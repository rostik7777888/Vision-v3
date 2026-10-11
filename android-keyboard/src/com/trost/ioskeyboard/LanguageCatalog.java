package com.trost.ioskeyboard;

import android.content.Context;
import java.util.*;

public final class LanguageCatalog {
    private static String active = "ru";
    private static final Map<String, String[]> EXTRA_ROWS = new HashMap<>();
    private static final List<String> CYCLE_ORDER = Collections.unmodifiableList(Arrays.asList(
            "ru", "en", "uk", "kk", "de", "es", "fr", "pt", "tr", "ar", "bg", "el", "he", "hy", "ka"));
    private static final List<String> DEFAULT_LANGUAGES = Collections.unmodifiableList(Arrays.asList("ru", "en"));

    static {
        EXTRA_ROWS.put("de", new String[]{"qwertzuiopü", "asdfghjklöä", "yxcvbnmß"});
        EXTRA_ROWS.put("fr", new String[]{"azertyuiop", "qsdfghjklm", "wxcvbn"});
        EXTRA_ROWS.put("es", new String[]{"qwertyuiop", "asdfghjklñ", "zxcvbnm"});
        EXTRA_ROWS.put("pt", new String[]{"qwertyuiop", "asdfghjklç", "zxcvbnm"});
        EXTRA_ROWS.put("tr", new String[]{"qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç"});
    }

    private LanguageCatalog() {
    }

    public static void use(String language) {
        active = supported(language) ? normalize(language) : "ru";
    }

    public static String row(String original) {
        return row(active, original);
    }

    public static String row(String language, String original) {
        if (original == null) return "";
        int index = sourceRowIndex(original);
        if (index < 0) return original;
        String[] rows = rows(language);
        if (rows == null) rows = rows("ru");
        return rows[index];
    }

    public static String[] rows(String language) {
        String[] rows = KeyboardLayoutData.rows(language);
        if (rows != null) return rows;
        rows = EXTRA_ROWS.get(normalize(language));
        return rows == null ? null : rows.clone();
    }

    public static boolean supported(String language) {
        String code = normalize(language);
        return KeyboardLayoutData.supports(code) || EXTRA_ROWS.containsKey(code);
    }

    public static String name(String code) {
        String normalized = normalize(code);
        switch (normalized) {
            case "ar": return "العربية";
            case "bg": return "Български";
            case "de": return "Deutsch";
            case "el": return "Ελληνικά";
            case "en": return "English";
            case "es": return "Español";
            case "fr": return "Français";
            case "he": return "עברית";
            case "hy": return "Հայերեն";
            case "ka": return "ქართული";
            case "kk": return "Қазақша";
            case "pt": return "Português";
            case "ru": return "Русский";
            case "tr": return "Türkçe";
            case "uk": return "Українська";
            default: return code == null ? "" : code.toUpperCase(Locale.ROOT);
        }
    }

    public static String detail(String code) {
        if (!supported(code)) return "Нативная раскладка пока не поддерживается";
        if ("kk".equals(normalize(code))) return "Кириллица · все 42 буквы";
        if ("hy".equals(normalize(code))) return "Армянский алфавит";
        if ("ka".equals(normalize(code))) return "Грузинский алфавит";
        if ("ar".equals(normalize(code))) return "Арабская раскладка";
        if ("he".equals(normalize(code))) return "Еврейская раскладка";
        return "Нативная раскладка клавиатуры";
    }

    public static String next(Context context, String current) {
        Set<String> selected = ThemeConfig.prefs(context).getStringSet(
                "languages", new HashSet<>(DEFAULT_LANGUAGES));
        return next(current, selected);
    }

    static String next(String current, Collection<String> selected) {
        List<String> languages = new ArrayList<>();
        if (selected != null) {
            for (String code : CYCLE_ORDER) {
                for (String chosen : selected) {
                    if (code.equals(normalize(chosen))) {
                        languages.add(code);
                        break;
                    }
                }
            }
        }
        if (languages.isEmpty()) languages.addAll(DEFAULT_LANGUAGES);
        int currentIndex = languages.indexOf(normalize(current));
        return languages.get((currentIndex + 1) % languages.size());
    }

    public static List<String> all() {
        List<String> codes = new ArrayList<>(CYCLE_ORDER.subList(DEFAULT_LANGUAGES.size(), CYCLE_ORDER.size()));
        Collections.sort(codes, (a, b) -> {
            int nameOrder = name(a).compareToIgnoreCase(name(b));
            return nameOrder == 0 ? a.compareTo(b) : nameOrder;
        });
        codes.add(0, "en");
        codes.add(0, "ru");
        return Collections.unmodifiableList(codes);
    }

    private static int sourceRowIndex(String original) {
        if ("qwertyuiop".equals(original)) return 0;
        if ("asdfghjkl".equals(original)) return 1;
        if ("zxcvbnm".equals(original)) return 2;
        return -1;
    }

    private static String normalize(String language) {
        if (language == null) return "";
        String code = language.trim().toLowerCase(Locale.ROOT);
        int separator = code.indexOf('-');
        return separator < 0 ? code : code.substring(0, separator);
    }
}
