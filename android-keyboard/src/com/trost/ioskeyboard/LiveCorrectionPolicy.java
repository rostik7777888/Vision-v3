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
    private static final int MIN_CONTEXT_OBSERVATIONS = 3;
    private static final double MIN_SCORE_MARGIN = Math.log(5.0d);
    private static final double MIN_CONTEXT_SCORE_MARGIN = Math.log(2.0d);
    private static final double MAX_CONTEXT_SCORE_BONUS = Math.log(8.0d);
    private static final double KEYBOARD_ADJACENCY_BONUS = Math.log(2.0d);
    private static final String[][] KEYBOARD_ROWS = {
            {"qwertyuiop", "asdfghjkl", "zxcvbnm"},
            {"йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"}
    };

    private LiveCorrectionPolicy() {
    }

    public static String chooseCorrection(String language, String typed, boolean knownWord,
                                          boolean knownPrefix, Map<String, Integer> candidateFrequencies) {
        return chooseCorrection(language, typed, knownWord, knownPrefix, candidateFrequencies,
                Collections.<String, Integer>emptyMap());
    }

    public static String chooseCorrection(String language, String typed, boolean knownWord,
                                          boolean knownPrefix, Map<String, Integer> candidateFrequencies,
                                          Map<String, Integer> contextualCounts) {
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
            Integer contextCount = contextualCounts == null ? null : contextualCounts.get(candidate);
            int observations = contextCount == null ? 0 : Math.max(0, contextCount.intValue());
            double score = Math.log1p(frequency.intValue());
            if (hasKeyboardAdjacentSubstitution(typed, candidate)) {
                score += KEYBOARD_ADJACENCY_BONUS;
            }
            if (observations >= MIN_CONTEXT_OBSERVATIONS) {
                score += Math.min(MAX_CONTEXT_SCORE_BONUS, Math.log1p(observations));
            }
            candidates.add(new RankedCandidate(candidate, score, observations));
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Collections.sort(candidates, new Comparator<RankedCandidate>() {
            @Override
            public int compare(RankedCandidate left, RankedCandidate right) {
                int scoreOrder = Double.compare(right.score, left.score);
                if (scoreOrder != 0) {
                    return scoreOrder;
                }
                int contextOrder = Integer.compare(right.contextCount, left.contextCount);
                return contextOrder != 0 ? contextOrder : left.word.compareTo(right.word);
            }
        });
        RankedCandidate best = candidates.get(0);
        double minimumMargin = hasDistinctiveContext(best, candidates)
                ? MIN_CONTEXT_SCORE_MARGIN : MIN_SCORE_MARGIN;
        if (candidates.size() > 1 && best.score - candidates.get(1).score < minimumMargin) {
            return null;
        }
        return best.word;
    }

    private static boolean hasDistinctiveContext(RankedCandidate best, List<RankedCandidate> candidates) {
        if (best.contextCount < MIN_CONTEXT_OBSERVATIONS) {
            return false;
        }
        int strongestOther = 0;
        for (RankedCandidate candidate : candidates) {
            if (candidate != best) {
                strongestOther = Math.max(strongestOther, candidate.contextCount);
            }
        }
        return best.contextCount >= strongestOther * 2;
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

    static boolean hasKeyboardAdjacentSubstitution(String typed, String candidate) {
        int[] first = typed.codePoints().toArray();
        int[] second = candidate.codePoints().toArray();
        if (first.length != second.length) {
            return false;
        }
        int mismatch = -1;
        for (int i = 0; i < first.length; i++) {
            if (first[i] != second[i]) {
                if (mismatch >= 0) {
                    return false;
                }
                mismatch = i;
            }
        }
        return mismatch >= 0 && areKeyboardNeighbors(first[mismatch], second[mismatch]);
    }

    private static boolean areKeyboardNeighbors(int first, int second) {
        for (String[] layout : KEYBOARD_ROWS) {
            for (int row = 0; row < layout.length; row++) {
                int firstColumn = layout[row].indexOf(first);
                if (firstColumn < 0) {
                    continue;
                }
                for (int otherRow = Math.max(0, row - 1);
                     otherRow <= Math.min(layout.length - 1, row + 1); otherRow++) {
                    int secondColumn = layout[otherRow].indexOf(second);
                    if (secondColumn >= 0 && Math.abs(row - otherRow) + Math.abs(firstColumn - secondColumn) <= 2
                            && (row != otherRow || Math.abs(firstColumn - secondColumn) == 1)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static final class RankedCandidate {
        final String word;
        final double score;
        final int contextCount;

        RankedCandidate(String word, double score, int contextCount) {
            this.word = word;
            this.score = score;
            this.contextCount = contextCount;
        }
    }
}
