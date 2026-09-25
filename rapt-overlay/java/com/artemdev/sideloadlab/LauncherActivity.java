// Зачем этот класс: экран до Ren'Py. Создаёт Documents/the_question_sideload
// (тот же путь, который sideload.rpe ищет на Android), распаковывает zip
// из incoming прямо в эту папку и только потом открывает PythonSDLActivity.

package com.artemdev.sideloadlab;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.support.v4.content.FileProvider;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import org.renpy.android.PythonSDLActivity;
import org.renpy.android.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class LauncherActivity extends Activity {

    private static final int REQ_STORAGE = 41;
    private static final String MOD_URL =
            "https://raw.githubusercontent.com/artem213101zse/renpy-sideload-test/main/tools/sample_mod.zip";

    private boolean downloadRunning = false;

    private TextView statusView;
    private TextView pathView;
    private File sideloadDir;
    private File incomingDir;
    private File logFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        pathView = (TextView) findViewById(R.id.bios_path);
        statusView = (TextView) findViewById(R.id.bios_log);
        findViewById(R.id.bios_download).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadMod();
            }
        });
        findViewById(R.id.bios_install).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installZips();
            }
        });
        findViewById(R.id.bios_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteMod();
            }
        });
        findViewById(R.id.bios_export_saves).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportSaves();
            }
        });
        findViewById(R.id.bios_import_saves).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importSaves();
            }
        });
        findViewById(R.id.bios_share_saves).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareSaves();
            }
        });
        findViewById(R.id.bios_start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startGame();
            }
        });

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
            showPath();
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

    private void showPath() {
        if (pathView != null) {
            pathView.setText(pathReport());
        }
    }

    private void prepareFolders() {
        showPath();
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

    private void downloadMod() {
        if (downloadRunning) {
            appendStatus("\nСкачивание уже идёт.");
            return;
        }
        if (sideloadDir == null || incomingDir == null) {
            resolvePaths();
        }
        downloadRunning = true;
        appendStatus("\nКачаю " + MOD_URL);
        new Thread(new Runnable() {
            @Override
            public void run() {
                downloadModInBackground();
            }
        }).start();
    }

    private void downloadModInBackground() {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        File part = new File(incomingDir, "sample_mod.zip.part");
        File dest = new File(incomingDir, "sample_mod.zip");
        try {
            if (!incomingDir.isDirectory() && !incomingDir.mkdirs()) {
                postStatus("\nНет папки incoming.");
                return;
            }
            URL url = new URL(MOD_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "SideloadBIOS");
            conn.connect();
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                postStatus("\nСервер ответил " + code);
                return;
            }
            int total = conn.getContentLength();
            in = conn.getInputStream();
            out = new FileOutputStream(part);
            byte[] buf = new byte[8192];
            int got = 0;
            int lastPct = -1;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) {
                    continue;
                }
                out.write(buf, 0, n);
                got += n;
                if (total > 0) {
                    int pct = (int) ((got * 100L) / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        postStatus("\n" + pct + "%  скачано " + got + " / " + total);
                    }
                } else if (got == n || got % 32768 < n) {
                    postStatus("\nскачано " + got + " / неизвестно");
                }
            }
            out.flush();
            out.close();
            out = null;
            if (dest.exists() && !dest.delete()) {
                postStatus("\nНе удалось заменить старый sample_mod.zip");
                return;
            }
            if (!part.renameTo(dest)) {
                postStatus("\nНе удалось записать sample_mod.zip");
                return;
            }
            final int done = got;
            final int size = total;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (size > 0) {
                        appendStatus("\n100%  скачано " + done + " / " + size);
                    } else {
                        appendStatus("\nскачано " + done + " / " + done);
                    }
                    appendStatus("\nСкачано. Ставлю zip.");
                    logLine("downloaded sample_mod.zip bytes " + done);
                    installZips();
                }
            });
        } catch (Exception e) {
            postStatus("\nСкачивание не удалось: " + messageOf(e));
            logLine("download failed " + messageOf(e));
        } finally {
            downloadRunning = false;
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
            if (conn != null) {
                conn.disconnect();
            }
            if (part.exists()) {
                part.delete();
            }
        }
    }

    private void postStatus(final String line) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                appendStatus(line);
            }
        });
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

    private File savesDir() {
        if (sideloadDir == null) {
            resolvePaths();
        }
        return new File(sideloadDir, "saves");
    }

    private File backupsDir() {
        if (sideloadDir == null) {
            resolvePaths();
        }
        return new File(sideloadDir, "backups");
    }

    private void exportSaves() {
        File saves = savesDir();
        File backups = backupsDir();
        if (!saves.isDirectory()) {
            appendStatus("\nЭкспорт: папки saves нет.");
            logLine("export saves missing");
            return;
        }
        if (!backups.isDirectory() && !backups.mkdirs()) {
            appendStatus("\nЭкспорт: не создана папка backups.");
            logLine("export backups failed");
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
        File zip = new File(backups, "saves_" + stamp + ".zip");
        ZipOutputStream zos = null;
        int count = 0;
        try {
            zos = new ZipOutputStream(new FileOutputStream(zip));
            count = addTreeToZip(zos, saves, "");
            zos.finish();
            appendStatus("\nЭкспорт " + zip.getAbsolutePath() + ", файлов: " + count);
            logLine("export " + zip.getAbsolutePath() + " files " + count);
        } catch (Exception e) {
            appendStatus("\nЭкспорт не удался: " + messageOf(e));
            logLine("export failed " + messageOf(e));
        } finally {
            if (zos != null) {
                try {
                    zos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private int addTreeToZip(ZipOutputStream zos, File dir, String prefix) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (file == null) {
                continue;
            }
            String name = file.getName();
            if (name == null || name.indexOf("..") >= 0) {
                continue;
            }
            String entryName = prefix.length() == 0 ? name : prefix + "/" + name;
            if (file.isDirectory()) {
                count += addTreeToZip(zos, file, entryName);
            } else if (file.isFile()) {
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                copyFileToStream(file, zos);
                zos.closeEntry();
                count++;
            }
        }
        return count;
    }

    private void copyFileToStream(File file, ZipOutputStream zos) throws IOException {
        InputStream in = null;
        try {
            in = new java.io.FileInputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    zos.write(buf, 0, n);
                }
            }
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }

    private void shareSaves() {
        if (newestBackupZip() == null) {
            exportSaves();
        }
        File zip = newestBackupZip();
        if (zip == null || !zip.isFile()) {
            appendStatus("\nПоделиться: нет zip бэкапа.");
            logLine("share no zip");
            return;
        }
        try {
            String authority = getPackageName() + ".fileprovider";
            Uri uri = FileProvider.getUriForFile(this, authority, zip);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newRawUri("saves", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "Отправить бэкап");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
            appendStatus("\nПоделиться " + zip.getAbsolutePath());
            logLine("share " + zip.getAbsolutePath());
        } catch (Exception e) {
            appendStatus("\nПоделиться не удалось: " + messageOf(e));
            logLine("share failed " + messageOf(e));
        }
    }

    private void importSaves() {
        File zip = newestBackupZip();
        String from = "backups";
        if (zip == null && incomingDir != null) {
            File incomingZip = new File(incomingDir, "saves.zip");
            if (incomingZip.isFile()) {
                zip = incomingZip;
                from = "incoming";
            }
        }
        if (zip == null) {
            appendStatus("\nИмпорт: нет zip в backups и нет incoming/saves.zip.");
            logLine("import no zip");
            return;
        }
        File saves = savesDir();
        if (!saves.isDirectory() && !saves.mkdirs()) {
            appendStatus("\nИмпорт: не создана папка saves.");
            logLine("import saves mkdir failed");
            return;
        }
        int count = unzipReplace(zip, saves);
        appendStatus("\nИмпорт " + from + " " + zip.getAbsolutePath() + ", файлов: " + count);
        logLine("import " + zip.getAbsolutePath() + " files " + count);
    }

    private File newestBackupZip() {
        File backups = backupsDir();
        File[] files = backups.listFiles();
        if (files == null) {
            return null;
        }
        File best = null;
        long bestTime = -1L;
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (file == null || !file.isFile()) {
                continue;
            }
            String name = file.getName();
            if (name == null || !name.toLowerCase(Locale.US).endsWith(".zip")) {
                continue;
            }
            long when = file.lastModified();
            if (best == null || when > bestTime) {
                best = file;
                bestTime = when;
            }
        }
        return best;
    }

    private int unzipReplace(File zip, File destDir) {
        ZipFile zipFile = null;
        int count = 0;
        try {
            zipFile = new ZipFile(zip);
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (forbiddenZipName(name)) {
                    appendStatus("\nПропущен путь с .. : " + name);
                    logLine("import reject " + name);
                    continue;
                }
                String relative = name.replace('\\', '/');
                File out = new File(destDir, relative);
                if (!staysInside(destDir, out)) {
                    appendStatus("\nПропущен путь вне saves: " + name);
                    logLine("import outside " + name);
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
                count++;
            }
        } catch (Exception e) {
            appendStatus("\nИмпорт не удался: " + messageOf(e));
            logLine("import failed " + messageOf(e));
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (IOException ignored) {
                }
            }
        }
        return count;
    }

    private void deleteMod() {
        if (sideloadDir == null) {
            resolvePaths();
        }
        String[] names = new String[] {
                "extra_hello.rpy",
                "extra_hello.png",
                "extra_hello.rpyc"
        };
        StringBuilder report = new StringBuilder();
        report.append("\nУдаление мода:");
        for (int i = 0; i < names.length; i++) {
            File file = new File(sideloadDir, names[i]);
            try {
                if (!file.exists()) {
                    report.append("\nнет ").append(names[i]);
                    logLine("delete missing " + names[i]);
                    continue;
                }
                if (file.isDirectory()) {
                    report.append("\nпропуск каталога ").append(names[i]);
                    logLine("delete skip dir " + names[i]);
                    continue;
                }
                if (file.delete()) {
                    report.append("\nудалён ").append(names[i]);
                    logLine("deleted " + file.getAbsolutePath());
                } else {
                    report.append("\nне удалился ").append(names[i]);
                    logLine("delete failed " + file.getAbsolutePath());
                }
            } catch (SecurityException e) {
                report.append("\nнет права на ").append(names[i]);
                logLine("delete denied " + names[i] + " " + messageOf(e));
            }
        }
        appendStatus(report.toString());
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
