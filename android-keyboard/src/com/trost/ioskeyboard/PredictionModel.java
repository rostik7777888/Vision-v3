package com.trost.ioskeyboard;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Offline word ranking and bounded, on-device personalization. */
public final class PredictionModel {
    private static final int FILE_MAGIC = 0x54524f53;
    private static final int FILE_VERSION = 1;
    private static final int MAX_DICTIONARY_WORDS = 60000;
    private static final int MAX_LEARNED_WORDS = 1200;
    private static final int MAX_LANGUAGES = 8;
    private static final int MAX_BIGRAMS = 4096;
    private static final int MAX_CONTEXT_CANDIDATES = 64;
    private static final int MAX_WORD_LENGTH = 32;
    private static final int PREFIX_DEPTH = 3;
    private static final int PREFIX_BUCKET_LIMIT = 256;
    private static final int EDIT_WORD_LIMIT = 6000;
    private static final int EDIT_BUCKET_LIMIT = 32;
    private static final int EDIT_CANDIDATE_LIMIT = 256;

    private final Map<String, DictionaryIndex> dictionaries = new HashMap<String, DictionaryIndex>();
    private final Map<String, LinkedHashMap<String, Integer>> learned =
            new HashMap<String, LinkedHashMap<String, Integer>>();
    private final Map<String, LinkedHashMap<String, Integer>> nextWords =
            new HashMap<String, LinkedHashMap<String, Integer>>();
    private File storageFile;
    private long revision;
    private long dictionaryGeneration;
    private int totalBigrams;

    public PredictionModel() {
    }

    public PredictionModel(File storageFile) {
        this.storageFile = storageFile;
    }

    public synchronized void setStorageFile(File storageFile) {
        this.storageFile = storageFile;
    }

    public synchronized long revision() {
        return revision;
    }

    public void setDictionary(String language, Map<String, Integer> words) {
        String lang = normalizeLanguage(language);
        if (lang == null || words == null) {
            return;
        }
        final long generation;
        synchronized (this) {
            generation = dictionaryGeneration;
        }

        List<DictionaryWord> entries = new ArrayList<DictionaryWord>();
        for (Map.Entry<String, Integer> entry : words.entrySet()) {
            String word = normalizeWord(lang, entry.getKey());
            if (word == null) {
                continue;
            }
            Integer frequency = entry.getValue();
            entries.add(new DictionaryWord(word, frequency == null ? 1 : Math.max(1, frequency)));
        }
        Collections.sort(entries, new Comparator<DictionaryWord>() {
            @Override
            public int compare(DictionaryWord left, DictionaryWord right) {
                int frequencyOrder = Integer.compare(right.frequency, left.frequency);
                return frequencyOrder != 0 ? frequencyOrder : left.word.compareTo(right.word);
            }
        });

        DictionaryIndex index = new DictionaryIndex();
        Set<String> seen = new HashSet<String>();
        int editIndexed = 0;
        for (DictionaryWord entry : entries) {
            if (index.frequency.size() >= MAX_DICTIONARY_WORDS || !seen.add(entry.word)) {
                continue;
            }
            index.frequency.put(entry.word, entry.frequency);
            index.popular.add(entry.word);
            addLimited(index.byLength, codePointLength(entry.word), entry.word, 128);
            addLimited(index.byFirstAndLength, firstLengthKey(entry.word), entry.word, 128);
            for (int depth = 1; depth <= Math.min(PREFIX_DEPTH, codePointLength(entry.word)); depth++) {
                addLimited(index.byPrefix, prefix(entry.word, depth), entry.word, PREFIX_BUCKET_LIMIT);
            }
            if (editIndexed++ < EDIT_WORD_LIMIT) {
                for (String deletion : deletionForms(entry.word)) {
                    addLimited(index.byDeletion, deletion, entry.word, EDIT_BUCKET_LIMIT);
                }
            }
        }
        index.alphabeticalWords.addAll(index.frequency.keySet());
        Collections.sort(index.alphabeticalWords);
        synchronized (this) {
            if (generation == dictionaryGeneration) {
                dictionaries.put(lang, index);
                revision++;
            }
        }
    }

    public synchronized void clearDictionaries() {
        dictionaries.clear();
        dictionaryGeneration++;
        revision++;
    }

    public synchronized boolean contains(String language, String word) {
        String lang = normalizeLanguage(language);
        String normalized = normalizeWord(lang, word);
        if (lang == null || normalized == null) {
            return false;
        }
        DictionaryIndex index = dictionaries.get(lang);
        if (index != null && index.frequency.containsKey(normalized)) {
            return true;
        }
        Map<String, Integer> words = learned.get(lang);
        return words != null && words.containsKey(normalized);
    }

    public synchronized List<String> suggest(String language, String prefix, String previous, int limit) {
        String lang = normalizeLanguage(language);
        if (lang == null) {
            return Collections.emptyList();
        }
        int maximum = Math.max(1, Math.min(5, limit));
        String typed = prefix == null ? "" : prefix;
        String normalizedPrefix = typed.isEmpty() ? "" : normalizeWord(lang, typed);
        if (!typed.isEmpty() && normalizedPrefix == null) {
            return Collections.emptyList();
        }

        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        if (normalizedPrefix.isEmpty()) {
            String context = normalizeWord(lang, previous);
            for (String candidate : nextWords(lang, context, maximum)) {
                addResult(result, candidate, candidate, maximum);
            }
            for (String candidate : popularWords(lang, maximum)) {
                addResult(result, candidate, candidate, maximum);
            }
            return immutableValues(result);
        }

        addResult(result, normalizedPrefix, typed, maximum);
        Map<String, Double> completions = new HashMap<String, Double>();
        DictionaryIndex index = dictionaries.get(lang);
        if (index != null) {
            for (String candidate : index.prefixCandidates(normalizedPrefix)) {
                if (candidate.startsWith(normalizedPrefix)) {
                    completions.put(candidate, score(lang, candidate));
                }
            }
        }
        Map<String, Integer> personalWords = learned.get(lang);
        if (personalWords != null) {
            for (String candidate : personalWords.keySet()) {
                if (candidate.startsWith(normalizedPrefix)) {
                    completions.put(candidate, score(lang, candidate));
                }
            }
        }
        for (String candidate : ranked(completions)) {
            addResult(result, candidate, matchCase(candidate, typed), maximum);
        }

        if (result.size() < maximum && !contains(lang, normalizedPrefix)) {
            int maxDistance = codePointLength(normalizedPrefix) >= 7 ? 2 : 1;
            Map<String, Double> similar = new HashMap<String, Double>();
            for (String candidate : editCandidates(lang, normalizedPrefix)) {
                int distance = editDistance(normalizedPrefix, candidate, maxDistance);
                if (distance > 0 && distance <= maxDistance) {
                    similar.put(candidate, score(lang, candidate) - (distance * 3.0d));
                }
            }
            for (String candidate : ranked(similar)) {
                addResult(result, candidate, matchCase(candidate, typed), maximum);
                if (result.size() >= maximum) {
                    break;
                }
            }
        }
        return immutableValues(result);
    }

    public synchronized String correct(String language, String word, boolean live) {
        String lang = normalizeLanguage(language);
        if (lang == null || (!"ru".equals(lang) && !"en".equals(lang))
                || word == null || !word.equals(word.toLowerCase(Locale.ROOT))) {
            return null;
        }
        String normalized = normalizeWord(lang, word);
        if (live) {
            return correctLive(lang, normalized, word);
        }
        if (normalized == null || codePointLength(normalized) < 3
                || contains(lang, normalized)) {
            return null;
        }
        DictionaryIndex index = dictionaries.get(lang);
        if (index == null) {
            return null;
        }

        List<RankedWord> candidates = new ArrayList<RankedWord>();
        LinkedHashSet<String> editCandidates = new LinkedHashSet<String>();
        index.addEditCandidates(normalized, editCandidates, EDIT_CANDIDATE_LIMIT);
        for (String candidate : editCandidates) {
            int distance = editDistance(normalized, candidate, 1);
            if (distance != 1) {
                continue;
            }
            int frequency = index.frequency.get(candidate);
            int minimumFrequency = 1000;
            if (frequency < minimumFrequency) {
                continue;
            }
            candidates.add(new RankedWord(candidate, score(lang, candidate), distance));
        }
        Collections.sort(candidates, RankedWord.BEST_FIRST);
        if (candidates.isEmpty()) {
            return null;
        }
        RankedWord best = candidates.get(0);
        if (candidates.size() > 1) {
            double margin = 1.1d;
            if (best.score - candidates.get(1).score < margin) {
                return null;
            }
        }
        return best.word;
    }

    private String correctLive(String language, String normalized, String typed) {
        if (normalized == null) {
            return null;
        }
        boolean knownWord = contains(language, normalized);
        DictionaryIndex index = dictionaries.get(language);
        boolean knownPrefix = index != null && index.hasWordStartingWith(normalized);
        Map<String, Integer> personal = learned.get(language);
        if (personal != null) {
            for (String candidate : personal.keySet()) {
                if (candidate.startsWith(normalized)) {
                    knownPrefix = true;
                    break;
                }
            }
        }

        Map<String, Integer> frequencies = new HashMap<String, Integer>();
        for (String candidate : editCandidates(language, normalized)) {
            int frequency = 0;
            if (index != null) {
                Integer dictionaryFrequency = index.frequency.get(candidate);
                if (dictionaryFrequency != null) {
                    frequency = dictionaryFrequency.intValue();
                }
            }
            Integer personalCount = personal == null ? null : personal.get(candidate);
            if (personalCount != null) {
                long learnedFrequency = (long) personalCount.intValue() * 4000L;
                frequency = (int) Math.min(Integer.MAX_VALUE, Math.max(frequency, learnedFrequency));
            }
            if (frequency > 0) {
                frequencies.put(candidate, Integer.valueOf(frequency));
            }
        }
        if (personal != null) {
            for (Map.Entry<String, Integer> entry : personal.entrySet()) {
                String candidate = entry.getKey();
                if (LiveCorrectionPolicy.oneEditApart(normalized, candidate)) {
                    long learnedFrequency = (long) entry.getValue().intValue() * 4000L;
                    int frequency = (int) Math.min(Integer.MAX_VALUE, learnedFrequency);
                    Integer existing = frequencies.get(candidate);
                    if (existing == null || frequency > existing.intValue()) {
                        frequencies.put(candidate, Integer.valueOf(frequency));
                    }
                }
            }
        }
        return LiveCorrectionPolicy.chooseCorrection(language, typed, knownWord, knownPrefix, frequencies);
    }

    public synchronized boolean observeCompleted(String language, String previous, String word) {
        String lang = normalizeLanguage(language);
        if (!"en".equals(lang) && !"ru".equals(lang)) {
            return false;
        }
        String current = normalizeWord(lang, word);
        if (lang == null || current == null) {
            return false;
        }
        LinkedHashMap<String, Integer> words = learned.get(lang);
        if (words == null) {
            if (learned.size() >= MAX_LANGUAGES) {
                return false;
            }
            words = new LinkedHashMap<String, Integer>(32, 0.75f, true);
            learned.put(lang, words);
        }
        Integer oldCount = words.get(current);
        if (oldCount == null && words.size() >= MAX_LEARNED_WORDS) {
            evictLeastUseful(words);
        }
        words.put(current, Integer.valueOf(Math.min(1000, oldCount == null ? 1 : oldCount + 1)));

        String prior = normalizeWord(lang, previous);
        if (prior != null && !prior.equals(current)) {
            LinkedHashMap<String, Integer> following = nextWords.get(pairKey(lang, prior));
            if (following == null) {
                following = new LinkedHashMap<String, Integer>(4, 0.75f, true);
                nextWords.put(pairKey(lang, prior), following);
            }
            Integer oldPairCount = following.get(current);
            if (oldPairCount == null) {
                if (totalBigrams >= MAX_BIGRAMS) {
                    evictLeastUsefulPair();
                }
                if (following.size() >= MAX_CONTEXT_CANDIDATES) {
                    evictLeastUseful(following);
                    totalBigrams--;
                }
                following.put(current, Integer.valueOf(1));
                totalBigrams++;
            } else {
                following.put(current, Integer.valueOf(Math.min(1000, oldPairCount + 1)));
            }
        }
        revision++;
        return true;
    }

    public synchronized List<String> nextWords(String language, String previous, int limit) {
        String lang = normalizeLanguage(language);
        String prior = normalizeWord(lang, previous);
        if (lang == null || prior == null) {
            return Collections.emptyList();
        }
        Map<String, Integer> candidates = nextWords.get(pairKey(lang, prior));
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> words = new ArrayList<String>(candidates.keySet());
        Collections.sort(words, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                int countOrder = Integer.compare(candidates.get(right), candidates.get(left));
                if (countOrder != 0) {
                    return countOrder;
                }
                int scoreOrder = Double.compare(score(language, right), score(language, left));
                return scoreOrder != 0 ? scoreOrder : left.compareTo(right);
            }
        });
        return immutablePrefix(words, Math.max(1, Math.min(5, limit)));
    }

    public void load() throws IOException {
        File file;
        synchronized (this) {
            file = storageFile;
        }
        if (file == null || !file.isFile() || file.length() <= 0 || file.length() > 2 * 1024 * 1024L) {
            return;
        }
        Map<String, LinkedHashMap<String, Integer>> loadedWords =
                new HashMap<String, LinkedHashMap<String, Integer>>();
        Map<String, LinkedHashMap<String, Integer>> loadedPairs =
                new HashMap<String, LinkedHashMap<String, Integer>>();
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            if (input.readInt() != FILE_MAGIC || input.readInt() != FILE_VERSION) {
                return;
            }
            int wordCount = input.readInt();
            if (wordCount < 0 || wordCount > MAX_LEARNED_WORDS * MAX_LANGUAGES) {
                return;
            }
            for (int i = 0; i < wordCount; i++) {
                String lang = normalizeLanguage(input.readUTF());
                String word = normalizeWord(lang, input.readUTF());
                int count = input.readInt();
                if (lang == null || word == null || count < 1 || count > 1000) {
                    return;
                }
                LinkedHashMap<String, Integer> words = loadedWords.get(lang);
                if (words == null) {
                    if (loadedWords.size() >= MAX_LANGUAGES) {
                        return;
                    }
                    words = new LinkedHashMap<String, Integer>(32, 0.75f, true);
                    loadedWords.put(lang, words);
                }
                if (words.size() < MAX_LEARNED_WORDS) {
                    words.put(word, Integer.valueOf(count));
                }
            }
            int pairCount = input.readInt();
            if (pairCount < 0 || pairCount > MAX_BIGRAMS) {
                return;
            }
            for (int i = 0; i < pairCount; i++) {
                String lang = normalizeLanguage(input.readUTF());
                String previous = normalizeWord(lang, input.readUTF());
                String next = normalizeWord(lang, input.readUTF());
                int count = input.readInt();
                if (lang == null || previous == null || next == null || count < 1 || count > 1000) {
                    return;
                }
                LinkedHashMap<String, Integer> following = loadedPairs.get(pairKey(lang, previous));
                if (following == null) {
                    following = new LinkedHashMap<String, Integer>(4, 0.75f, true);
                    loadedPairs.put(pairKey(lang, previous), following);
                }
                following.put(next, Integer.valueOf(count));
            }
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid prediction model", exception);
        }
        synchronized (this) {
            learned.clear();
            learned.putAll(loadedWords);
            nextWords.clear();
            nextWords.putAll(loadedPairs);
            totalBigrams = pairCount(loadedPairs);
            revision++;
        }
    }

    public void save() throws IOException {
        File file;
        List<WordRecord> words = new ArrayList<WordRecord>();
        List<PairRecord> pairs = new ArrayList<PairRecord>();
        synchronized (this) {
            file = storageFile;
            if (file == null) {
                return;
            }
            for (Map.Entry<String, LinkedHashMap<String, Integer>> language : learned.entrySet()) {
                for (Map.Entry<String, Integer> word : language.getValue().entrySet()) {
                    words.add(new WordRecord(language.getKey(), word.getKey(), word.getValue()));
                }
            }
            for (Map.Entry<String, LinkedHashMap<String, Integer>> entry : nextWords.entrySet()) {
                String[] key = splitPairKey(entry.getKey());
                if (key == null) {
                    continue;
                }
                for (Map.Entry<String, Integer> next : entry.getValue().entrySet()) {
                    pairs.add(new PairRecord(key[0], key[1], next.getKey(), next.getValue()));
                }
            }
        }

        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create prediction model directory");
        }
        File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(stream))) {
            output.writeInt(FILE_MAGIC);
            output.writeInt(FILE_VERSION);
            output.writeInt(words.size());
            for (WordRecord word : words) {
                output.writeUTF(word.language);
                output.writeUTF(word.word);
                output.writeInt(word.count);
            }
            output.writeInt(pairs.size());
            for (PairRecord pair : pairs) {
                output.writeUTF(pair.language);
                output.writeUTF(pair.previous);
                output.writeUTF(pair.next);
                output.writeInt(pair.count);
            }
            output.flush();
            stream.getFD().sync();
        } catch (IOException exception) {
            temporary.delete();
            throw exception;
        }
        replaceAtomically(temporary, file);
    }

    static boolean isValidWord(String language, String word) {
        return normalizeWord(normalizeLanguage(language), word) != null;
    }

    private List<String> popularWords(String language, int limit) {
        LinkedHashMap<String, String> candidates = new LinkedHashMap<String, String>();
        DictionaryIndex index = dictionaries.get(language);
        if (index != null) {
            for (String word : index.popular) {
                candidates.put(word, word);
                if (candidates.size() >= limit) {
                    break;
                }
            }
        }
        Map<String, Integer> personal = learned.get(language);
        if (personal != null && candidates.size() < limit) {
            List<String> learnedWords = new ArrayList<String>(personal.keySet());
            Collections.sort(learnedWords, new Comparator<String>() {
                @Override
                public int compare(String left, String right) {
                    int countOrder = Integer.compare(personal.get(right), personal.get(left));
                    return countOrder != 0 ? countOrder : left.compareTo(right);
                }
            });
            for (String word : learnedWords) {
                candidates.put(word, word);
                if (candidates.size() >= limit) {
                    break;
                }
            }
        }
        return new ArrayList<String>(candidates.values());
    }

    private List<String> editCandidates(String language, String query) {
        LinkedHashSet<String> candidates = new LinkedHashSet<String>();
        DictionaryIndex index = dictionaries.get(language);
        if (index != null) {
            index.addEditCandidates(query, candidates, EDIT_CANDIDATE_LIMIT);
        }
        Map<String, Integer> personal = learned.get(language);
        if (personal != null && candidates.size() < EDIT_CANDIDATE_LIMIT) {
            for (String word : personal.keySet()) {
                if (candidates.size() >= EDIT_CANDIDATE_LIMIT) {
                    break;
                }
                candidates.add(word);
            }
        }
        return new ArrayList<String>(candidates);
    }

    private double score(String language, String word) {
        DictionaryIndex index = dictionaries.get(language);
        int frequency = index == null || index.frequency.get(word) == null
                ? 0 : index.frequency.get(word).intValue();
        Map<String, Integer> personal = learned.get(language);
        Integer personalCount = personal == null ? null : personal.get(word);
        return Math.log1p(frequency) + (1.3d * Math.log1p(personalCount == null ? 0 : personalCount));
    }

    private static List<String> ranked(Map<String, Double> scores) {
        List<RankedWord> ranked = new ArrayList<RankedWord>(scores.size());
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            ranked.add(new RankedWord(entry.getKey(), entry.getValue().doubleValue(), 0));
        }
        Collections.sort(ranked, RankedWord.BEST_FIRST);
        List<String> words = new ArrayList<String>(ranked.size());
        for (RankedWord word : ranked) {
            words.add(word.word);
        }
        return words;
    }

    private static void addResult(Map<String, String> result, String normalized, String display, int limit) {
        if (result.size() < limit && normalized != null && display != null && !result.containsKey(normalized)) {
            result.put(normalized, display);
        }
    }

    private static List<String> immutableValues(LinkedHashMap<String, String> result) {
        return Collections.unmodifiableList(new ArrayList<String>(result.values()));
    }

    private static List<String> immutablePrefix(List<String> words, int limit) {
        return Collections.unmodifiableList(new ArrayList<String>(words.subList(0, Math.min(limit, words.size()))));
    }

    private static String normalizeLanguage(String language) {
        if (language == null) {
            return null;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 24) {
            return null;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            if (!(character >= 'a' && character <= 'z') && !(character >= '0' && character <= '9')
                    && character != '-' && character != '_') {
                return null;
            }
        }
        return normalized;
    }

    private static String normalizeWord(String language, String word) {
        if (language == null || word == null || word.isEmpty() || codePointLength(word) > MAX_WORD_LENGTH) {
            return null;
        }
        boolean sawLetter = false;
        Character.UnicodeScript script = null;
        for (int offset = 0; offset < word.length();) {
            int codePoint = word.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) {
                return null;
            }
            if ("en".equals(language)) {
                if (!((codePoint >= 'a' && codePoint <= 'z') || (codePoint >= 'A' && codePoint <= 'Z'))) {
                    return null;
                }
            } else if ("ru".equals(language)) {
                if (!((codePoint >= 'а' && codePoint <= 'я') || (codePoint >= 'А' && codePoint <= 'Я')
                        || codePoint == 'ё' || codePoint == 'Ё')) {
                    return null;
                }
            } else {
                Character.UnicodeScript current = Character.UnicodeScript.of(codePoint);
                if (script != null && script != current) {
                    return null;
                }
                script = current;
            }
            sawLetter = true;
        }
        return sawLetter ? word.toLowerCase(Locale.ROOT) : null;
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String prefix(String word, int length) {
        int end = word.offsetByCodePoints(0, Math.min(length, codePointLength(word)));
        return word.substring(0, end);
    }

    private static String firstLengthKey(String word) {
        int first = word.codePointAt(0);
        return new String(Character.toChars(first)) + ':' + codePointLength(word);
    }

    private static String pairKey(String language, String previous) {
        return language + '\u0001' + previous;
    }

    private static String[] splitPairKey(String key) {
        int separator = key.indexOf('\u0001');
        if (separator <= 0 || separator == key.length() - 1) {
            return null;
        }
        return new String[]{key.substring(0, separator), key.substring(separator + 1)};
    }

    private static void addLimited(Map<String, List<String>> index, String key, String word, int limit) {
        List<String> words = index.get(key);
        if (words == null) {
            words = new ArrayList<String>(Math.min(limit, 16));
            index.put(key, words);
        }
        if (words.size() < limit) {
            words.add(word);
        }
    }

    private static void addLimited(Map<Integer, List<String>> index, int key, String word, int limit) {
        List<String> words = index.get(Integer.valueOf(key));
        if (words == null) {
            words = new ArrayList<String>(Math.min(limit, 16));
            index.put(Integer.valueOf(key), words);
        }
        if (words.size() < limit) {
            words.add(word);
        }
    }

    private static List<String> deletionForms(String word) {
        int[] all = word.codePoints().limit(8).toArray();
        LinkedHashSet<String> forms = new LinkedHashSet<String>();
        forms.add(fromCodePoints(all, -1, -1));
        for (int first = 0; first < all.length; first++) {
            forms.add(fromCodePoints(all, first, -1));
            for (int second = first + 1; second < all.length; second++) {
                forms.add(fromCodePoints(all, first, second));
            }
        }
        return new ArrayList<String>(forms);
    }

    private static String fromCodePoints(int[] codePoints, int skipOne, int skipTwo) {
        StringBuilder result = new StringBuilder(codePoints.length);
        for (int i = 0; i < codePoints.length; i++) {
            if (i != skipOne && i != skipTwo) {
                result.appendCodePoint(codePoints[i]);
            }
        }
        return result.toString();
    }

    private static int editDistance(String left, String right, int limit) {
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        if (Math.abs(a.length - b.length) > limit) {
            return limit + 1;
        }
        int[] previousPrevious = null;
        int[] previous = new int[b.length + 1];
        int[] current = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length; i++) {
            current[0] = i;
            int rowMinimum = current[0];
            for (int j = 1; j <= b.length; j++) {
                int substitution = previous[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    current[j] = Math.min(current[j], previousPrevious[j - 2] + 1);
                }
                rowMinimum = Math.min(rowMinimum, current[j]);
            }
            if (rowMinimum > limit) {
                return limit + 1;
            }
            int[] reusable = previousPrevious;
            previousPrevious = previous;
            previous = current;
            current = reusable == null ? new int[b.length + 1] : reusable;
        }
        return previous[b.length];
    }

    private static String matchCase(String candidate, String typed) {
        boolean anyUpper = false;
        boolean titleCase = false;
        int[] codePoints = typed.codePoints().toArray();
        if (codePoints.length > 0 && Character.isUpperCase(codePoints[0])) {
            titleCase = true;
            for (int i = 1; i < codePoints.length; i++) {
                if (Character.isUpperCase(codePoints[i])) {
                    titleCase = false;
                    break;
                }
            }
        }
        for (int codePoint : codePoints) {
            anyUpper |= Character.isUpperCase(codePoint);
        }
        if (anyUpper && !titleCase) {
            return candidate.toUpperCase(Locale.ROOT);
        }
        if (titleCase && !candidate.isEmpty()) {
            int first = candidate.codePointAt(0);
            int end = Character.charCount(first);
            return new String(Character.toChars(Character.toUpperCase(first)))
                    + candidate.substring(end).toLowerCase(Locale.ROOT);
        }
        return candidate.toLowerCase(Locale.ROOT);
    }

    private static void evictLeastUseful(LinkedHashMap<String, Integer> words) {
        String victim = null;
        int minimum = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : words.entrySet()) {
            if (entry.getValue().intValue() < minimum) {
                minimum = entry.getValue().intValue();
                victim = entry.getKey();
            }
        }
        if (victim != null) {
            words.remove(victim);
        }
    }

    private void evictLeastUsefulPair() {
        String victimKey = null;
        String victimWord = null;
        int minimum = Integer.MAX_VALUE;
        for (Map.Entry<String, LinkedHashMap<String, Integer>> entry : nextWords.entrySet()) {
            for (Map.Entry<String, Integer> following : entry.getValue().entrySet()) {
                if (following.getValue().intValue() < minimum) {
                    minimum = following.getValue().intValue();
                    victimKey = entry.getKey();
                    victimWord = following.getKey();
                }
            }
        }
        if (victimKey != null) {
            LinkedHashMap<String, Integer> following = nextWords.get(victimKey);
            following.remove(victimWord);
            totalBigrams--;
            if (following.isEmpty()) {
                nextWords.remove(victimKey);
            }
        }
    }

    private static int pairCount(Map<String, LinkedHashMap<String, Integer>> pairs) {
        int count = 0;
        for (Map<String, Integer> following : pairs.values()) {
            count += following.size();
        }
        return count;
    }

    private static void replaceAtomically(File temporary, File destination) throws IOException {
        if (temporary.renameTo(destination)) {
            return;
        }
        File backup = new File(destination.getPath() + ".bak");
        if (backup.exists() && !backup.delete()) {
            temporary.delete();
            throw new IOException("Cannot replace prediction model");
        }
        boolean hadDestination = destination.exists();
        if (hadDestination && !destination.renameTo(backup)) {
            temporary.delete();
            throw new IOException("Cannot replace prediction model");
        }
        if (!temporary.renameTo(destination)) {
            if (hadDestination) {
                backup.renameTo(destination);
            }
            temporary.delete();
            throw new IOException("Cannot commit prediction model");
        }
        backup.delete();
    }

    private static final class DictionaryWord {
        final String word;
        final int frequency;

        DictionaryWord(String word, int frequency) {
            this.word = word;
            this.frequency = frequency;
        }
    }

    private static final class DictionaryIndex {
        final Map<String, Integer> frequency = new HashMap<String, Integer>();
        final Map<String, List<String>> byPrefix = new HashMap<String, List<String>>();
        final Map<String, List<String>> byDeletion = new HashMap<String, List<String>>();
        final Map<Integer, List<String>> byLength = new HashMap<Integer, List<String>>();
        final Map<String, List<String>> byFirstAndLength = new HashMap<String, List<String>>();
        final List<String> popular = new ArrayList<String>();
        final List<String> alphabeticalWords = new ArrayList<String>();

        boolean hasWordStartingWith(String prefix) {
            int position = Collections.binarySearch(alphabeticalWords, prefix);
            if (position < 0) {
                position = -position - 1;
            }
            return position < alphabeticalWords.size() && alphabeticalWords.get(position).startsWith(prefix);
        }

        List<String> prefixCandidates(String word) {
            String key = prefix(word, Math.min(PREFIX_DEPTH, codePointLength(word)));
            List<String> candidates = byPrefix.get(key);
            return candidates == null ? Collections.<String>emptyList() : candidates;
        }

        void addEditCandidates(String query, LinkedHashSet<String> candidates, int limit) {
            for (String deletion : deletionForms(query)) {
                addAll(candidates, byDeletion.get(deletion), query, limit);
                if (candidates.size() >= limit) {
                    return;
                }
            }
            int length = codePointLength(query);
            for (int candidateLength = Math.max(1, length - 1); candidateLength <= length + 1; candidateLength++) {
                addAll(candidates, byLength.get(Integer.valueOf(candidateLength)), query, limit);
                if (candidates.size() >= limit) {
                    return;
                }
                addAll(candidates, byFirstAndLength.get(firstLengthKey(query, candidateLength)), query, limit);
                if (candidates.size() >= limit) {
                    return;
                }
            }
        }

        private static void addAll(LinkedHashSet<String> destination, List<String> source,
                                   String query, int limit) {
            if (source == null) {
                return;
            }
            for (String word : source) {
                if (!word.equals(query)) {
                    destination.add(word);
                    if (destination.size() >= limit) {
                        return;
                    }
                }
            }
        }

        private static String firstLengthKey(String word, int length) {
            int first = word.codePointAt(0);
            return new String(Character.toChars(first)) + ':' + length;
        }
    }

    private static final class RankedWord {
        static final Comparator<RankedWord> BEST_FIRST = new Comparator<RankedWord>() {
            @Override
            public int compare(RankedWord left, RankedWord right) {
                int scoreOrder = Double.compare(right.score, left.score);
                return scoreOrder != 0 ? scoreOrder : left.word.compareTo(right.word);
            }
        };

        final String word;
        final double score;
        final int distance;

        RankedWord(String word, double score, int distance) {
            this.word = word;
            this.score = score;
            this.distance = distance;
        }
    }

    private static final class WordRecord {
        final String language;
        final String word;
        final int count;

        WordRecord(String language, String word, Integer count) {
            this.language = language;
            this.word = word;
            this.count = count.intValue();
        }
    }

    private static final class PairRecord {
        final String language;
        final String previous;
        final String next;
        final int count;

        PairRecord(String language, String previous, String next, Integer count) {
            this.language = language;
            this.previous = previous;
            this.next = next;
            this.count = count.intValue();
        }
    }
}
