package com.trost.ioskeyboard;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

public final class LanguagePackDataTest {
    private static final String VALID = "{\"version\":1,\"languages\":[{\"code\":\"xx\",\"name\":\"Example\",\"rows\":[\"abcdef\",\"ghijkl\",\"mnopqr\"]}]}";

    private LanguagePackDataTest() {
    }

    public static void main(String[] args) throws Exception {
        LanguagePackData pack = LanguagePackData.parse(VALID);
        LanguagePackData.Entry entry = pack.languages().get("xx");
        check(entry != null && entry.name().equals("Example"), "pack parses a valid native layout");
        String[] rows = entry.rows();
        rows[0] = "changed";
        check(entry.rows()[0].equals("abcdef"), "layout rows are immutable to callers");
        rejects(VALID.replace("\"version\":1", "\"version\":2"), "unsupported version");
        rejects(VALID.replace("\"rows\":[", "\"extra\":true,\"rows\":["), "unknown fields");
        rejects(VALID.substring(0, VALID.length() - 2)
                + ",{\"code\":\"xx\",\"name\":\"Other\",\"rows\":[\"stuvwx\",\"yzabcd\",\"efghij\"]}]}",
                "duplicate language code");
        rejects(VALID.replace("\"xx\",", "\"рu\","), "non-Latin language code");
        rejects(VALID.replace("Example", "Bad\\nName"), "control character in name");
        rejects(VALID.replace("Example", "Bad\\u202eName"), "bidi control in name");
        rejects(VALID.replace("\"mnopqr\"", "\"mnopqa\""), "duplicate row character");
        rejects(VALID.replace("\"mnopqr\"", "\"mnopqr\\u202e\""), "bidi control in layout");
        rejects(VALID.replace("\"mnopqr\"", "\"mnopqr\" ,\"extra\""), "wrong row count");
        rejects("{\"version\":1,\"version\":1,\"languages\":[]}", "duplicate JSON keys");
        rejectsBytes(new byte[]{'{', '"', 'v', 'e', 'r', 's', 'i', 'o', 'n', '"', ':', '1', ',', '"', 'l', 'a', 'n', 'g', 'u', 'a', 'g', 'e', 's', '"', ':', '[', ']', '}', (byte) 0xff}, "invalid UTF-8");
        String longRow = "a".repeat(33);
        rejects(VALID.replace("abcdef", longRow), "row exceeds key limit");
        StringBuilder many = new StringBuilder("{\"version\":1,\"languages\":[");
        for (int i = 0; i <= LanguagePackData.MAX_LANGUAGES; i++) {
            if (i > 0) many.append(',');
            String code = String.format(java.util.Locale.ROOT, "x%02d", i);
            many.append("{\"code\":\"").append(code).append("\",\"name\":\"N\",\"rows\":[\"abc\",\"def\",\"ghi\"]}");
        }
        many.append("]}");
        rejects(many.toString(), "language count exceeds limit");
        rejects(" ".repeat(LanguagePackData.MAX_BYTES + 1), "pack exceeds byte limit");
        LanguagePackData bundled = LanguagePackData.parse(Files.readAllBytes(
                new File("android-keyboard/assets/language-packs.json").toPath()));
        check(bundled.languages().size() == 15, "bundled offline catalog contains the verified layouts");
        check(Arrays.asList(entry.rows()).size() == 3, "all three layout rows are preserved");
        System.out.println("LanguagePackDataTest passed");
    }

    private static void rejects(String json, String message) {
        try {
            LanguagePackData.parse(json);
            throw new AssertionError("Accepted invalid pack: " + message);
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }

    private static void rejectsBytes(byte[] bytes, String message) {
        try {
            LanguagePackData.parse(bytes);
            throw new AssertionError("Accepted invalid pack: " + message);
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
