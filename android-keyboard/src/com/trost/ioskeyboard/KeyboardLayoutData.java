package com.trost.ioskeyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Native three-row character layouts adapted from AOSP LatinIME and Keyman. */
public final class KeyboardLayoutData {
    private static final Map<String, String[]> ROWS = new LinkedHashMap<>();

    static {
        put("en", "qwertyuiop", "asdfghjkl", "zxcvbnm");
        put("ru", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбюё");
        put("uk", "йцукенгшщзхї", "фівапролджє", "ячсмитьбюґ");
        put("kk", "йцукенгшщзхъқәі", "фывапролджэғңө", "ячсмитьбюёұүһ");

        put("ar", "ضصثقفغعهخحج", "شسيبلاتنمكط", "ءؤرىةوزظدذ");
        put("bg", "явертъуиопч", "асдфгхйклшщ", "зьцжбнмю");
        put("el", "ςερτυθιοπ", "ασδφγηξκλ", "ζχψωβνμ");
        put("he", "קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ");
        put("hy", "էթփձջրչճժծքոե", "ռտըւիօպասդֆգհյ", "կլզղցվբնմխշև");
        put("ka", "ჭღთქწერტყუი", "ოპშჟასდფგჰჯ", "კლძჩზხცვბნმ");
    }

    private KeyboardLayoutData() {
    }

    private static void put(String code, String first, String second, String third) {
        ROWS.put(code, new String[]{first, second, third});
    }

    public static boolean supports(String language) {
        return ROWS.containsKey(normalize(language));
    }

    public static String[] rows(String language) {
        String[] rows = ROWS.get(normalize(language));
        return rows == null ? null : rows.clone();
    }

    public static List<String> languages() {
        return Collections.unmodifiableList(new ArrayList<>(ROWS.keySet()));
    }

    private static String normalize(String language) {
        if (language == null) return "";
        String code = language.trim().toLowerCase(Locale.ROOT);
        int separator = code.indexOf('-');
        return separator < 0 ? code : code.substring(0, separator);
    }
}
