package com.trost.ioskeyboard;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Standalone checks for native layouts and language switching. */
public final class LanguageCatalogTest {
    private static final String[] SOURCE_ROWS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};

    private LanguageCatalogTest() {
    }

    public static void main(String[] args) {
        checkCatalog();
        checkKazakh();
        checkCompleteScriptLayouts();
        checkSwitching();
        System.out.println("LanguageCatalogTest passed");
    }

    private static void checkCatalog() {
        List<String> languages = LanguageCatalog.all();
        check("ru".equals(languages.get(0)) && "en".equals(languages.get(1)), "default languages come first");
        check(languages.containsAll(Arrays.asList("ru", "en", "uk", "kk", "ar", "bg", "el", "he", "hy", "ka")),
                "core and native-script layouts are listed");
        for (String unsupported : Arrays.asList("zh", "ja", "ko", "af", "xx")) {
            check(!languages.contains(unsupported), unsupported + " is not advertised without a native layout");
        }
        check(new HashSet<>(languages).size() == languages.size(), "catalog language codes are unique");
        for (String code : languages) {
            String[] rows = LanguageCatalog.rows(code);
            check(rows != null && rows.length == 3, code + " has three native rows");
            for (String row : rows) check(row != null && !row.isEmpty(), code + " rows are nonempty");
            if (!"en".equals(code)) {
                check(!Arrays.equals(rows, new String[]{SOURCE_ROWS[0], SOURCE_ROWS[1], SOURCE_ROWS[2]}),
                        code + " does not silently use English rows");
            }
        }
        check(LanguageCatalog.name("kk").equals("Қазақша"), "Kazakh uses its native name");
        check(LanguageCatalog.detail("zh").contains("не поддерживается"), "unsupported layout is explicit");
        check(Arrays.equals(LanguageCatalog.rows("en-US"), LanguageCatalog.rows("en")), "language tags normalize");
        check(LanguageCatalog.row("zh", SOURCE_ROWS[0]).equals(LanguageCatalog.rows("ru")[0]),
                "unsupported stale languages fail safe to a non-English layout");
        String[] mutableRows = LanguageCatalog.rows("kk");
        mutableRows[0] = "changed";
        check(!"changed".equals(LanguageCatalog.rows("kk")[0]), "layout data is protected from mutation");
    }

    private static void checkKazakh() {
        String[] rows = LanguageCatalog.rows("kk");
        check(rows[0].length() + rows[1].length() + rows[2].length() == 42, "Kazakh has all 42 letters");
        Set<Character> letters = new HashSet<>();
        for (String row : rows) for (char letter : row.toCharArray()) letters.add(letter);
        check(letters.size() == 42, "Kazakh rows have no missing or duplicate letters");
        for (char letter : "әғқңөұүһі".toCharArray()) check(letters.contains(letter), "Kazakh-specific " + letter);
        check(LanguageCatalog.row("kk", SOURCE_ROWS[0]).equals(rows[0]), "Kazakh replaces the first QWERTY row");
        LanguageCatalog.use("kk");
        check(LanguageCatalog.row(SOURCE_ROWS[1]).equals(rows[1]), "active Kazakh language replaces rows");
        LanguageCatalog.use("ru");
    }

    private static void checkCompleteScriptLayouts() {
        checkComplete("ru", 33, "Russian alphabet is complete");
        checkComplete("uk", 33, "Ukrainian alphabet is complete");
        checkComplete("bg", 30, "Bulgarian alphabet is complete");
        checkComplete("he", 27, "Hebrew base and final letters are present");
        checkComplete("ka", 33, "Georgian alphabet is complete");
        checkComplete("hy", 39, "Armenian alphabet and ligature are present");
        checkComplete("ar", 32, "Arabic AOSP character set and dhal are present");
        check(flatten("ar").indexOf('ذ')>=0,"Arabic dhal must be typeable");
    }

    private static void checkSwitching() {
        check("en".equals(LanguageCatalog.next("ru", Arrays.asList("en", "ru", "zh"))),
                "switching cycles only through supported selections");
        check("kk".equals(LanguageCatalog.next("uk", Arrays.asList("kk", "uk", "zh"))),
                "switching order is stable for preference sets");
        check("ru".equals(LanguageCatalog.next("unknown", Arrays.asList("zh"))),
                "empty supported selection uses the Russian default");
        LanguageCatalog.use("zh");
        check(LanguageCatalog.row(SOURCE_ROWS[0]).equals(LanguageCatalog.rows("ru")[0]),
                "unsupported active language fails safe");
    }

    private static String flatten(String code) {
        return String.join("", LanguageCatalog.rows(code));
    }

    private static void checkComplete(String code, int length, String message) {
        String letters = flatten(code);
        check(letters.length() == length, message);
        Set<Character> unique = new HashSet<>();
        for (char letter : letters.toCharArray()) unique.add(letter);
        check(unique.size() == length, code + " layout has no duplicated letters");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
