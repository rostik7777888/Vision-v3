package com.trost.ioskeyboard;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.inputmethodservice.InputMethodService;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Android-facing adapter for the bundled offline dictionaries and private user model. */
public final class Lexicon {
    public static final Lexicon INSTANCE = new Lexicon();

    private static final int MAX_DICTIONARY_WORDS = 60000;
    private static final int MAX_CURSOR_TEXT = 256;
    private static final ExecutorService DISK = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "trost-lexicon-disk");
            thread.setDaemon(true);
            return thread;
        }
    });

    private final PredictionModel model = new PredictionModel();
    private final AtomicBoolean saveQueued = new AtomicBoolean();
    private final AtomicBoolean saveDirty = new AtomicBoolean();
    private final Map<String, List<String>> cache = new LinkedHashMap<String, List<String>>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > 128;
        }
    };
    private volatile boolean loading;
    private volatile boolean loaded;
    private volatile String activeLanguage = "ru";

    private Lexicon() {
    }

    public void load(final Context context) {
        if (context == null) {
            return;
        }
        synchronized (this) {
            if (loading || loaded) {
                return;
            }
            loading = true;
        }
        Runnable task = new Runnable() {
            @Override
            public void run() {
                Context app = context.getApplicationContext();
                if (app == null) {
                    app = context;
                }
                try {
                    for (String language : new String[]{"ru", "en"}) {
                        Map<String, Integer> words = readDictionary(app, language);
                        if (!words.isEmpty()) {
                            model.setDictionary(language, words);
                        }
                    }
                    model.setStorageFile(new File(app.getFilesDir(), "trost-prediction-model.bin"));
                    try {
                        model.load();
                    } catch (IOException ignored) {
                        // A damaged private model is ignored; bundled dictionaries remain available.
                    }
                } finally {
                    synchronized (Lexicon.this) {
                        loading = false;
                        loaded = true;
                    }
                    clearCache();
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            DISK.execute(task);
        } else {
            task.run();
        }
    }

    public synchronized void install$app_debug(Map<String, ? extends List<String>> dictionaries) {
        model.clearDictionaries();
        if (dictionaries != null) {
            for (Map.Entry<String, ? extends List<String>> entry : dictionaries.entrySet()) {
                List<String> words = entry.getValue();
                if (words == null) {
                    continue;
                }
                Map<String, Integer> frequencies = new LinkedHashMap<String, Integer>();
                for (int i = 0; i < words.size() && i < MAX_DICTIONARY_WORDS; i++) {
                    frequencies.put(words.get(i), Integer.valueOf(Math.max(1, words.size() - i)));
                }
                model.setDictionary(entry.getKey(), frequencies);
            }
        }
        loaded = true;
        clearCache();
    }

    void loadFilesForTest(String directory) throws Exception {
        Map<String, List<String>> dictionaries = new LinkedHashMap<String, List<String>>();
        for (String language : new String[]{"ru", "en"}) {
            List<String> words = new ArrayList<String>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream(new File(directory, language + "-freq.tsv")), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null && words.size() < MAX_DICTIONARY_WORDS) {
                    String[] parts = line.split("\t", 2);
                    if (parts.length == 2) {
                        words.add(parts[0]);
                    }
                }
            }
            dictionaries.put(language, words);
        }
        install$app_debug(dictionaries);
    }

    /** Retained for 1.8 binary callers; offline suggestions never forward text to observers. */
    public void setObserver(BiConsumer<String, String> observer) {
    }

    public boolean contains(String language, String word) {
        return model.contains(language, word);
    }

    public String correct(String language, String word) {
        return model.correct(language, word, false);
    }

    public String liveCorrect(String language, String word) {
        return model.correct(language, word, true);
    }

    public List<String> suggest(String language, String prefix, String previous) {
        String lang = language == null ? "" : language;
        if ("ru".equals(lang) || "en".equals(lang)) {
            activeLanguage = lang;
        }
        String typed = prefix == null ? "" : prefix;
        String prior = previous == null ? "" : previous;
        String key = model.revision() + "\u0000" + lang + "\u0000" + typed + "\u0000" + prior;
        synchronized (cache) {
            List<String> result = cache.get(key);
            if (result != null) {
                return result;
            }
        }
        List<String> result = model.suggest(lang, typed, prior, 3);
        synchronized (cache) {
            cache.put(key, result);
        }
        return result;
    }

    /** Called before the original 1.8 key handler commits a delimiter. */
    public static void observe(InputMethodService service, String key) {
        if (service == null || !isWordBoundary(key)) {
            return;
        }
        EditorInfo editor = service.getCurrentInputEditorInfo();
        if (!allowsPersonalization(editor)) {
            return;
        }
        InputConnection connection = service.getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        CharSequence before;
        try {
            before = connection.getTextBeforeCursor(MAX_CURSOR_TEXT, 0);
        } catch (RuntimeException ignored) {
            return;
        }
        if (before == null || before.length() == 0 || before.length() > MAX_CURSOR_TEXT) {
            return;
        }
        String text = before.toString();
        if (containsUnsafeContext(text)) {
            return;
        }
        String[] words = lastWords(text);
        if (words == null) {
            return;
        }
        INSTANCE.learn(service.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lang", "ru"), words[0], words[1]);
    }

    /** Public adaptation entry point for an explicitly chosen suggestion. */
    public void observeSelection(String language, String previous, String selectedWord) {
        learn(language, previous, selectedWord);
    }

    /** Privacy-checked variant suitable for the service's suggestion-selection path. */
    public static void observeSelection(InputMethodService service, String language,
                                        String previous, String selectedWord) {
        if (service == null || !service.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("learning", true)
                || !allowsPersonalization(service.getCurrentInputEditorInfo())) {
            return;
        }
        INSTANCE.observeSelection(language, previous, selectedWord);
    }

    /** Reads the preceding word before a suggestion-selection handler replaces the typed prefix. */
    public static void observeSelection(InputMethodService service, String language, String selectedWord) {
        if (service == null || !service.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("learning", true)
                || !allowsPersonalization(service.getCurrentInputEditorInfo())) {
            return;
        }
        String previous = "";
        InputConnection connection = service.getCurrentInputConnection();
        if (connection != null) {
            CharSequence before;
            try {
                before = connection.getTextBeforeCursor(MAX_CURSOR_TEXT, 0);
            } catch (RuntimeException ignored) {
                return;
            }
            if (before != null && before.length() <= MAX_CURSOR_TEXT) {
                String text = before.toString();
                if (containsUnsafeContext(text)) {
                    return;
                }
                String[] words = lastWords(text);
                if (words != null) {
                    previous = words[0];
                }
            }
        }
        INSTANCE.observeSelection(language, previous, selectedWord);
    }

    public void learn(String language, String previous, String word) {
        Runnable update = () -> {
            if (model.observeCompleted(language, previous, word)) {
                clearCache();
                queueSave();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) DISK.execute(update);
        else update.run();
    }

    private void queueSave() {
        saveDirty.set(true);
        if (!saveQueued.compareAndSet(false, true)) {
            return;
        }
        DISK.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    do {
                        saveDirty.set(false);
                        model.save();
                    } while (saveDirty.get());
                } catch (IOException ignored) {
                    // The in-memory model remains usable if private storage is temporarily unavailable.
                } finally {
                    saveQueued.set(false);
                    if (saveDirty.get()) {
                        queueSave();
                    }
                }
            }
        });
    }

    private static Map<String, Integer> readDictionary(Context context, String language) {
        LinkedHashMap<String, Integer> words = new LinkedHashMap<String, Integer>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(language + "-freq.tsv"), "UTF-8"))) {
            String line;
            while (words.size() < MAX_DICTIONARY_WORDS && (line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 2);
                if (parts.length != 2 || !PredictionModel.isValidWord(language, parts[0])) {
                    continue;
                }
                try {
                    words.put(parts[0], Integer.valueOf(Math.max(1, Integer.parseInt(parts[1]))));
                } catch (NumberFormatException ignored) {
                    // Skip malformed frequency rows.
                }
            }
        } catch (IOException ignored) {
            words.clear();
        }
        if (!words.isEmpty()) {
            return words;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(language + ".txt"), "UTF-8"))) {
            String line;
            int position = 0;
            while (position < MAX_DICTIONARY_WORDS && (line = reader.readLine()) != null) {
                String word = line.trim();
                if (!word.isEmpty() && !word.startsWith("#") && PredictionModel.isValidWord(language, word)) {
                    words.put(word, Integer.valueOf(MAX_DICTIONARY_WORDS - position));
                    position++;
                }
            }
        } catch (IOException ignored) {
            words.clear();
        }
        return words;
    }

    private static boolean isWordBoundary(String key) {
        return "space".equals(key) || "enter".equals(key) || " ".equals(key) || "\n".equals(key);
    }

    private static boolean allowsPersonalization(EditorInfo editor) {
        if (editor == null || (editor.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) {
            return false;
        }
        int type = editor.inputType;
        if ((type & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return false;
        }
        int variation = type & InputType.TYPE_MASK_VARIATION;
        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_URI) {
            return false;
        }
        Bundle extras = editor.extras;
        if (extras != null) {
            try {
                for (String key : extras.keySet()) {
                    String normalized = key.toLowerCase(Locale.ROOT);
                    if (normalized.contains("sensitive") || normalized.contains("no_personalized_learning")) {
                        Object value = extras.get(key);
                        if (Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value))) {
                            return false;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsUnsafeContext(String text) {
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isISOControl(character) || Character.isDigit(character)
                    || character == '@' || character == '/' || character == '\\') {
                return true;
            }
        }
        return false;
    }

    private static String[] lastWords(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (end == 0) {
            return null;
        }
        int currentStart = end;
        while (currentStart > 0 && !Character.isWhitespace(text.charAt(currentStart - 1))) {
            currentStart--;
        }
        String current = trimBoundaryPunctuation(text.substring(currentStart, end));
        if (!PredictionModel.isValidWord("ru", current) && !PredictionModel.isValidWord("en", current)) {
            return null;
        }

        int priorEnd = currentStart;
        while (priorEnd > 0 && Character.isWhitespace(text.charAt(priorEnd - 1))) {
            priorEnd--;
        }
        int priorStart = priorEnd;
        while (priorStart > 0 && !Character.isWhitespace(text.charAt(priorStart - 1))) {
            priorStart--;
        }
        String previous = trimBoundaryPunctuation(text.substring(priorStart, priorEnd));
        if (!PredictionModel.isValidWord("ru", previous) && !PredictionModel.isValidWord("en", previous)) {
            previous = "";
        }
        return new String[]{previous, current};
    }

    private static String trimBoundaryPunctuation(String word) {
        int start = 0;
        int end = word.length();
        while (start < end && isBoundaryPunctuation(word.codePointAt(start))) {
            start += Character.charCount(word.codePointAt(start));
        }
        while (end > start) {
            int codePoint = word.codePointBefore(end);
            if (!isBoundaryPunctuation(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return word.substring(start, end);
    }

    private static boolean isBoundaryPunctuation(int codePoint) {
        switch (codePoint) {
            case '.':
            case ',':
            case '!':
            case '?':
            case ';':
            case ':':
            case '\u2026':
            case '"':
            case '\'':
            case '\u201c':
            case '\u201d':
            case '\u2018':
            case '\u2019':
            case '(':
            case ')':
            case '[':
            case ']':
            case '{':
            case '}':
                return true;
            default:
                return false;
        }
    }

    private void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }
}
