package com.trost.ioskeyboard;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory ordered history and its Android-independent on-disk payload format. */
public final class ClipboardHistory {
    public static final int PAGE_SIZE = 40;
    private static final int MAGIC = 0x54434832;
    private static final int VERSION = 1;

    private final ArrayList<Entry> entries = new ArrayList<>();
    private final HashMap<String, Entry> byText = new HashMap<>();
    private volatile Entry[] visible = new Entry[0];
    private volatile Entry[] visiblePinned = new Entry[0];
    private long sequence;
    private volatile int pinnedCount;

    public static final class Entry {
        public final String text;
        public final long timestamp;
        public final boolean pinned;
        private final long sequence;

        private Entry(String text, long timestamp, boolean pinned, long sequence) {
            this.text = text;
            this.timestamp = timestamp;
            this.pinned = pinned;
            this.sequence = sequence;
        }
    }

    public Entry addOrPromote(String text, long timestamp) {
        if (text == null || text.length() == 0) return null;
        Entry previous = byText.remove(text);
        boolean pinned = previous != null && previous.pinned;
        if (previous != null) {
            entries.remove(previous);
            if (pinned) pinnedCount--;
        }
        if (sequence < Long.MAX_VALUE) sequence++;
        Entry entry = new Entry(text, timestamp, pinned, sequence);
        entries.add(0, entry);
        byText.put(text, entry);
        if (pinned) pinnedCount++;
        publish();
        return entry;
    }

    public boolean setPinned(String text, boolean pinned) {
        Entry previous = byText.get(text);
        if (previous == null || previous.pinned == pinned) return false;
        Entry replacement = new Entry(previous.text, previous.timestamp, pinned, previous.sequence);
        entries.set(entries.indexOf(previous), replacement);
        byText.put(text, replacement);
        pinnedCount += pinned ? 1 : -1;
        publish();
        return true;
    }

    public boolean remove(String text) {
        Entry entry = byText.remove(text);
        if (entry == null) return false;
        entries.remove(entry);
        if (entry.pinned) pinnedCount--;
        publish();
        return true;
    }

    public int clearUnpinned() {
        int removed = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (!entry.pinned) {
                entries.remove(i);
                byText.remove(entry.text);
                removed++;
            }
        }
        if (removed != 0) publish();
        return removed;
    }

    public void clear() {
        entries.clear();
        byText.clear();
        pinnedCount = 0;
        publish();
    }

    public int size() { return visible.length; }
    public int pinnedCount() { return pinnedCount; }

    public int pageCount(boolean pinnedOnly) {
        int count = pinnedOnly ? visiblePinned.length : visible.length;
        return (int) ((count + (long) PAGE_SIZE - 1L) / PAGE_SIZE);
    }

    public List<Entry> page(int pageIndex, boolean pinnedOnly) {
        if (pageIndex < 0) return Collections.emptyList();
        Entry[] current = pinnedOnly ? visiblePinned : visible;
        long skip = (long) pageIndex * PAGE_SIZE;
        ArrayList<Entry> result = new ArrayList<>(PAGE_SIZE);
        if (skip >= current.length) return Collections.emptyList();
        int start = (int) skip;
        int end = (int) Math.min((long) current.length, (long) start + PAGE_SIZE);
        for (int i = start; i < end; i++) result.add(current[i]);
        return Collections.unmodifiableList(result);
    }

    /** Merges disk state without discarding newer session copies or pins. */
    public void merge(ClipboardHistory diskHistory) {
        if (diskHistory == null || diskHistory == this) return;
        Entry[] current = visible;
        Entry[] disk = diskHistory.visible;
        LinkedHashMap<String, Entry> merged = new LinkedHashMap<>();
        for (Entry entry : current) merged.put(entry.text, entry);
        for (Entry entry : disk) {
            Entry existing = merged.get(entry.text);
            if (existing == null) {
                merged.put(entry.text, entry);
            } else {
                Entry latest = compareRecency(entry, existing) > 0 ? entry : existing;
                merged.put(entry.text, new Entry(latest.text,
                        Math.max(existing.timestamp, entry.timestamp),
                        existing.pinned || entry.pinned, latest.sequence));
            }
        }
        ArrayList<Entry> result = new ArrayList<>(merged.values());
        result.sort(new Comparator<Entry>() {
            @Override public int compare(Entry left, Entry right) {
                int bySequence = Long.compare(right.sequence, left.sequence);
                return bySequence != 0 ? bySequence : Long.compare(right.timestamp, left.timestamp);
            }
        });
        entries.clear();
        entries.addAll(result);
        byText.clear();
        pinnedCount = 0;
        for (Entry entry : entries) {
            byText.put(entry.text, entry);
            if (entry.pinned) pinnedCount++;
            sequence = Math.max(sequence, entry.sequence);
        }
        sequence = Math.max(sequence, diskHistory.sequence);
        publish();
    }

    public void writeTo(OutputStream output) throws IOException {
        Entry[] current = visible;
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeInt(VERSION);
        data.writeLong(sequence);
        data.writeInt(current.length);
        for (int i = current.length - 1; i >= 0; i--) {
            Entry entry = current[i];
            byte[] text = entry.text.getBytes(StandardCharsets.UTF_8);
            data.writeLong(entry.timestamp);
            data.writeBoolean(entry.pinned);
            data.writeLong(entry.sequence);
            data.writeInt(text.length);
            data.write(text);
        }
        data.flush();
    }

    public static ClipboardHistory readFrom(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(input);
        if (data.readInt() != MAGIC) throw new IOException("Not a clipboard history file");
        if (data.readInt() != VERSION) throw new IOException("Unsupported clipboard history version");
        ClipboardHistory history = new ClipboardHistory();
        history.sequence = data.readLong();
        if (history.sequence < 0) throw new IOException("Invalid clipboard history sequence");
        int count = data.readInt();
        if (count < 0) throw new IOException("Invalid clipboard history size");
        byte[] buffer = new byte[8192];
        for (int i = 0; i < count; i++) {
            long timestamp = data.readLong();
            boolean pinned = data.readBoolean();
            long order = data.readLong();
            int byteCount = data.readInt();
            if (order < 0 || byteCount < 0) throw new IOException("Invalid clipboard entry");
            ByteArrayOutputStream textBytes = new ByteArrayOutputStream();
            int remaining = byteCount;
            while (remaining > 0) {
                int read = data.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) throw new IOException("Truncated clipboard entry");
                textBytes.write(buffer, 0, read);
                remaining -= read;
            }
            String text = new String(textBytes.toByteArray(), StandardCharsets.UTF_8);
            if (text.length() != 0) history.addLoaded(new Entry(text, timestamp, pinned, order));
        }
        history.publish();
        return history;
    }

    static boolean hasHeader(int value) { return value == MAGIC; }

    private void addLoaded(Entry entry) {
        Entry previous = byText.get(entry.text);
        if (previous == null) {
            entries.add(0, entry);
            byText.put(entry.text, entry);
            if (entry.pinned) pinnedCount++;
        } else {
            Entry latest = compareRecency(entry, previous) >= 0 ? entry : previous;
            Entry merged = new Entry(entry.text, Math.max(previous.timestamp, entry.timestamp),
                    previous.pinned || entry.pinned, latest.sequence);
            entries.remove(previous);
            entries.add(0, merged);
            byText.put(entry.text, merged);
            if (previous.pinned != merged.pinned) pinnedCount++;
        }
        sequence = Math.max(sequence, entry.sequence);
    }

    private static int compareRecency(Entry left, Entry right) {
        int bySequence = Long.compare(left.sequence, right.sequence);
        return bySequence != 0 ? bySequence : Long.compare(left.timestamp, right.timestamp);
    }

    private void publish() {
        Entry[] all = entries.toArray(new Entry[0]);
        ArrayList<Entry> pinned = new ArrayList<>(pinnedCount);
        for (Entry entry : all) if (entry.pinned) pinned.add(entry);
        visiblePinned = pinned.toArray(new Entry[0]);
        visible = all;
    }
}
