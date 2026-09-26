// Зачем этот класс: экран до Ren'Py. Создаёт Documents/the_question_sideload
// (тот же путь, который sideload.rpe ищет на Android), распаковывает zip
// из incoming прямо в эту папку и только потом открывает PythonSDLActivity.

package com.artemdev.sideloadlab;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.provider.OpenableColumns;
import android.support.v4.content.FileProvider;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.renpy.android.PythonSDLActivity;
import org.renpy.android.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class LauncherActivity extends Activity {

    private static final int REQ_STORAGE = 41;
    private static final int REQ_PICK_IMAGE = 81;
    private static final int REQ_PICK_ZIP = 82;
    private static final String MOD_URL =
            "https://raw.githubusercontent.com/artem213101zse/renpy-sideload-test/main/tools/sample_mod.zip";
    private static final String RELEASES_URL =
            "https://api.github.com/repos/artem213101zse/renpy-sideload-test/releases/latest";

    private volatile String updateApkUrl;
    private volatile String updateApkName;
    private volatile String updateApkDigest;
    private volatile String updateApkShaUrl;

    private boolean downloadRunning = false;

    private TextView statusView;
    private TextView pathView;
    private TextView progressLine;
    private ProgressBar progressBar;
    private WebView webView;
    private boolean pageReady = false;
    private boolean nativeShown = false;
    private final StringBuilder webBuffer = new StringBuilder();
    private File sideloadDir;
    private File incomingDir;
    private File logFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!tryShowWebBios()) {
            showNativeBios();
        }

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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE && requestCode != REQ_PICK_ZIP) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            appendStatus("\nотмена");
            logLine("picker cancel");
            return;
        }
        if (requestCode == REQ_PICK_IMAGE) {
            copyWallpaper(data.getData());
        } else {
            copyPickedZip(data.getData());
        }
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
        try {
            if (!incomingDir.isDirectory() && !incomingDir.mkdirs()) {
                postStatus("\nНет папки incoming.");
                return;
            }
            String expected = fetchOptionalSha(MOD_URL + ".sha256");
            File dest = new File(incomingDir, "sample_mod.zip");
            boolean ok = downloadUrlToFile(MOD_URL, dest, expected);
            if (!ok) {
                return;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    appendStatus("\nСкачано. Ставлю zip.");
                    logLine("install sample_mod.zip");
                    installZips();
                }
            });
        } finally {
            downloadRunning = false;
        }
    }

    private boolean downloadUrlToFile(String url, File dest, String expectedHash) {
        File part = new File(dest.getAbsolutePath() + ".part");
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        boolean renamed = false;
        try {
            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                postStatus("\nНет папки для " + dest.getName());
                return false;
            }
            resetProgress();
            conn = openGet(url);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                postStatus("\nСервер ответил " + code);
                return false;
            }
            long total = contentLength(conn);
            in = conn.getInputStream();
            out = new FileOutputStream(part);
            byte[] buf = new byte[8192];
            long got = 0;
            long started = System.currentTimeMillis();
            long lastUi = 0;
            int lastLoggedPct = -10;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n <= 0) {
                    continue;
                }
                out.write(buf, 0, n);
                got += n;
                long now = System.currentTimeMillis();
                double speed = got / Math.max(0.001, (now - started) / 1000.0);
                int pct = total > 0 ? (int) ((got * 100L) / total) : -1;
                if (now - lastUi >= 200 || pct == 100) {
                    lastUi = now;
                    showProgress(got, total, speed);
                }
                if (pct >= 0 && pct / 10 != lastLoggedPct / 10) {
                    lastLoggedPct = pct;
                    postStatus("\n" + progressText(got, total, speed));
                } else if (pct < 0 && got == n) {
                    postStatus("\n" + progressText(got, total, speed));
                }
            }
            out.flush();
            out.close();
            out = null;
            double speed = got / Math.max(0.001, (System.currentTimeMillis() - started) / 1000.0);
            showProgress(got, total, speed);
            postStatus("\n" + progressText(got, total, speed));
            if (dest.exists() && !dest.delete()) {
                postStatus("\nНе удалось заменить " + dest.getName());
                return false;
            }
            if (!part.renameTo(dest)) {
                postStatus("\nНе удалось записать " + dest.getName());
                return false;
            }
            renamed = true;
            return acceptHash(dest, expectedHash);
        } catch (Exception e) {
            postStatus("\nСкачивание не удалось: " + messageOf(e));
            logLine("download failed " + messageOf(e));
            return false;
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
            if (conn != null) {
                conn.disconnect();
            }
            if (!renamed && part.exists()) {
                part.delete();
            }
        }
    }

    private HttpURLConnection openGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "SideloadLab/1.0");
        conn.connect();
        return conn;
    }

    private long contentLength(HttpURLConnection conn) {
        if (Build.VERSION.SDK_INT >= 24) {
            long len = conn.getContentLengthLong();
            return len > 0 ? len : -1L;
        }
        int len = conn.getContentLength();
        return len > 0 ? len : -1L;
    }

    private String fetchOptionalSha(String url) {
        HttpURLConnection conn = null;
        try {
            conn = openGet(url);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[256];
            int n = in.read(buf);
            in.close();
            if (n <= 0) {
                return null;
            }
            return normalizeHash(new String(buf, 0, n, "UTF-8"));
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String normalizeHash(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.trim().toLowerCase(Locale.US);
        int nl = line.indexOf('\n');
        if (nl >= 0) {
            line = line.substring(0, nl).trim();
        }
        if (line.startsWith("sha256:")) {
            line = line.substring("sha256:".length()).trim();
        }
        int space = line.indexOf(' ');
        if (space > 0) {
            line = line.substring(0, space);
        }
        if (line.length() != 64) {
            return null;
        }
        return line;
    }

    private boolean acceptHash(File file, String expectedHash) {
        String expected = normalizeHash(expectedHash);
        if (expected == null) {
            postStatus("\nхеш не задан, пропуск");
            logLine("hash skipped " + file.getName());
            return true;
        }
        try {
            String actual = sha256(file);
            if (expected.equals(actual)) {
                postStatus("\nхеш совпал");
                logLine("hash ok " + file.getName());
                return true;
            }
            postStatus("\nфайл битый, качни снова");
            logLine("hash mismatch " + file.getName());
            file.delete();
            return false;
        } catch (Exception e) {
            postStatus("\nНе удалось посчитать хеш: " + messageOf(e));
            logLine("hash error " + messageOf(e));
            file.delete();
            return false;
        }
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    digest.update(buf, 0, n);
                }
            }
        } finally {
            in.close();
        }
        byte[] raw = digest.digest();
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < raw.length; i++) {
            hex.append(String.format(Locale.US, "%02x", raw[i] & 0xff));
        }
        return hex.toString();
    }

    private void resetProgress() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setMax(100);
                    progressBar.setProgress(0);
                }
                if (progressLine != null) {
                    progressLine.setText("");
                }
            }
        });
    }

    private void showProgress(final long got, final long total, final double bytesPerSec) {
        final String line = progressText(got, total, bytesPerSec);
        final int pct = total > 0 ? (int) ((got * 100L) / total) : -1;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (progressLine != null) {
                    progressLine.setText(line);
                }
                if (progressBar != null) {
                    if (pct >= 0) {
                        progressBar.setIndeterminate(false);
                        progressBar.setMax(100);
                        progressBar.setProgress(pct > 100 ? 100 : pct);
                    } else {
                        progressBar.setIndeterminate(true);
                    }
                }
                pushWebProgress(line, pct);
            }
        });
    }

    private void pushWebProgress(final String line, final int pct) {
        if (webView == null || !pageReady) {
            return;
        }
        final String js = "if(window.biosProgress){window.biosProgress(" + jsString(line) + "," + pct + ");}";
        webView.post(new Runnable() {
            @Override
            public void run() {
                if (webView == null) {
                    return;
                }
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private String progressText(long got, long total, double bytesPerSec) {
        String speed = formatSpeed(bytesPerSec);
        if (total > 0) {
            int pct = (int) ((got * 100L) / total);
            if (pct > 100) {
                pct = 100;
            }
            return "скачано " + got + " / всего " + total + "  " + pct + "%  " + speed;
        }
        return "скачано " + got + " / всего неизвестно  " + speed;
    }

    private String formatSpeed(double bytesPerSec) {
        if (bytesPerSec >= 1024.0 * 1024.0) {
            return String.format(Locale.US, "%.1f МБ/с", bytesPerSec / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.0f КБ/с", bytesPerSec / 1024.0);
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

    private void pickImage() {
        if (sideloadDir == null) {
            resolvePaths();
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (Exception e) {
            appendStatus("\nПикер картинок не открылся: " + messageOf(e));
            logLine("pick image failed " + messageOf(e));
        }
    }

    private void pickZip() {
        if (incomingDir == null) {
            resolvePaths();
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/zip",
                "application/x-zip-compressed",
                "*/*"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_ZIP);
        } catch (Exception e) {
            appendStatus("\nПикер zip не открылся: " + messageOf(e));
            logLine("pick zip failed " + messageOf(e));
        }
    }

    private void copyWallpaper(Uri uri) {
        File dest = new File(sideloadDir, "custom_wallpaper.png");
        try {
            if (dest.getParentFile() != null && !dest.getParentFile().isDirectory()) {
                dest.getParentFile().mkdirs();
            }
            long bytes = copyUriToFile(uri, dest);
            String name = displayName(uri);
            appendStatus("\nКартинка " + name + ", байт " + bytes + "\n" + dest.getAbsolutePath());
            logLine("wallpaper " + name + " bytes " + bytes + " " + dest.getAbsolutePath());
        } catch (Exception e) {
            appendStatus("\nКартинка не скопирована: " + messageOf(e));
            logLine("wallpaper failed " + messageOf(e));
        }
    }

    private void copyPickedZip(Uri uri) {
        String name = displayName(uri);
        if (name != null && !name.toLowerCase(Locale.US).endsWith(".zip")) {
            appendStatus("\nЭто не zip: " + name);
            logLine("pick not zip " + name);
            return;
        }
        if (incomingDir == null) {
            resolvePaths();
        }
        if (!incomingDir.isDirectory() && !incomingDir.mkdirs()) {
            appendStatus("\nПапка incoming не создана.");
            logLine("pick zip no incoming");
            return;
        }
        File dest = new File(incomingDir, "picked.zip");
        try {
            long bytes = copyUriToFile(uri, dest);
            appendStatus("\nZip " + name + ", байт " + bytes + "\n" + dest.getAbsolutePath());
            logLine("picked zip " + name + " bytes " + bytes);
            if (zipHasExtraHello(dest)) {
                appendStatus("\nВ архиве extra_hello.rpy. Ставлю мод.");
                logLine("picked zip install mod");
                unzipIntoSideload(dest);
            } else {
                appendStatus("\nВ архиве нет extra_hello.rpy. Импорт сейвов.");
                logLine("picked zip import saves");
                File saves = savesDir();
                if (!saves.isDirectory() && !saves.mkdirs()) {
                    appendStatus("\nПапка saves не создана.");
                    logLine("picked zip saves mkdir failed");
                    return;
                }
                int count = unzipReplace(dest, saves);
                appendStatus("\nИмпорт сейвов " + dest.getAbsolutePath() + ", файлов: " + count);
                logLine("picked zip import " + dest.getAbsolutePath() + " files " + count);
            }
        } catch (Exception e) {
            appendStatus("\nZip не скопирован: " + messageOf(e));
            logLine("picked zip failed " + messageOf(e));
        }
    }

    private boolean zipHasExtraHello(File zip) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(zip);
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name == null) {
                    continue;
                }
                String norm = name.replace('\\', '/');
                if (norm.endsWith("extra_hello.rpy")) {
                    return true;
                }
            }
        } catch (Exception e) {
            appendStatus("\nАрхив не прочитан: " + messageOf(e));
            logLine("picked zip read failed " + messageOf(e));
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    private long copyUriToFile(Uri uri, File dest) throws IOException {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new IOException("пустой поток");
            }
            out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
            }
            out.flush();
            return total;
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

    private String displayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return uri.getLastPathSegment();
    }

    private void checkUpdate() {
        appendStatus("\nПроверяю релиз.");
        new Thread(new Runnable() {
            @Override
            public void run() {
                checkUpdateInBackground();
            }
        }).start();
    }

    private void checkUpdateInBackground() {
        HttpURLConnection conn = null;
        try {
            conn = openGet(RELEASES_URL);
            int code = conn.getResponseCode();
            if (code == 404) {
                updateApkUrl = null;
                postStatus("\nРелиза нет.");
                logLine("release missing");
                return;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                postStatus("\nСервер релизов ответил " + code);
                logLine("release http " + code);
                return;
            }
            String body = readStream(conn.getInputStream());
            JSONObject root = new JSONObject(body);
            String tag = root.optString("tag_name", "");
            JSONArray assets = root.optJSONArray("assets");
            StringBuilder report = new StringBuilder();
            report.append("\nРелиз ").append(tag.length() == 0 ? "(без tag)" : tag);
            String apkUrl = null;
            String apkName = null;
            String digest = null;
            String shaUrl = null;
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) {
                        continue;
                    }
                    String name = asset.optString("name", "");
                    report.append("\nasset ").append(name);
                    String lower = name.toLowerCase(Locale.US);
                    if (apkUrl == null && lower.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", "");
                        apkName = name;
                        digest = asset.optString("digest", "");
                    }
                }
                if (apkName != null) {
                    String shaName = apkName + ".sha256";
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset != null && shaName.equals(asset.optString("name", ""))) {
                            shaUrl = asset.optString("browser_download_url", "");
                        }
                    }
                }
            }
            updateApkUrl = apkUrl;
            updateApkName = apkName;
            updateApkDigest = digest;
            updateApkShaUrl = shaUrl;
            long installed = currentVersionCode();
            report.append("\nтекущий versionCode ").append(installed);
            if (apkUrl == null || apkUrl.length() == 0) {
                updateApkUrl = null;
                report.append("\nВ релизе нет apk.");
            } else {
                report.append("\nНайден ").append(apkName);
                report.append("\nПоставить можно.");
            }
            postStatus(report.toString());
            logLine("release " + tag + " apk " + apkName);
        } catch (Exception e) {
            postStatus("\nПроверка релиза не удалась: " + messageOf(e));
            logLine("release failed " + messageOf(e));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void downloadUpdate() {
        if (downloadRunning) {
            appendStatus("\nСкачивание уже идёт.");
            return;
        }
        downloadRunning = true;
        appendStatus("\nГотовлю apk.");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (updateApkUrl == null || updateApkUrl.length() == 0) {
                        checkUpdateInBackground();
                    }
                    if (updateApkUrl == null || updateApkUrl.length() == 0) {
                        postStatus("\nКачать нечего: apk в релизе нет.");
                        return;
                    }
                    String expected = updateApkDigest;
                    if (normalizeHash(expected) == null && updateApkShaUrl != null && updateApkShaUrl.length() > 0) {
                        expected = fetchOptionalSha(updateApkShaUrl);
                    }
                    final File dest = new File(getFilesDir(), "update.apk");
                    boolean ok = downloadUrlToFile(updateApkUrl, dest, expected);
                    if (!ok) {
                        return;
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            installDownloadedApk(dest);
                        }
                    });
                } finally {
                    downloadRunning = false;
                }
            }
        }).start();
    }

    private void installDownloadedApk(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            appendStatus("\nРазреши установку из этого приложения, потом нажми кнопку снова.");
            logLine("need REQUEST_INSTALL_PACKAGES");
            try {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                settings.setData(Uri.parse("package:" + getPackageName()));
                startActivity(settings);
            } catch (Exception e) {
                appendStatus("\nНастройки установки не открылись: " + messageOf(e));
            }
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            appendStatus("\nОткрыл установщик " + apk.getName());
            logLine("install apk " + apk.getAbsolutePath());
        } catch (Exception e) {
            appendStatus("\nУстановщик не открылся: " + messageOf(e));
            logLine("install apk failed " + messageOf(e));
        }
    }

    private long currentVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Exception e) {
            return -1L;
        }
    }

    private String readStream(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        StringBuilder body = new StringBuilder();
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) {
                body.append(new String(buf, 0, n, "UTF-8"));
            }
        }
        in.close();
        return body.toString();
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

    private boolean tryShowWebBios() {
        try {
            WebView web = new WebView(this);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setAllowFileAccess(true);
            settings.setDomStorageEnabled(false);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    pageReady = true;
                    flushWebBuffer();
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    if (failingUrl != null && failingUrl.indexOf("index.html") >= 0) {
                        showNativeBios();
                    }
                }
            });
            web.loadUrl("file:///android_asset/www/index.html");
            webView = web;
            setContentView(web);
            return true;
        } catch (Throwable t) {
            webView = null;
            return false;
        }
    }

    private void showNativeBios() {
        if (nativeShown) {
            return;
        }
        nativeShown = true;
        pageReady = false;
        webView = null;
        setContentView(R.layout.activity_launcher);
        pathView = (TextView) findViewById(R.id.bios_path);
        statusView = (TextView) findViewById(R.id.bios_log);
        progressBar = (ProgressBar) findViewById(R.id.bios_progress);
        progressLine = (TextView) findViewById(R.id.bios_progress_line);
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
        findViewById(R.id.bios_update_check).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate();
            }
        });
        findViewById(R.id.bios_update_install).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadUpdate();
            }
        });
        findViewById(R.id.bios_pick_image).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
        findViewById(R.id.bios_pick_zip).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickZip();
            }
        });
        findViewById(R.id.bios_start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startGame();
            }
        });
    }

    private void pushWebLog(String text) {
        if (text == null) {
            return;
        }
        if (webView == null || !pageReady) {
            webBuffer.append(text);
            return;
        }
        evalJs("biosLog", text);
    }

    private void flushWebBuffer() {
        if (webBuffer.length() == 0) {
            return;
        }
        String text = webBuffer.toString();
        webBuffer.setLength(0);
        evalJs("biosLog", text);
    }

    private void evalJs(String fn, String arg) {
        if (webView == null) {
            return;
        }
        final String js = "if(window." + fn + "){window." + fn + "(" + jsString(arg) + ");}";
        webView.post(new Runnable() {
            @Override
            public void run() {
                if (webView == null) {
                    return;
                }
                if (Build.VERSION.SDK_INT >= 19) {
                    webView.evaluateJavascript(js, null);
                } else {
                    webView.loadUrl("javascript:" + js);
                }
            }
        });
    }

    private String jsString(String text) {
        StringBuilder out = new StringBuilder();
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"') {
                out.append('\\');
                out.append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else {
                out.append(c);
            }
        }
        out.append('"');
        return out.toString();
    }

    private void setStatus(String text) {
        if (statusView != null) {
            statusView.setText(text);
        }
        pushWebLog(text);
    }

    private void appendStatus(String text) {
        if (statusView != null) {
            statusView.append(text);
        }
        pushWebLog(text);
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
