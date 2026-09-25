// Зачем этот класс: экран до Ren'Py. Создаёт Documents/the_question_sideload
// (тот же путь, который sideload.rpe ищет на Android), распаковывает zip
// из incoming прямо в эту папку и только потом открывает PythonSDLActivity.

package com.artemdev.sideloadlab;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.renpy.android.PythonSDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class LauncherActivity extends Activity {

    private static final int REQ_STORAGE = 41;

    private TextView statusView;
    private File sideloadDir;
    private File incomingDir;
    private File logFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(32, 32, 32, 32);

        statusView = new TextView(this);
        statusView.setTextColor(Color.BLACK);
        statusView.setTextSize(16);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(statusView);

        Button install = new Button(this);
        install.setText("Установить zip из incoming");
        install.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installZips();
            }
        });

        Button start = new Button(this);
        start.setText("Запустить игру");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startGame();
            }
        });

        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(install, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(start, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        resolvePaths();
        installNativeEngine();
        if (needsRuntimePermission()) {
            setStatus("Запрашиваю доступ к памяти.\n" + pathReport());
            requestPermissions(new String[] {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQ_STORAGE);
            return;
        }
        prepareFolders();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) {
            return;
        }
        boolean granted = true;
        if (grantResults == null || grantResults.length == 0) {
            granted = false;
        } else {
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                }
            }
        }
        if (!granted) {
            setStatus("Нет права на память. Папки не созданы.\n" + pathReport());
            return;
        }
        prepareFolders();
    }

    private void installNativeEngine() {
        // Только getFilesDir()/hello_engine. В Documents бинарь не кладём.
        String abiName = nativeEngineAssetName();
        if (abiName == null) {
            appendStatus("\nНативный движок: ABI не arm64-v8a и не x86_64. Останется /system/bin/sh.");
            return;
        }
        File dest = new File(getFilesDir(), "hello_engine");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = openEngineStream(abiName);
            if (in == null) {
                appendStatus("\nНативный движок: " + abiName + " нет в assets. Останется /system/bin/sh.");
                return;
            }
            out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    out.write(buf, 0, n);
                }
            }
            out.flush();
            chmod755(dest);
            appendStatus("\nНативный движок: " + dest.getAbsolutePath());
            logLine("copied " + abiName + " to " + dest.getAbsolutePath());
        } catch (Exception e) {
            appendStatus("\nНе удалось скопировать hello_engine: " + messageOf(e));
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private String nativeEngineAssetName() {
        String abi = "";
        if (Build.VERSION.SDK_INT >= 21) {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis != null && abis.length > 0 && abis[0] != null) {
                abi = abis[0];
            }
        } else {
            abi = Build.CPU_ABI == null ? "" : Build.CPU_ABI;
        }
        abi = abi.toLowerCase(Locale.US);
        if (abi.startsWith("arm64")) {
            return "hello_engine-arm64";
        }
        if (abi.startsWith("x86_64")) {
            return "hello_engine-x86_64";
        }
        return null;
    }

    private InputStream openEngineStream(String name) {
        String[] assets = new String[] {
                name,
                "bin/" + name,
                "x-" + name,
                "x-bin/x-" + name,
                "x-rapt-overlay/x-bin/x-" + name
        };
        for (int i = 0; i < assets.length; i++) {
            try {
                return getAssets().open(assets[i]);
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private void chmod755(File dest) {
        dest.setReadable(true, false);
        dest.setWritable(true, true);
        dest.setExecutable(true, false);
        try {
            Process chmod = Runtime.getRuntime().exec(new String[] {
                    "chmod", "755", dest.getAbsolutePath()
            });
            chmod.waitFor();
        } catch (Exception e) {
            appendStatus("\nchmod 755: " + messageOf(e));
        }
    }

    private void resolvePaths() {
        File documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        sideloadDir = new File(documents, "the_question_sideload");
        incomingDir = new File(sideloadDir, "incoming");
        logFile = new File(sideloadDir, "launcher.log");
    }

    private boolean needsRuntimePermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            return false;
        }
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }

    private boolean allFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            return true;
        }
        try {
            return Environment.isExternalStorageManager();
        } catch (Throwable t) {
            return false;
        }
    }

    private void prepareFolders() {
        StringBuilder report = new StringBuilder();
        report.append(pathReport());
        if (Build.VERSION.SDK_INT >= 30 && !allFilesAccess()) {
            report.append("\nНет MANAGE_EXTERNAL_STORAGE. Разрешите доступ ко всем файлам.");
        }
        try {
            boolean sideloadOk = sideloadDir.isDirectory() || sideloadDir.mkdirs();
            boolean incomingOk = incomingDir.isDirectory() || incomingDir.mkdirs();
            if (!sideloadOk || !incomingOk) {
                report.append("\nПапки: не созданы.");
                setStatus(report.toString());
                return;
            }
            report.append("\nПапки: созданы.");
            logLine("folders ready");
            report.append("\nЛог: ").append(logFile.getAbsolutePath());
        } catch (SecurityException e) {
            report.append("\nНет права создать папки: ").append(messageOf(e));
        } catch (Exception e) {
            report.append("\nНе удалось создать папки: ").append(messageOf(e));
        }
        setStatus(report.toString());
    }

    private String pathReport() {
        String path = sideloadDir == null ? "(нет пути)" : sideloadDir.getAbsolutePath();
        String incoming = incomingDir == null ? "(нет пути)" : incomingDir.getAbsolutePath();
        return "Путь: " + path + "\nincoming: " + incoming;
    }

    private void installZips() {
        if (Build.VERSION.SDK_INT >= 30 && !allFilesAccess()) {
            appendStatus("\nНет доступа ко всем файлам. Открываю настройки.");
            openAllFilesSettings();
            return;
        }
        if (incomingDir == null || !incomingDir.isDirectory()) {
            appendStatus("\nПапка incoming не создана.");
            return;
        }
        File[] files;
        try {
            files = incomingDir.listFiles();
        } catch (SecurityException e) {
            appendStatus("\nНет права читать incoming: " + messageOf(e));
            return;
        }
        if (files == null) {
            appendStatus("\nНе удалось прочитать incoming.");
            return;
        }
        int zips = 0;
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (file == null || !file.isFile()) {
                continue;
            }
            String name = file.getName();
            if (name == null || !name.toLowerCase(Locale.US).endsWith(".zip")) {
                continue;
            }
            zips++;
            unzipIntoSideload(file);
        }
        if (zips == 0) {
            appendStatus("\nВ incoming нет zip.");
        }
    }

    private void unzipIntoSideload(File zip) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(zip);
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            int written = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (forbiddenZipName(name)) {
                    appendStatus("\nПропущен путь с .. : " + name);
                    logLine("reject " + name);
                    continue;
                }
                String relative = name.replace('\\', '/');
                File out = new File(sideloadDir, relative);
                if (!staysInside(sideloadDir, out)) {
                    appendStatus("\nПропущен путь вне папки: " + name);
                    logLine("reject outside " + name);
                    continue;
                }
                if (entry.isDirectory() || relative.endsWith("/")) {
                    if (!out.isDirectory() && !out.mkdirs()) {
                        appendStatus("\nНе создан каталог " + relative);
                    }
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    appendStatus("\nНе создан каталог для " + relative);
                    continue;
                }
                copyEntry(zipFile, entry, out);
                written++;
            }
            appendStatus("\nУстановлен " + zip.getName() + ", файлов: " + written);
            logLine("installed " + zip.getName() + " files " + written);
        } catch (SecurityException e) {
            appendStatus("\nНет права распаковать " + zip.getName() + ": " + messageOf(e));
        } catch (Exception e) {
            appendStatus("\nОшибка zip " + zip.getName() + ": " + messageOf(e));
            logLine("zip error " + zip.getName() + " " + messageOf(e));
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void copyEntry(ZipFile zipFile, ZipEntry entry, File out) throws IOException {
        InputStream in = null;
        FileOutputStream outs = null;
        try {
            in = zipFile.getInputStream(entry);
            outs = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    outs.write(buf, 0, n);
                }
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (outs != null) {
                try {
                    outs.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private boolean forbiddenZipName(String name) {
        if (name == null || name.length() == 0) {
            return true;
        }
        String norm = name.replace('\\', '/');
        if (norm.startsWith("/")) {
            return true;
        }
        return norm.indexOf("..") >= 0;
    }

    private boolean staysInside(File root, File candidate) {
        try {
            String base = root.getCanonicalPath();
            String target = candidate.getCanonicalPath();
            return target.equals(base) || target.startsWith(base + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private void startGame() {
        try {
            Intent intent = new Intent(this, PythonSDLActivity.class);
            startActivity(intent);
            logLine("start PythonSDLActivity");
        } catch (Exception e) {
            appendStatus("\nНе удалось запустить игру: " + messageOf(e));
        }
    }

    private void openAllFilesSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception e2) {
                appendStatus("\nНе удалось открыть настройки: " + messageOf(e2));
            }
        }
    }

    private void logLine(String message) {
        if (logFile == null) {
            return;
        }
        FileWriter writer = null;
        try {
            File parent = logFile.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            writer = new FileWriter(logFile, true);
            writer.write(stamp);
            writer.write(" ");
            writer.write(message);
            writer.write("\n");
        } catch (Exception ignored) {
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void setStatus(String text) {
        statusView.setText(text);
    }

    private void appendStatus(String text) {
        statusView.append(text);
    }

    private String messageOf(Throwable t) {
        if (t == null) {
            return "";
        }
        String message = t.getMessage();
        if (message == null || message.length() == 0) {
            return t.getClass().getSimpleName();
        }
        return message;
    }
}
