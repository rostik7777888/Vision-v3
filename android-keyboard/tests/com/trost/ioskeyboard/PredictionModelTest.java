package com.trost.ioskeyboard;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Standalone Java checks for offline ranking and personalization. */
public final class PredictionModelTest {
    private PredictionModelTest() {
    }

    public static void main(String[] args) throws Exception {
        PredictionModel model = seededModel();

        List<String> englishPrefix = model.suggest("en", "ap", "", 3);
        check(englishPrefix.get(0).equals("ap"), "prefix keeps the user's text");
        check(englishPrefix.contains("apple"), "English prefix completion");
        List<String> cyrillicPrefix = model.suggest("ru", "прив", "", 3);
        check(cyrillicPrefix.contains("привет"), "Russian prefix completion");

        check("the".equals(model.correct("en", "teh", false)), "English typo correction");
        check("hello".equals(model.correct("en", "hlelo", false)), "transposition correction");
        check("привет".equals(model.correct("ru", "привт", false)), "Russian typo correction");
        check(model.correct("en", "hello", false) == null, "known words are unchanged");
        check(model.correct("en", "Hlelo", false) == null, "capitalized names are not rewritten");
        check(model.suggest("en", "hlelo", "", 3).contains("hello"), "transposition suggestion");
        check(model.suggest("en", "Ap", "", 3).contains("Apple"), "suggestion case is preserved");

        check(model.observeCompleted("en", "hello", "quokka"), "learn a local word");
        check(model.observeCompleted("en", "hello", "quokka"), "reinforce a local word");
        check(model.contains("en", "quokka"), "learned word is recognized");
        check(model.suggest("en", "quo", "", 3).contains("quokka"), "learned prefix completion");
        check("quokka".equals(model.suggest("en", "", "hello", 3).get(0)),
                "learned bigram ranks first");

        File file = File.createTempFile("trost-prediction-test", ".bin");
        try {
            model.setStorageFile(file);
            model.save();
            PredictionModel restored = new PredictionModel(file);
            restored.setDictionary("en", englishWords());
            restored.setDictionary("ru", russianWords());
            restored.load();
            check(restored.contains("en", "quokka"), "learned word survives serialization");
            check("quokka".equals(restored.suggest("en", "", "hello", 3).get(0)),
                    "learned context survives serialization");
        } finally {
            file.delete();
            new File(file.getPath() + ".tmp").delete();
            new File(file.getPath() + ".bak").delete();
        }

        check(!PredictionModel.isValidWord("en", "猫"), "English rejects another script");
        check(!PredictionModel.isValidWord("ru", "hello"), "Russian rejects Latin input");
        check(!PredictionModel.isValidWord("en", "word2"), "digits are not words");
        check(!PredictionModel.isValidWord("en", "word@example.com"), "addresses are not words");
        check(!PredictionModel.isValidWord("en", "word\u0001"), "control characters are rejected");
        check(!model.observeCompleted("en", "", "猫"), "invalid input is not learned");
        check(model.suggest("en", "猫", "", 3).isEmpty(), "invalid-script queries are ignored");
        check(!model.suggest("fr", "ap", "", 3).contains("apple"),
                "unsupported languages use no fabricated English words");
        check(model.suggest("fr", "ap", "", 3).get(0).equals("ap"),
                "unsupported-language input remains intact");

        if (args.length > 0) {
            testBundledDictionaries(new File(args[0]));
        }

        System.out.println("PredictionModelTest passed");
    }

    private static void testBundledDictionaries(File assets) throws Exception {
        PredictionModel model = new PredictionModel();
        model.setDictionary("en", readFrequencies(new File(assets, "en-freq.tsv")));
        model.setDictionary("ru", readFrequencies(new File(assets, "ru-freq.tsv")));
        check(model.contains("en", "hello"), "bundled English dictionary loads");
        check(model.contains("ru", "привет"), "bundled Russian dictionary loads");
        check(model.suggest("en", "hel", "", 3).contains("hello"), "bundled English prefix");
        check(model.suggest("ru", "прив", "", 3).contains("привет"), "bundled Russian prefix");
        check("hello".equals(model.correct("en", "hlelo", false)), "bundled English transposition");
    }

    private static Map<String, Integer> readFrequencies(File file) throws Exception {
        Map<String, Integer> frequencies = new LinkedHashMap<String, Integer>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), "UTF-8"))) {
            String line;
            while (frequencies.size() < 60000 && (line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 2);
                if (parts.length == 2) {
                    frequencies.put(parts[0], Integer.valueOf(parts[1]));
                }
            }
        }
        return frequencies;
    }

    private static PredictionModel seededModel() {
        PredictionModel model = new PredictionModel();
        model.setDictionary("en", englishWords());
        model.setDictionary("ru", russianWords());
        return model;
    }

    private static Map<String, Integer> englishWords() {
        Map<String, Integer> words = new LinkedHashMap<String, Integer>();
        words.put("the", 1000000);
        words.put("then", 250000);
        words.put("ten", 100000);
        words.put("tea", 90000);
        words.put("hello", 500000);
        words.put("help", 150000);
        words.put("apple", 400000);
        words.put("apply", 300000);
        words.put("apples", 100000);
        words.put("world", 250000);
        return words;
    }

    private static Map<String, Integer> russianWords() {
        Map<String, Integer> words = new LinkedHashMap<String, Integer>();
        words.put("привет", 500000);
        words.put("приветы", 5000);
        words.put("привод", 50000);
        words.put("мир", 200000);
        return words;
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
