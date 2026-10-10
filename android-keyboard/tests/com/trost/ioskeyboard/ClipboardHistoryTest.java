package com.trost.ioskeyboard;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

public final class ClipboardHistoryTest {
    public static void main(String[] args) throws Exception {
        firstCopyAndDistinctCopies();
        duplicatePromotesAndKeepsPin();
        longUnicodeRoundTrip();
        mergeKeepsNewerCopiesAndPins();
        clearRemovesEntries();
        pagesContainAtMostFortyNewestFirst();
    }

    private static void firstCopyAndDistinctCopies() {
        ClipboardHistory history = new ClipboardHistory();
        history.addOrPromote("first", 1);
        history.addOrPromote("second", 2);
        check(history.size() == 2, "distinct copies are retained");
        check("second".equals(history.page(0, false).get(0).text), "latest copy is first");
        check("first".equals(history.page(0, false).get(1).text), "first copy remains available");
    }

    private static void duplicatePromotesAndKeepsPin() {
        ClipboardHistory history = new ClipboardHistory();
        history.addOrPromote("copy", 1);
        history.addOrPromote("other", 2);
        history.setPinned("copy", true);
        history.addOrPromote("copy", 3);
        check(history.size() == 2, "duplicate does not create a second row");
        ClipboardHistory.Entry latest = history.page(0, false).get(0);
        check("copy".equals(latest.text) && latest.timestamp == 3, "duplicate is promoted");
        check(latest.pinned, "duplicate promotion preserves pin");
    }

    private static void longUnicodeRoundTrip() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 6000; i++) text.append("Здравствуйте🌿漢字");
        ClipboardHistory history = new ClipboardHistory();
        history.addOrPromote(text.toString(), 42);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        history.writeTo(bytes);
        ClipboardHistory restored = ClipboardHistory.readFrom(new ByteArrayInputStream(bytes.toByteArray()));
        check(restored.size() == 1, "round trip restores one long entry");
        check(text.toString().equals(restored.page(0, false).get(0).text), "UTF-8 round trip preserves long Unicode text");
    }

    private static void mergeKeepsNewerCopiesAndPins() {
        ClipboardHistory current = new ClipboardHistory();
        current.addOrPromote("shared", 10);
        current.addOrPromote("new session copy", 20);
        ClipboardHistory disk = new ClipboardHistory();
        disk.addOrPromote("shared", 5);
        disk.setPinned("shared", true);
        disk.addOrPromote("older saved copy", 7);
        current.merge(disk);
        check(current.size() == 3, "merge retains distinct disk and session copies");
        check("new session copy".equals(current.page(0, false).get(0).text), "merge retains current ordering");
        ClipboardHistory.Entry shared = find(current.page(0, false), "shared");
        check(shared != null && shared.timestamp == 10 && shared.pinned, "merge keeps the newer copy and existing pin");
    }

    private static void clearRemovesEntries() {
        ClipboardHistory history = new ClipboardHistory();
        history.addOrPromote("one", 1);
        history.addOrPromote("two", 2);
        history.clear();
        check(history.size() == 0 && history.page(0, false).isEmpty(), "clear empties memory and pages");
    }

    private static void pagesContainAtMostFortyNewestFirst() {
        ClipboardHistory history = new ClipboardHistory();
        for (int i = 0; i < 81; i++) history.addOrPromote("item-" + i, i);
        List<ClipboardHistory.Entry> first = history.page(0, false);
        List<ClipboardHistory.Entry> second = history.page(1, false);
        List<ClipboardHistory.Entry> third = history.page(2, false);
        check(history.pageCount(false) == 3, "page count rounds up");
        check(first.size() == 40 && "item-80".equals(first.get(0).text), "first page is newest-first");
        check(second.size() == 40 && "item-40".equals(second.get(0).text), "second page starts at item forty");
        check(third.size() == 1 && "item-0".equals(third.get(0).text), "last page contains the remainder");
    }

    private static ClipboardHistory.Entry find(List<ClipboardHistory.Entry> entries, String text) {
        for (ClipboardHistory.Entry entry : entries) if (text.equals(entry.text)) return entry;
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
