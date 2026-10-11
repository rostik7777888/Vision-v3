package com.trost.ioskeyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Conservative, dictionary-backed decisions for correcting a word before its delimiter. */
public final class LiveCorrectionPolicy {
    private static final int MIN_WORD_LENGTH = 4;
    private static final int MIN_CANDIDATE_FREQUENCY = 8000;
    private static final double MIN_SCORE_MARGIN = Math.log(5.0d);

    private LiveCorrectionPolicy() {
    }

    public static String chooseCorrection(String language, String typed, boolean knownWord,
                                          boolean knownPrefix, Map<String, Integer> candidateFrequencies) {
        if (!isSupportedLanguage(language) || typed == null || knownWord || knownPrefix
                || codePointLength(typed) < MIN_WORD_LENGTH || !typed.equals(typed.toLowerCase(Locale.ROOT))
                || !PredictionModel.isValidWord(language, typed) || candidateFrequencies == null) {
            return null;
        }

        List<RankedCandidate> candidates = new ArrayList<RankedCandidate>();
        for (Map.Entry<String, Integer> entry : candidateFrequencies.entrySet()) {
            String candidate = entry.getKey();
            Integer frequency = entry.getValue();
            if (candidate == null || frequency == null || frequency.intValue() < MIN_CANDIDATE_FREQUENCY
                    || candidate.equals(typed) || !candidate.equals(candidate.toLowerCase(Locale.ROOT))
                    || !PredictionModel.isValidWord(language, candidate)
                    || !oneEditApart(typed, candidate)) {
                continue;
            }
            candidates.add(new RankedCandidate(candidate, Math.log1p(frequency.intValue())));
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Collections.sort(candidates, new Comparator<RankedCandidate>() {
            @Override
            public int compare(RankedCandidate left, RankedCandidate right) {
                int scoreOrder = Double.compare(right.score, left.score);
                return scoreOrder != 0 ? scoreOrder : left.word.compareTo(right.word);
            }
        });
        if (candidates.size() > 1
                && candidates.get(0).score - candidates.get(1).score < MIN_SCORE_MARGIN) {
            return null;
        }
        return candidates.get(0).word;
    }

    private static boolean isSupportedLanguage(String language) {
        if (language == null) {
            return false;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        return "en".equals(normalized) || "ru".equals(normalized);
    }

    static boolean oneEditApart(String left, String right) {
        int[] first = left.codePoints().toArray();
        int[] second = right.codePoints().toArray();
        if (Math.abs(first.length - second.length) > 1) {
            return false;
        }
        if (first.length == second.length) {
            int firstMismatch = -1;
            int secondMismatch = -1;
            for (int i = 0; i < first.length; i++) {
                if (first[i] != second[i]) {
                    if (firstMismatch < 0) {
                        firstMismatch = i;
                    } else if (secondMismatch < 0) {
                        secondMismatch = i;
                    } else {
                        return false;
                    }
                }
            }
            if (secondMismatch < 0) {
                return firstMismatch >= 0;
            }
            return secondMismatch == firstMismatch + 1
                    && first[firstMismatch] == second[secondMismatch]
                    && first[secondMismatch] == second[firstMismatch];
        }

        int[] shorter = first.length < second.length ? first : second;
        int[] longer = first.length < second.length ? second : first;
        int shortIndex = 0;
        int longIndex = 0;
        boolean skipped = false;
        while (shortIndex < shorter.length && longIndex < longer.length) {
            if (shorter[shortIndex] == longer[longIndex]) {
                shortIndex++;
                longIndex++;
            } else if (skipped) {
                return false;
            } else {
                skipped = true;
                longIndex++;
            }
        }
        return true;
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static final class RankedCandidate {
        final String word;
        final double score;

        RankedCandidate(String word, double score) {
            this.word = word;
            this.score = score;
        }
    }
}
