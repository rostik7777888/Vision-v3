package com.trost.ioskeyboard;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

public final class LanguagePackStoreTest {
    private static final String FIRST = "{\"version\":1,\"languages\":[{\"code\":\"xx\",\"name\":\"First\",\"rows\":[\"abcdef\",\"ghijkl\",\"mnopqr\"]}]}";
    private static final String SECOND = FIRST.replace("First", "Second");

    private LanguagePackStoreTest() {
    }

    public static void main(String[] args) throws Exception {
        File directory = new File(System.getProperty("java.io.tmpdir"), "language-pack-test-" + System.nanoTime());
        File cache = new File(directory, "language-packs.json");
        try {
            check(LanguagePackStore.replaceCache(cache, FIRST.getBytes(StandardCharsets.UTF_8)), "valid cache is written");
            check("First".equals(LanguagePackStore.readCache(cache).languages().get("xx").name()), "written cache parses");
            check(!LanguagePackStore.replaceCache(cache, "not-json".getBytes(StandardCharsets.UTF_8)), "invalid update is rejected");
            check("First".equals(LanguagePackStore.readCache(cache).languages().get("xx").name()), "bad update preserves last good cache");
            check(LanguagePackStore.replaceCache(cache, SECOND.getBytes(StandardCharsets.UTF_8)), "valid update replaces cache");
            check("Second".equals(LanguagePackStore.readCache(cache).languages().get("xx").name()), "new cache is installed whole");
            Files.copy(cache.toPath(), new File(cache.getPath() + ".bak").toPath());
            Files.write(cache.toPath(), "interrupted write".getBytes(StandardCharsets.UTF_8));
            check("Second".equals(LanguagePackStore.readCache(cache).languages().get("xx").name()),
                    "interrupted primary cache recovers its last-good backup");
            check(LanguagePackStore.replaceCache(cache, FIRST.getBytes(StandardCharsets.UTF_8)),
                    "a recovered backup can be replaced by a new complete snapshot");
            check("First".equals(LanguagePackStore.readCache(cache).languages().get("xx").name()),
                    "replacement installs the new snapshot after crash recovery");
        } finally {
            delete(directory);
        }
        System.out.println("LanguagePackStoreTest passed");
    }

    private static void delete(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
