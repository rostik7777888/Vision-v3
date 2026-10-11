package com.trost.ioskeyboard;

import android.content.Context;
import java.util.*;

public final class LanguageCatalog {
    private static volatile String active = "ru";
    private static final Map<String, String[]> EXTRA_ROWS;
    private static volatile Map<String, LanguagePackData.Entry> remote = Collections.emptyMap();
    private static final List<String> DEFAULT_LANGUAGES = Collections.unmodifiableList(Arrays.asList("ru", "en"));

    static {
        Map<String, String[]> rows = new HashMap<>();
        rows.put("de", new String[]{"qwertzuiopü", "asdfghjklöä", "yxcvbnmß"});
        rows.put("fr", new String[]{"azertyuiop", "qsdfghjklm", "wxcvbn"});
        rows.put("es", new String[]{"qwertyuiop", "asdfghjklñ", "zxcvbnm"});
        rows.put("pt", new String[]{"qwertyuiop", "asdfghjklç", "zxcvbnm"});
        rows.put("tr", new String[]{"qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç"});
        EXTRA_ROWS = Collections.unmodifiableMap(rows);
    }

    private LanguageCatalog() {
    }

    public static void installRemote(LanguagePackData pack) {
        Map<String, LanguagePackData.Entry> installed = new LinkedHashMap<>();
        if (pack != null) installed.putAll(pack.languages());
        remote = Collections.unmodifiableMap(installed);
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
        String code = normalize(language);
        LanguagePackData.Entry downloaded = remote.get(code);
        if (downloaded != null) return downloaded.rows();
        String[] rows = KeyboardLayoutData.rows(code);
        if (rows != null) return rows;
        rows = EXTRA_ROWS.get(code);
        return rows == null ? null : rows.clone();
    }

    public static boolean supported(String language) {
        String code = normalize(language);
        return remote.containsKey(code) || KeyboardLayoutData.supports(code) || EXTRA_ROWS.containsKey(code);
    }

    public static String name(String code) {
        String normalized = normalize(code);
        LanguagePackData.Entry downloaded = remote.get(normalized);
        if (downloaded != null) return downloaded.name();
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
        List<String> order = all();
        List<String> languages = new ArrayList<>();
        if (selected != null) {
            for (String code : order) {
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
        Set<String> available = new HashSet<>(KeyboardLayoutData.languages());
        available.addAll(EXTRA_ROWS.keySet());
        available.addAll(remote.keySet());
        available.remove("ru");
        available.remove("en");
        List<String> rest = new ArrayList<>(available);
        Collections.sort(rest, (a, b) -> {
            int nameOrder = name(a).compareToIgnoreCase(name(b));
            return nameOrder == 0 ? a.compareTo(b) : nameOrder;
        });
        List<String> codes = new ArrayList<>(rest.size() + 2);
        codes.add("ru");
        codes.add("en");
        codes.addAll(rest);
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
