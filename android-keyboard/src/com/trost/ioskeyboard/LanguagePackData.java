package com.trost.ioskeyboard;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict, immutable model for the small downloadable native-layout catalog. */
public final class LanguagePackData {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_LANGUAGES = 128;
    public static final int MAX_KEYS_PER_ROW = 32;

    private final Map<String, Entry> languages;

    private LanguagePackData(Map<String, Entry> languages) {
        this.languages = Collections.unmodifiableMap(new LinkedHashMap<>(languages));
    }

    public Map<String, Entry> languages() {
        return languages;
    }

    public static LanguagePackData parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Language pack size is invalid");
        }
        final String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Language pack is not UTF-8", error);
        }
        return parseJson(json);
    }

    public static LanguagePackData parse(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Language pack size is invalid");
        }
        return parseJson(json);
    }

    private static LanguagePackData parseJson(String json) {
        Object rootValue = new JsonParser(json).parse();
        Map<String, Object> root = object(rootValue, "root");
        exactKeys(root, "version", "languages");
        if (!(root.get("version") instanceof Long) || ((Long) root.get("version")) != 1L) {
            throw new IllegalArgumentException("Unsupported language pack version");
        }
        List<?> values = array(root.get("languages"), "languages");
        if (values.isEmpty() || values.size() > MAX_LANGUAGES) {
            throw new IllegalArgumentException("Language count is invalid");
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (Object value : values) {
            Map<String, Object> item = object(value, "language");
            exactKeys(item, "code", "name", "rows");
            String code = string(item.get("code"), "code");
            if (!validCode(code)) throw new IllegalArgumentException("Language code is invalid");
            String name = string(item.get("name"), "name");
            validateName(name);
            List<?> rowValues = array(item.get("rows"), "rows");
            if (rowValues.size() != 3) throw new IllegalArgumentException("A layout must have three rows");
            String[] rows = new String[3];
            Set<Integer> keys = new HashSet<>();
            for (int i = 0; i < rows.length; i++) {
                rows[i] = string(rowValues.get(i), "row");
                validateRow(rows[i], keys);
            }
            if (entries.put(code, new Entry(code, name, rows)) != null) {
                throw new IllegalArgumentException("Duplicate language code");
            }
        }
        return new LanguagePackData(entries);
    }

    private static void validateName(String name) {
        if (name.trim().isEmpty() || name.length() > 64) {
            throw new IllegalArgumentException("Language name is invalid");
        }
        validateText(name);
    }

    private static void validateRow(String row, Set<Integer> keys) {
        int count = row.codePointCount(0, row.length());
        if (count == 0 || count > MAX_KEYS_PER_ROW) throw new IllegalArgumentException("Row length is invalid");
        validateText(row);
        for (int offset = 0; offset < row.length();) {
            int codePoint = row.codePointAt(offset);
            if (!Character.isLetter(codePoint) || !keys.add(codePoint)) {
                throw new IllegalArgumentException("Rows must contain unique letters");
            }
            offset += Character.charCount(codePoint);
        }
    }

    private static void validateText(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint) || type == Character.FORMAT || isBidiControl(codePoint)) {
                throw new IllegalArgumentException("Control characters are not allowed");
            }
            offset += Character.charCount(codePoint);
        }
    }

    private static boolean isBidiControl(int cp) {
        return cp == 0x061c || cp == 0x200e || cp == 0x200f
                || (cp >= 0x202a && cp <= 0x202e)
                || (cp >= 0x2066 && cp <= 0x2069)
                || (cp >= 0x206a && cp <= 0x206f) || cp == 0xfeff;
    }

    private static boolean validCode(String code) {
        if (code.length() < 2 || code.length() > 3) return false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        return true;
    }

    private static void exactKeys(Map<String, Object> value, String... expected) {
        if (value.size() != expected.length) throw new IllegalArgumentException("Unexpected object fields");
        for (String key : expected) if (!value.containsKey(key)) throw new IllegalArgumentException("Missing field: " + key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map)) throw new IllegalArgumentException(field + " must be an object");
        return (Map<String, Object>) value;
    }

    private static List<?> array(Object value, String field) {
        if (!(value instanceof List)) throw new IllegalArgumentException(field + " must be an array");
        return (List<?>) value;
    }

    private static String string(Object value, String field) {
        if (!(value instanceof String)) throw new IllegalArgumentException(field + " must be a string");
        return (String) value;
    }

    public static final class Entry {
        private final String code;
        private final String name;
        private final String[] rows;

        private Entry(String code, String name, String[] rows) {
            this.code = code;
            this.name = name;
            this.rows = rows.clone();
        }

        public String code() { return code; }
        public String name() { return name; }
        public String[] rows() { return rows.clone(); }
    }

    private static final class JsonParser {
        private final String input;
        private int at;
        private int tokens;

        JsonParser(String input) {
            this.input = input;
        }

        Object parse() {
            Object value = value(0);
            whitespace();
            if (at != input.length()) fail("Trailing data");
            return value;
        }

        private Object value(int depth) {
            whitespace();
            if (depth > 8 || ++tokens > 2048 || at >= input.length()) fail("Invalid JSON structure");
            char ch = input.charAt(at);
            if (ch == '{') return object(depth + 1);
            if (ch == '[') return array(depth + 1);
            if (ch == '"') return string();
            if (ch == 't') return keyword("true", Boolean.TRUE);
            if (ch == 'f') return keyword("false", Boolean.FALSE);
            if (ch == 'n') return keyword("null", null);
            if (ch == '-' || (ch >= '0' && ch <= '9')) return number();
            fail("Unexpected JSON value");
            return null;
        }

        private Map<String, Object> object(int depth) {
            at++;
            whitespace();
            Map<String, Object> result = new LinkedHashMap<>();
            if (take('}')) return result;
            while (true) {
                whitespace();
                if (at >= input.length() || input.charAt(at) != '"') fail("Expected object key");
                String key = string();
                whitespace();
                expect(':');
                Object child = value(depth);
                if (result.containsKey(key)) fail("Duplicate object key");
                result.put(key, child);
                whitespace();
                if (take('}')) return result;
                expect(',');
            }
        }

        private List<Object> array(int depth) {
            at++;
            whitespace();
            List<Object> result = new ArrayList<>();
            if (take(']')) return result;
            while (true) {
                if (result.size() >= MAX_LANGUAGES + 1) fail("Array is too large");
                result.add(value(depth));
                whitespace();
                if (take(']')) return result;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (at < input.length()) {
                char ch = input.charAt(at++);
                if (ch == '"') {
                    ensurePairs(result);
                    return result.toString();
                }
                if (ch == '\\') {
                    if (at >= input.length()) fail("Incomplete escape");
                    char escaped = input.charAt(at++);
                    switch (escaped) {
                        case '"': case '\\': case '/': result.append(escaped); break;
                        case 'b': result.append('\b'); break;
                        case 'f': result.append('\f'); break;
                        case 'n': result.append('\n'); break;
                        case 'r': result.append('\r'); break;
                        case 't': result.append('\t'); break;
                        case 'u':
                            if (at + 4 > input.length()) fail("Incomplete unicode escape");
                            int value = 0;
                            for (int i = 0; i < 4; i++) {
                                int digit = Character.digit(input.charAt(at++), 16);
                                if (digit < 0) fail("Invalid unicode escape");
                                value = (value << 4) | digit;
                            }
                            result.append((char) value);
                            break;
                        default: fail("Invalid escape");
                    }
                } else {
                    if (ch < 0x20) fail("Unescaped control character");
                    result.append(ch);
                }
                if (result.length() > 256) fail("String is too long");
            }
            fail("Unterminated string");
            return "";
        }

        private void ensurePairs(CharSequence value) {
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (Character.isHighSurrogate(ch)) {
                    if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) fail("Invalid surrogate pair");
                } else if (Character.isLowSurrogate(ch)) fail("Invalid surrogate pair");
            }
        }

        private Long number() {
            int start = at;
            if (take('-') && at >= input.length()) fail("Invalid number");
            if (take('0')) {
                if (at < input.length() && Character.isDigit(input.charAt(at))) fail("Invalid number");
            } else {
                if (at >= input.length() || input.charAt(at) < '1' || input.charAt(at) > '9') fail("Invalid number");
                while (at < input.length() && input.charAt(at) >= '0' && input.charAt(at) <= '9') at++;
            }
            try {
                return Long.parseLong(input.substring(start, at));
            } catch (NumberFormatException error) {
                fail("Number is out of range");
                return 0L;
            }
        }

        private Object keyword(String word, Object value) {
            if (!input.regionMatches(at, word, 0, word.length())) fail("Invalid JSON token");
            at += word.length();
            return value;
        }

        private void whitespace() {
            while (at < input.length()) {
                char ch = input.charAt(at);
                if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') at++;
                else break;
            }
        }

        private boolean take(char expected) {
            if (at < input.length() && input.charAt(at) == expected) { at++; return true; }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) fail("Expected '" + expected + "'");
        }

        private void fail(String message) {
            throw new IllegalArgumentException(message + " at " + at);
        }
    }
}
