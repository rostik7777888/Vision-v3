package com.trost.ioskeyboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class ClipboardPanel {
    private static final String ALIAS = "trost_clipboard_v1";
    private static final String FILE = "clipboard-history.bin";
    private static final String SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE";
    private static final int IV_SIZE = 12;
    private static final Object STORES_LOCK = new Object();
    private static final Map<InputMethodService, Store> STORES = new WeakHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong FILE_EPOCH = new AtomicLong();
    private static volatile boolean suppressUntilClipEvent;
    private static volatile ClipboardHistory SHARED_HISTORY = new ClipboardHistory();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "trost-clipboard-history");
            thread.setDaemon(true);
            return thread;
        }
    });

    private ClipboardPanel() { }

    public static void start(InputMethodService service) {
        if (service == null) return;
        onMain(() -> storeFor(service).start());
    }

    public static void pause(InputMethodService service) {
        if (service == null) return;
        onMain(() -> {
            Store store = findStore(service);
            if (store != null) store.pause();
        });
    }

    public static void stop(InputMethodService service) {
        if (service == null) return;
        onMain(() -> {
            Store store;
            synchronized (STORES_LOCK) { store = STORES.remove(service); }
            if (store != null) store.pause();
        });
    }

    public static void show(InputMethodService service, View keyboard, boolean sensitive) {
        if (service == null || keyboard == null) return;
        onMain(() -> {
            if (sensitive || protectedField(service.getCurrentInputEditorInfo())) return;
            Store store = storeFor(service);
            store.start();
            new Panel(service, keyboard, store).open();
        });
    }

    public static void clear(Context context) {
        if (context == null) return;
        ArrayList<Store> stores = new ArrayList<>();
        ClipboardHistory empty = new ClipboardHistory();
        synchronized (STORES_LOCK) {
            FILE_EPOCH.incrementAndGet();
            suppressUntilClipEvent = true;
            SHARED_HISTORY = empty;
            stores.addAll(STORES.values());
            for (Store store : stores) store.clearImmediately(empty);
        }
        File directory = context.getFilesDir();
        submit(() -> {
            deleteFile(directory, FILE);
            deleteFile(directory, FILE + ".tmp");
        });
        refreshStores();
    }

    private static void onMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run();
        else MAIN.post(runnable);
    }

    private static void submit(Runnable runnable) {
        try { IO.execute(runnable); } catch (RuntimeException ignored) { }
    }

    private static Store findStore(InputMethodService service) {
        synchronized (STORES_LOCK) { return STORES.get(service); }
    }

    private static Store storeFor(InputMethodService service) {
        synchronized (STORES_LOCK) {
            Store store = STORES.get(service);
            if (store == null) {
                store = new Store(service);
                STORES.put(service, store);
            }
            return store;
        }
    }

    private static final class Store {
        final Object stateLock = new Object();
        final WeakReference<InputMethodService> service;
        final WeakReference<ClipboardManager> manager;
        final SharedPreferences preferences;
        final File filesDirectory;
        final ClipboardManager.OnPrimaryClipChangedListener listener;
        volatile WeakReference<Panel> panel = new WeakReference<>(null);
        volatile ClipboardHistory history;
        volatile boolean active;
        volatile boolean listening;
        volatile boolean persistenceEnabled;
        volatile long generation;
        long loadedGeneration = Long.MIN_VALUE;
        boolean writeQueued;
        long queuedGeneration;
        long queuedEpoch;
        boolean initialCaptureDone;

        Store(InputMethodService service) {
            this.service = new WeakReference<>(service);
            ClipboardManager clipboard = (ClipboardManager) service.getSystemService(Context.CLIPBOARD_SERVICE);
            this.manager = new WeakReference<>(clipboard);
            this.preferences = service.getSharedPreferences("trost_extra", Context.MODE_PRIVATE);
            if (!preferences.contains("history")) preferences.edit().putBoolean("history", true).apply();
            this.persistenceEnabled = preferences.getBoolean("history", true);
            this.filesDirectory = service.getFilesDir();
            this.history = SHARED_HISTORY;
            this.listener = new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override public void onPrimaryClipChanged() {
                    final long eventGeneration = generation;
                    final long eventEpoch = FILE_EPOCH.get();
                    MAIN.post(() -> capture(Store.this, true, eventGeneration, eventEpoch));
                }
            };
        }

        void start() {
            active = true;
            boolean captureInitial = !initialCaptureDone;
            initialCaptureDone = true;
            ClipboardManager clipboard = manager.get();
            if (!listening && clipboard != null) {
                try {
                    clipboard.addPrimaryClipChangedListener(listener);
                    listening = true;
                } catch (RuntimeException ignored) { }
            }
            final long currentGeneration;
            final long currentEpoch = FILE_EPOCH.get();
            boolean load = false;
            synchronized (stateLock) {
                currentGeneration = generation;
                if (persistenceEnabled && loadedGeneration != currentGeneration) {
                    loadedGeneration = currentGeneration;
                    load = true;
                }
            }
            if (load) submit(() -> loadHistory(this, currentGeneration, currentEpoch));
            if (captureInitial) capture(this, false, currentGeneration, currentEpoch);
        }

        void pause() {
            active = false;
            ClipboardManager clipboard = manager.get();
            if (listening && clipboard != null) {
                try { clipboard.removePrimaryClipChangedListener(listener); }
                catch (RuntimeException ignored) { }
            }
            listening = false;
            setPanel(null);
        }

        void clearImmediately(ClipboardHistory empty) {
            synchronized (stateLock) {
                generation++;
                loadedGeneration = generation;
                history = empty;
                writeQueued = false;
            }
        }

        void setPanel(Panel value) { panel = new WeakReference<>(value); }
        Panel getPanel() { return panel.get(); }

        boolean isValidPersistence(long expectedGeneration, long expectedEpoch) {
            synchronized (stateLock) {
                return generation == expectedGeneration && FILE_EPOCH.get() == expectedEpoch;
            }
        }

        void refresh(long expectedGeneration) {
            MAIN.post(() -> {
                if (!active || generation != expectedGeneration) return;
                Panel current = getPanel();
                if (current != null) current.render();
            });
        }

        void mutate(HistoryChange change) {
            final long expectedGeneration;
            synchronized (stateLock) { expectedGeneration = generation; }
            final long expectedEpoch = FILE_EPOCH.get();
            submit(() -> {
                if (!isValidPersistence(expectedGeneration, expectedEpoch)) return;
                ClipboardHistory current = history;
                if (!change.apply(current)) return;
                if (persistenceEnabled) queueSave(this, expectedGeneration, expectedEpoch);
                refreshStores();
            });
        }

        void setPersistenceEnabled(boolean enabled) {
            final long expectedGeneration;
            final long expectedEpoch;
            synchronized (stateLock) {
                if (persistenceEnabled == enabled) return;
                expectedEpoch = FILE_EPOCH.incrementAndGet();
                generation++;
                expectedGeneration = generation;
                loadedGeneration = generation;
                persistenceEnabled = enabled;
                writeQueued = false;
                preferences.edit().putBoolean("history", enabled).apply();
            }
            if (enabled) queueSave(this, expectedGeneration, expectedEpoch);
            else submit(() -> {
                if (isValidPersistence(expectedGeneration, expectedEpoch) && !persistenceEnabled) {
                    deleteFile(filesDirectory, FILE);
                    deleteFile(filesDirectory, FILE + ".tmp");
                }
            });
            refresh(expectedGeneration);
        }
    }

    private interface HistoryChange { boolean apply(ClipboardHistory history); }

    private static void refreshStores() {
        ArrayList<Store> stores = new ArrayList<>();
        synchronized (STORES_LOCK) { stores.addAll(STORES.values()); }
        for (Store store : stores) if (store.active) store.refresh(store.generation);
    }

    private static void capture(Store store, boolean listenerEvent, long generation, long epoch) {
        if (!store.active || !store.isValidPersistence(generation, epoch)) return;
        InputMethodService service = store.service.get();
        ClipboardManager clipboard = store.manager.get();
        if (service == null || clipboard == null || service.getCurrentInputConnection() == null
                || protectedField(service.getCurrentInputEditorInfo())) return;
        if (listenerEvent) suppressUntilClipEvent = false;
        else if (suppressUntilClipEvent) return;
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0 || isSensitiveClip(clip)) return;
            CharSequence text = clip.getItemAt(0).getText();
            if (text == null || text.length() == 0) return;
            submit(() -> {
                if (!store.isValidPersistence(generation, epoch)) return;
                String copied = text.toString();
                if (copied.length() == 0) return;
                store.history.addOrPromote(copied, System.currentTimeMillis());
                if (store.persistenceEnabled) queueSave(store, generation, epoch);
                refreshStores();
            });
        } catch (RuntimeException ignored) { }
    }

    private static boolean isSensitiveClip(ClipData clip) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            android.os.PersistableBundle extras = clip.getDescription().getExtras();
            return extras != null && extras.getBoolean(SENSITIVE_EXTRA, false);
        } catch (RuntimeException ignored) { return false; }
    }

    private static boolean protectedField(EditorInfo editor) {
        if (editor == null || (editor.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true;
        if (editor.extras != null && editor.extras.getBoolean(SENSITIVE_EXTRA, false)) return true;
        int typeClass = editor.inputType & android.text.InputType.TYPE_MASK_CLASS;
        int variation = editor.inputType & android.text.InputType.TYPE_MASK_VARIATION;
        if (typeClass == android.text.InputType.TYPE_CLASS_TEXT) {
            return variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }
        return typeClass == android.text.InputType.TYPE_CLASS_NUMBER
                && variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private static void queueSave(Store store, long generation, long epoch) {
        synchronized (store.stateLock) {
            if (store.generation != generation || FILE_EPOCH.get() != epoch || !store.persistenceEnabled) return;
            if (store.writeQueued && store.queuedGeneration == generation && store.queuedEpoch == epoch) return;
            store.writeQueued = true;
            store.queuedGeneration = generation;
            store.queuedEpoch = epoch;
        }
        submit(() -> saveHistory(store, generation, epoch));
    }

    private static void loadHistory(Store store, long generation, long epoch) {
        if (!store.isValidPersistence(generation, epoch) || !store.persistenceEnabled) return;
        ClipboardHistory disk;
        try { disk = readHistory(store.filesDirectory); }
        catch (Exception ignored) { disk = new ClipboardHistory(); }
        if (!store.isValidPersistence(generation, epoch) || !store.persistenceEnabled) return;
        ClipboardHistory current = store.history;
        current.merge(disk);
        refreshStores();
    }

    private static void saveHistory(Store store, long generation, long epoch) {
        synchronized (store.stateLock) {
            if (store.queuedGeneration == generation && store.queuedEpoch == epoch) store.writeQueued = false;
            if (store.generation != generation || FILE_EPOCH.get() != epoch || !store.persistenceEnabled) return;
        }
        ClipboardHistory current = store.history;
        File temporary = new File(store.filesDirectory, FILE + ".tmp");
        File target = new File(store.filesDirectory, FILE);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_SIZE) throw new IOException("Unexpected AES-GCM IV");
            try (FileOutputStream file = new FileOutputStream(temporary)) {
                file.write(iv);
                OutputStream nonClosing = new FilterOutputStream(file) {
                    @Override public void close() throws IOException { flush(); }
                };
                try (CipherOutputStream encrypted = new CipherOutputStream(nonClosing, cipher)) {
                    current.writeTo(encrypted);
                }
                file.getFD().sync();
            }
            synchronized (store.stateLock) {
                if (store.generation != generation || FILE_EPOCH.get() != epoch
                        || !store.persistenceEnabled || store.history != current) {
                    deleteFile(store.filesDirectory, FILE + ".tmp");
                    return;
                }
                Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
            }
        } catch (Exception ignored) {
            deleteFile(store.filesDirectory, FILE + ".tmp");
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) keyStore.getKey(ALIAS, null);
    }

    private static ClipboardHistory readHistory(File directory) throws Exception {
        File file = new File(directory, FILE);
        if (!file.isFile()) return new ClipboardHistory();
        try (FileInputStream encryptedFile = new FileInputStream(file)) {
            byte[] iv = new byte[IV_SIZE];
            int offset = 0;
            while (offset < iv.length) {
                int read = encryptedFile.read(iv, offset, iv.length - offset);
                if (read < 0) throw new IOException("Truncated clipboard history");
                offset += read;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            try (PushbackInputStream plain = new PushbackInputStream(new CipherInputStream(encryptedFile, cipher), 4)) {
                byte[] marker = new byte[4];
                int read = 0;
                while (read < marker.length) {
                    int count = plain.read(marker, read, marker.length - read);
                    if (count < 0) throw new IOException("Truncated clipboard history payload");
                    read += count;
                }
                plain.unread(marker);
                int header = ByteBuffer.wrap(marker).getInt();
                if (ClipboardHistory.hasHeader(header)) {
                    ClipboardHistory history = ClipboardHistory.readFrom(plain);
                    if (plain.read() != -1) throw new IOException("Trailing clipboard history data");
                    return history;
                }
                return readLegacyHistory(plain);
            }
        }
    }

    private static ClipboardHistory readLegacyHistory(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        JSONArray old = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        ClipboardHistory history = new ClipboardHistory();
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null) continue;
            String text = item.optString("text", "");
            if (text.length() == 0) continue;
            history.addOrPromote(text, item.optLong("time", 0));
            if (item.optBoolean("pin", false)) history.setPinned(text, true);
        }
        return history;
    }

    private static void deleteFile(File directory, String name) {
        File file = new File(directory, name);
        if (file.exists()) file.delete();
    }

    private static final class Panel {
        final InputMethodService service;
        final View keyboard;
        final Store store;
        final InputConnection connection;
        final EditorInfo editor;
        final ThemeConfig theme;
        final LinearLayout root;
        int page;
        boolean pinnedOnly;
        boolean confirmClear;
        String message;
        final int height;

        Panel(InputMethodService service, View keyboard, Store store) {
            this.service = service;
            this.keyboard = keyboard;
            this.store = store;
            this.connection = service.getCurrentInputConnection();
            this.editor = service.getCurrentInputEditorInfo();
            this.theme = ThemeConfig.from(service);
            this.root = new LinearLayout(service);
            this.root.setOrientation(LinearLayout.VERTICAL);
            int keyboardHeight = Math.max(keyboard.getMeasuredHeight(), keyboard.getHeight());
            this.height = Math.max(dp(264), keyboardHeight);
            this.root.setMinimumHeight(height);
            this.root.setBackground(background(theme.bg, theme.border, 18));
        }

        void open() {
            store.setPanel(this);
            render();
            root.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            service.setInputView(root);
        }

        boolean valid() {
            return connection != null && service.getCurrentInputEditorInfo() == editor
                    && service.getCurrentInputConnection() == connection && !protectedField(service.getCurrentInputEditorInfo());
        }

        void back() {
            store.setPanel(null);
            if (keyboard.getParent() instanceof ViewGroup) ((ViewGroup) keyboard.getParent()).removeView(keyboard);
            service.setInputView(keyboard);
        }

        void render() {
            root.removeAllViews();
            root.setPadding(dp(10), dp(6), dp(10), dp(8));
            int totalPages = store.history.pageCount(pinnedOnly);
            page = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));

            LinearLayout header = row(root);
            addButton(header, "‹", theme.fn, theme.accent, dp(38), this::back);
            TextView title = label("Буфер обмена", 18, theme.text, true);
            header.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));
            addButton(header, confirmClear ? "Удалить всё?" : "Очистить", theme.fn,
                    confirmClear ? 0xffff8b8b : theme.accent, ViewGroup.LayoutParams.WRAP_CONTENT,
                    () -> { confirmClear = true; message = null; render(); });

            if (confirmClear) {
                LinearLayout confirmation = row(root);
                TextView prompt = label("Удалить сохранённую историю?", 13, theme.text, false);
                confirmation.addView(prompt, new LinearLayout.LayoutParams(0, dp(40), 1));
                addButton(confirmation, "Отмена", theme.key, theme.text, ViewGroup.LayoutParams.WRAP_CONTENT,
                        () -> { confirmClear = false; render(); });
                addButton(confirmation, "Подтвердить", theme.fn, theme.accent, ViewGroup.LayoutParams.WRAP_CONTENT,
                        () -> { confirmClear = false; ClipboardPanel.clear(service); });
            }

            LinearLayout filters = row(root);
            addButton(filters, pinnedOnly ? "Все записи" : "Закреплённые", theme.fn,
                    pinnedOnly ? theme.accent : theme.muted, ViewGroup.LayoutParams.WRAP_CONTENT,
                    () -> { pinnedOnly = !pinnedOnly; page = 0; render(); });
            TextView count = label("  " + matchingCount() + " записей", 12, theme.muted, false);
            filters.addView(count, new LinearLayout.LayoutParams(0, dp(38), 1));

            ScrollView scroll = new ScrollView(service);
            scroll.setFillViewport(true);
            scroll.setVerticalScrollBarEnabled(false);
            LinearLayout list = new LinearLayout(service);
            list.setOrientation(LinearLayout.VERTICAL);
            List<ClipboardHistory.Entry> entries = store.history.page(page, pinnedOnly);
            if (entries.isEmpty()) {
                TextView empty = label(pinnedOnly ? "Нет закреплённых записей" : "Скопированный текст появится здесь", 14, theme.muted, false);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(12), dp(28), dp(12), dp(28));
                list.addView(empty, new LinearLayout.LayoutParams(-1, -1));
            } else {
                for (ClipboardHistory.Entry entry : entries) addEntry(list, entry);
            }
            scroll.addView(list);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

            LinearLayout navigation = row(root);
            addButton(navigation, "‹ Предыдущая", theme.fn, theme.text, ViewGroup.LayoutParams.WRAP_CONTENT,
                    () -> { if (page > 0) { page--; render(); } });
            TextView pageLabel = label(totalPages == 0 ? "0 / 0" : (page + 1) + " / " + totalPages, 12, theme.muted, false);
            pageLabel.setGravity(Gravity.CENTER);
            navigation.addView(pageLabel, new LinearLayout.LayoutParams(0, dp(38), 1));
            addButton(navigation, "Следующая ›", theme.fn, theme.text, ViewGroup.LayoutParams.WRAP_CONTENT,
                    () -> { if (page + 1 < totalPages) { page++; render(); } });

            LinearLayout footer = row(root);
            String saveLabel = store.persistenceEnabled ? "История на устройстве: ВКЛ" : "История на устройстве: ВЫКЛ";
            addButton(footer, saveLabel, theme.key, store.persistenceEnabled ? theme.accent : theme.muted,
                    ViewGroup.LayoutParams.WRAP_CONTENT, () -> store.setPersistenceEnabled(!store.persistenceEnabled));
            TextView status = label(message == null ? "Нажми запись, чтобы вставить" : message, 11, theme.muted, false);
            footer.addView(status, new LinearLayout.LayoutParams(0, dp(38), 1));
        }

        private int matchingCount() {
            return pinnedOnly ? store.history.pinnedCount() : store.history.size();
        }

        private void addEntry(LinearLayout list, ClipboardHistory.Entry entry) {
            LinearLayout card = new LinearLayout(service);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(7), dp(10), dp(7));
            card.setBackground(background(theme.key, theme.border, 12));
            TextView preview = label(entry.text, 15, theme.text, false);
            preview.setMaxLines(2);
            preview.setEllipsize(TextUtils.TruncateAt.END);
            preview.setContentDescription("Вставить: " + entry.text);
            preview.setOnClickListener(view -> paste(entry.text));
            card.addView(preview, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout actions = row(card);
            TextView status = label(entry.pinned ? "Закреплено" : "Текст", 11, theme.muted, false);
            actions.addView(status, new LinearLayout.LayoutParams(0, dp(32), 1));
            addButton(actions, entry.pinned ? "Открепить" : "Закрепить", theme.fn, theme.accent,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    () -> store.mutate(history -> history.setPinned(entry.text, !entry.pinned)));
            addButton(actions, "Удалить", theme.fn, theme.text, ViewGroup.LayoutParams.WRAP_CONTENT,
                    () -> store.mutate(history -> history.remove(entry.text)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(6);
            list.addView(card, params);
        }

        private void paste(String text) {
            if (!valid()) { message = "Поле изменилось — открой буфер заново"; render(); return; }
            if (connection.commitText(text, 1)) back();
        }

        private LinearLayout row(LinearLayout parent) {
            LinearLayout row = new LinearLayout(service);
            row.setGravity(Gravity.CENTER_VERTICAL);
            parent.addView(row, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
            return row;
        }

        private TextView label(String value, int size, int color, boolean bold) {
            TextView text = new TextView(service);
            text.setText(value);
            text.setTextSize(size);
            text.setTextColor(color);
            if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            text.setGravity(Gravity.CENTER_VERTICAL);
            return text;
        }

        private void addButton(LinearLayout parent, String title, int fill, int foreground, int width, Runnable action) {
            TextView button = label(title, 12, foreground, true);
            button.setGravity(Gravity.CENTER);
            button.setMinHeight(dp(36));
            button.setPadding(dp(9), dp(3), dp(9), dp(3));
            button.setBackground(background(fill, theme.border, 10));
            button.setOnClickListener(view -> action.run());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, dp(38));
            params.setMargins(dp(2), dp(2), dp(2), dp(2));
            parent.addView(button, params);
        }

        private GradientDrawable background(int fill, int stroke, int radius) {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(fill);
            drawable.setCornerRadius(dp(radius));
            drawable.setStroke(dp(1), stroke);
            return drawable;
        }

        private int dp(int value) { return (int) (value * service.getResources().getDisplayMetrics().density + 0.5f); }
    }
}
