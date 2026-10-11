package com.trost.ioskeyboard;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Standalone Java checks for offline ranking and personalization. */
public final class PredictionModelTest {
    private PredictionModelTest() {
    }

    public static void main(String[] args) throws Exception {
        PredictionModel deletionRegression=new PredictionModel();
        for(String word:new String[]{"hello","help","helicopter"})deletionRegression.observeCompleted("en","say",word);
        for(int round=0;round<20;round++){
            for(String prefix:new String[]{"hel","he","h","he","hel"}){
                List<String> suggestions=deletionRegression.suggest("en",prefix,"say",3);
                check(!suggestions.isEmpty(),"learned-prefix deletion must not crash or lose suggestions");
            }
        }
        File regressionFile=File.createTempFile("fishka-deletion-regression",".bin");
        try{
            deletionRegression.setStorageFile(regressionFile);deletionRegression.save();
            PredictionModel reopened=new PredictionModel(regressionFile);reopened.load();
            check(!reopened.suggest("en","he","say",3).isEmpty(),"restored learned-prefix map must not mutate during scoring");
        }finally{regressionFile.delete();}
        PredictionModel model = seededModel();

        List<String> englishPrefix = model.suggest("en", "ap", "", 3);
        check(englishPrefix.get(0).equals("ap"), "prefix keeps the user's text");
        check(englishPrefix.contains("apple"), "English prefix completion");
        List<String> cyrillicPrefix = model.suggest("ru", "прив", "", 3);
        check(cyrillicPrefix.contains("привет"), "Russian prefix completion");

        check("the".equals(model.correct("en", "teh", false)), "English typo correction");
        check("hello".equals(model.correct("en", "hlelo", false)), "transposition correction");
        check("привет".equals(model.correct("ru", "привт", false)), "Russian typo correction");
        check("hello".equals(model.correct("en", "hlelo", true)), "live transposition correction");
        check("привет".equals(model.correct("ru", "привт", true)), "live Russian correction");
        check(model.correct("en", "hell", true) == null, "valid prefixes are not corrected midword");
        check(model.correct("en", "hellp", true) == null, "ambiguous live corrections are left alone");
        check(model.correct("en", "teh", true) == null, "short live words are left alone");
        check(model.correct("en", "hello", false) == null, "known words are unchanged");
        check(model.correct("en", "Hlelo", false) == null, "capitalized names are not rewritten");
        check(model.suggest("en", "hellp", "", 3).indexOf("hello")
                        < model.suggest("en", "hellp", "", 3).indexOf("helly"),
                "adjacent keyboard typo ranks above a similarly frequent candidate");
        check(model.suggest("en", "hlelo", "", 3).contains("hello"), "transposition suggestion");
        check(model.suggest("en", "Ap", "", 3).contains("Apple"), "suggestion case is preserved");
        check(model.checkedCorrect("en", "hell", "meeting") == null,
                "context-aware correction preserves a valid prefix");

        PredictionModel contextModel = seededModel();
        check(contextModel.checkedCorrect("en", "hellp", "meeting") == null,
                "context is not guessed before it is learned");
        contextModel.observeCompleted("en", "meeting", "hello");
        contextModel.observeCompleted("en", "meeting", "hello");
        check(contextModel.checkedCorrect("en", "hellp", "meeting") == null,
                "two observations are insufficient for contextual rewriting");
        contextModel.observeCompleted("en", "meeting", "hello");
        check("hello".equals(contextModel.checkedCorrect("en", "hellp", "meeting")),
                "three repeated bigrams personalize a close correction");

        PredictionModel longTypoModel = new PredictionModel();
        Map<String, Integer> longWord = new LinkedHashMap<String, Integer>();
        longWord.put("goodbye", 100000);
        longTypoModel.setDictionary("en", longWord);
        check(longTypoModel.suggest("en", "gppdbye", "", 3).contains("goodbye"),
                "two-error long word remains available as a suggestion");
        check(longTypoModel.correct("en", "gppdbye", true) == null,
                "two-error words are suggested, not rewritten live");

        Map<String, List<String>> adapterDictionary = new LinkedHashMap<String, List<String>>();
        List<String> adapterWords = new ArrayList<String>();
        adapterWords.add("hello");
        for (int i = 1; i < 8000; i++) {
            adapterWords.add("invalid" + i);
        }
        adapterDictionary.put("en", adapterWords);
        Lexicon.INSTANCE.install$app_debug(adapterDictionary);
        check("hello".equals(Lexicon.INSTANCE.checkedCorrect("en", "hlelo", "")),
                "Lexicon exposes offline contextual correction to the service");

        check(model.correct("en", "quokak", true) == null, "unknown words need local evidence first");
        check(model.observeCompleted("en", "hello", "quokka"), "learn a local word");
        check(model.correct("en", "quokak", true) == null, "one use is not enough to auto-correct");
        check(model.observeCompleted("en", "hello", "quokka"), "reinforce a local word");
        check("quokka".equals(model.correct("en", "quokak", true)),
                "repeated local words adapt live correction");
        check(model.contains("en", "quokka"), "learned word is recognized");
        check(model.suggest("en", "quo", "", 3).contains("quokka"), "learned prefix completion");
        check("quokka".equals(model.suggest("en", "", "hello", 3).get(0)),
                "learned bigram ranks first");
        check(model.checkedCorrect("en", "hellp", "meeting") == null,
                "unseen context does not alter correction");
        model.observeCompleted("en", "meeting", "hello");
        model.observeCompleted("en", "meeting", "hello");
        model.observeCompleted("en", "meeting", "hello");
        check("hello".equals(model.checkedCorrect("en", "hellp", "meeting")),
                "learned context influences only a plausible typo");

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
            check("hello".equals(restored.checkedCorrect("en", "hellp", "meeting")),
                    "contextual correction evidence survives serialization");
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
        check(model.observeCompleted("fr", "bonjour", "monde"),
                "languages without a bundled corpus still learn the user's own words");
        check(model.suggest("fr","mon","bonjour",3).contains("monde"),"downloadable-language words are suggested from local evidence");
        check(model.observeCompleted("kk","сәлем","әлем"),"Kazakh personal words are learned");
        check(model.suggest("kk","ә","сәлем",3).contains("әлем"),"Kazakh learning never falls back to English");
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
        check("hello".equals(model.correct("en", "hlelo", true)), "bundled live English transposition");
        check("привет".equals(model.correct("ru", "привт", true)), "bundled live Russian typo");
        check(model.correct("en", "hell", true) == null, "bundled valid prefix stays unchanged");
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
        words.put("helly", 450000);
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
