package com.trost.ioskeyboard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** Loads an app-owned cache and downloads only the validated layout catalog. */
public final class LanguagePackStore {
    private static final String DOWNLOAD_URL = "https://raw.githubusercontent.com/rostik7777888/Vision-v3/hoplite/datala-149318df/android-keyboard/assets/language-packs.json";
    private static final String DOWNLOAD_HOST = "raw.githubusercontent.com";
    private static final int TIMEOUT_MS = 5000;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "language-pack-store");
        thread.setDaemon(true);
        return thread;
    });

    private LanguagePackStore() {
    }

    public interface Callback {
        void onComplete(boolean success, String message);
    }

    public static void load(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            LanguagePackData pack = readCache(new File(app.getFilesDir(), "language-packs.json"));
            String message = "Локальный каталог загружен";
            if (pack == null) {
                pack = readAsset(app);
                message = "Встроенный каталог загружен";
            }
            if (pack != null) LanguageCatalog.installRemote(pack);
            post(callback, pack != null, pack == null ? "Каталог языков недоступен" : message);
        });
    }

    public static void refresh(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                byte[] bytes = download();
                LanguagePackData pack = LanguagePackData.parse(bytes);
                File cache = new File(app.getFilesDir(), "language-packs.json");
                if (!replaceCache(cache, bytes)) throw new IOException("Не удалось безопасно сохранить каталог");
                LanguageCatalog.installRemote(pack);
                post(callback, true, "Раскладки обновлены");
            } catch (Exception error) {
                android.util.Log.w("FishkaLayouts","Catalog download failed",error);
                post(callback, false, "Не удалось обновить раскладки: " + safeMessage(error));
            }
        });
    }

    private static LanguagePackData readAsset(Context context) {
        try (InputStream input = context.getAssets().open("language-packs.json")) {
            return LanguagePackData.parse(readBounded(input));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] download() throws IOException {
        URL current = new URL(DOWNLOAD_URL);
        for (int redirects = 0; redirects <= 2; redirects++) {
            requireTrustedUrl(current);
            HttpsURLConnection connection = (HttpsURLConnection) current.openConnection();
            try {
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/json");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == 2) throw new IOException("Слишком много перенаправлений");
                    URL redirected = new URL(current, location);
                    requireTrustedUrl(redirected);
                    current = redirected;
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status);
                if (connection.getContentLengthLong() > LanguagePackData.MAX_BYTES) {
                    throw new IOException("Каталог превышает допустимый размер");
                }
                try (InputStream input = connection.getInputStream()) {
                    return readBounded(input);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Не удалось загрузить каталог");
    }

    private static void requireTrustedUrl(URL url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !DOWNLOAD_HOST.equalsIgnoreCase(url.getHost())
                || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)) {
            throw new IOException("Перенаправление на недоверенный адрес запрещено");
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > LanguagePackData.MAX_BYTES) throw new IOException("Каталог превышает допустимый размер");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static LanguagePackData readCache(File cache) {
        LanguagePackData pack = readCacheFile(cache);
        return pack != null ? pack : readCacheFile(new File(cache.getPath() + ".bak"));
    }

    private static LanguagePackData readCacheFile(File file) {
        if (!file.isFile() || file.length() > LanguagePackData.MAX_BYTES) return null;
        try (InputStream input = new FileInputStream(file)) {
            return LanguagePackData.parse(readBounded(input));
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean replaceCache(File cache, byte[] bytes) {
        File backup = new File(cache.getPath() + ".bak");
        File temp = new File(cache.getPath() + ".tmp");
        boolean previousMoved = false;
        try {
            LanguagePackData.parse(bytes);
            File parent = cache.getAbsoluteFile().getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
            if (temp.exists() && !temp.delete()) return false;
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(bytes);
                output.getFD().sync();
            }

            boolean currentIsValid = readCacheFile(cache) != null;
            boolean backupIsValid = readCacheFile(backup) != null;
            if (currentIsValid) {
                if (backup.exists() && !backup.delete()) return failUpdate(temp, backup, cache, false);
                if (!cache.renameTo(backup)) return failUpdate(temp, backup, cache, false);
                previousMoved = true;
            } else if (backupIsValid) {
                if (cache.exists() && !cache.delete()) return failUpdate(temp, backup, cache, false);
                if (!backup.renameTo(cache)) return failUpdate(temp, backup, cache, false);
                if (!cache.renameTo(backup)) return failUpdate(temp, backup, cache, false);
                previousMoved = true;
            } else {
                if (cache.exists() && !cache.delete()) return failUpdate(temp, backup, cache, false);
                if (backup.exists() && !backup.delete()) return failUpdate(temp, backup, cache, false);
            }

            if (!temp.renameTo(cache)) {
                return failUpdate(temp, backup, cache, previousMoved);
            }
            if (backup.exists()) backup.delete();
            return true;
        } catch (Exception ignored) {
            failUpdate(temp, backup, cache, previousMoved);
            return false;
        }
    }

    private static boolean failUpdate(File temp, File backup, File cache, boolean previousMoved) {
        if (previousMoved && !cache.exists() && backup.exists()) backup.renameTo(cache);
        temp.delete();
        return false;
    }

    private static void post(Callback callback, boolean success, String message) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(() -> callback.onComplete(success, message));
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? "ошибка соединения" : message;
    }
}
