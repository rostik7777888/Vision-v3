package com.trost.ioskeyboard;

import java.util.HashMap;
import java.util.Map;

/** Standalone checks for conservative mid-word correction decisions. */
public final class LiveCorrectionPolicyTest {
    private LiveCorrectionPolicyTest() {
    }

    public static void main(String[] args) {
        Map<String, Integer> frequent = new HashMap<String, Integer>();
        frequent.put("hello", Integer.valueOf(500000));
        check("hello".equals(LiveCorrectionPolicy.chooseCorrection(
                "en", "hlelo", false, false, frequent)), "adjacent transposition is corrected");
        check("hello".equals(LiveCorrectionPolicy.chooseCorrection(
                "en", "hellp", false, false, only("hello", 500000))), "single typo is corrected");
        check(LiveCorrectionPolicy.chooseCorrection("en", "hell", false, true, frequent) == null,
                "known prefixes are preserved");
        check(LiveCorrectionPolicy.chooseCorrection("en", "hello", true, false, frequent) == null,
                "known words are preserved");
        check(LiveCorrectionPolicy.chooseCorrection("en", "teh", false, false, frequent) == null,
                "short input is not corrected");
        check(LiveCorrectionPolicy.chooseCorrection("en", "Hlelo", false, false, frequent) == null,
                "capitalized input is not rewritten");
        check(LiveCorrectionPolicy.chooseCorrection("en", "hell9", false, false, frequent) == null,
                "numbers and mixed input are not rewritten");
        check(LiveCorrectionPolicy.chooseCorrection("en", "hello@site", false, false, frequent) == null,
                "addresses are not rewritten");
        check(LiveCorrectionPolicy.chooseCorrection("fr", "hlelo", false, false, frequent) == null,
                "unsupported languages are left alone");
        check(LiveCorrectionPolicy.chooseCorrection("en", "hellp", false, false,
                candidates("hello", 500000, "helly", 200000)) == null,
                "nearby candidate scores must be decisive");
        check(LiveCorrectionPolicy.chooseCorrection("en", "hellp", false, false,
                only("hello", 7999)) == null, "low-frequency candidates are ignored");
        check(LiveCorrectionPolicy.oneEditApart("quokak", "quokka"), "one adjacent transposition");
        check(!LiveCorrectionPolicy.oneEditApart("hexxo", "hello"), "multiple edits are not accepted");
        System.out.println("LiveCorrectionPolicyTest passed");
    }

    private static Map<String, Integer> only(String word, int frequency) {
        Map<String, Integer> candidates = new HashMap<String, Integer>();
        candidates.put(word, Integer.valueOf(frequency));
        return candidates;
    }

    private static Map<String, Integer> candidates(String first, int firstFrequency,
                                                   String second, int secondFrequency) {
        Map<String, Integer> candidates = only(first, firstFrequency);
        candidates.put(second, Integer.valueOf(secondFrequency));
        return candidates;
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
