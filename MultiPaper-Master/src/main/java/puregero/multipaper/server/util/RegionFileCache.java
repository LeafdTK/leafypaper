package puregero.multipaper.server.util;

/*
 ** 2011 January 5
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */

/*
 * 2011 February 16
 *
 * This source code is based on the work of Scaevolus (see notice above).
 * It has been slightly modified by Mojang AB to limit the maximum cache
 * size (relevant to extremely big worlds on Linux systems with limited
 * number of file handles). The region files are postfixed with ".mcr"
 * (Minecraft region file) instead of ".data" to differentiate from the
 * original McRegion files.
 *
 */

// A concurrent cache and wrapper for efficiently sharing RegionFiles.

import java.io.*;
import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class RegionFileCache {

    private static final int MAX_CACHE_SIZE = Integer.getInteger("max.regionfile.cache.size", 256);

    // Open RegionFile handles, keyed by canonical file. ConcurrentHashMap so reads
    // never block. Eviction order is tracked separately in `lruOrder` (newest at
    // tail) — best-effort LRU, not strict, but avoids a class-wide lock.
    private static final ConcurrentHashMap<File, Reference<RegionFile>> cache = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<File> lruOrder = new ConcurrentLinkedDeque<>();

    private RegionFileCache() {
    }

    public static boolean isRegionFileOpen(File regionDir, int chunkX, int chunkZ) {
        File file = canonical(new File(regionDir, "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca"));
        Reference<RegionFile> ref = cache.get(file);
        return ref != null && ref.get() != null;
    }

    private static File canonical(File file) {
        try {
            return new File(file.getCanonicalPath());
        } catch (IOException e) {
            e.printStackTrace();
            return file;
        }
    }

    private static File getFileForRegionFile(File regionDir, int chunkX, int chunkZ) {
        return new File(regionDir, "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
    }

    public static RegionFile getRegionFileIfExists(File regionDir, int chunkX, int chunkZ) {
        File file = canonical(getFileForRegionFile(regionDir, chunkX, chunkZ));

        Reference<RegionFile> ref = cache.get(file);
        if (ref != null) {
            RegionFile rf = ref.get();
            if (rf != null) {
                touch(file);
                return rf;
            }
        }

        if (file.isFile()) {
            return getRegionFile(regionDir, chunkX, chunkZ);
        }
        return null;
    }

    public static RegionFile getRegionFile(File regionDir, int chunkX, int chunkZ) {
        File file = canonical(getFileForRegionFile(regionDir, chunkX, chunkZ));

        Reference<RegionFile> ref = cache.get(file);
        if (ref != null) {
            RegionFile rf = ref.get();
            if (rf != null) {
                touch(file);
                return rf;
            }
        }

        if (!regionDir.exists()) {
            regionDir.mkdirs();
        }

        // Atomic open-if-absent. computeIfAbsent guarantees the RegionFile
        // constructor runs exactly once per key even under concurrent callers.
        final File canonicalFile = file;
        Reference<RegionFile> stored = cache.compute(canonicalFile, (k, existing) -> {
            if (existing != null && existing.get() != null) {
                return existing;
            }
            return new SoftReference<>(new RegionFile(k));
        });

        lruOrder.addLast(canonicalFile);
        if (cache.size() > MAX_CACHE_SIZE) {
            clearOne();
        }

        return stored.get();
    }

    private static void touch(File file) {
        // Best-effort LRU bump. We just append; drift between cache size and
        // deque size is corrected during eviction by skipping stale entries.
        lruOrder.addLast(file);
    }

    private static void clearOne() {
        // Walk LRU front, pop until we find a key actually present in the cache.
        // This costs O(drift) but drift is bounded by how often `touch` ran.
        for (int i = 0; i < 32; i++) {
            File victim = lruOrder.pollFirst();
            if (victim == null) {
                return;
            }
            Reference<RegionFile> ref = cache.remove(victim);
            if (ref == null) {
                continue;
            }
            RegionFile rf = ref.get();
            if (rf != null) {
                try {
                    rf.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return;
            }
        }
    }

    public static int getSizeDelta(File basePath, int chunkX, int chunkZ) {
        RegionFile r = getRegionFile(basePath, chunkX, chunkZ);
        return r.getSizeDelta();
    }

    public static DataInputStream getChunkDataInputStream(File basePath, int chunkX, int chunkZ) {
        RegionFile r = getRegionFile(basePath, chunkX, chunkZ);
        if (r != null) {
            return r.getChunkDataInputStream(chunkX, chunkZ);
        } else {
            return null;
        }
    }

    public static DataOutputStream getChunkDataOutputStream(File basePath, int chunkX, int chunkZ) {
        RegionFile r = getRegionFile(basePath, chunkX, chunkZ);
        return r.getChunkDataOutputStream(chunkX, chunkZ);
    }

    public static CompletableFuture<byte[]> getChunkDeflatedDataAsync(File basePath, int chunkX, int chunkZ) {
        RegionFile r = getRegionFileIfExists(basePath, chunkX, chunkZ);
        if (r != null) {
            return r.submitTask(regionFile -> regionFile.getDeflatedBytes(chunkX, chunkZ));
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static byte[] getChunkDeflatedData(File basePath, int chunkX, int chunkZ) {
        try {
            RegionFile r = getRegionFileIfExists(basePath, chunkX, chunkZ);
            if (r != null) {
                return r.getDeflatedBytes(chunkX, chunkZ);
            } else {
                return null;
            }
        } catch (Throwable throwable) {
            System.err.println("Error when trying to read chunk " + chunkX + "," + chunkZ + " in " + basePath);
            throw throwable;
        }
    }

    public static CompletableFuture<Void> putChunkDeflatedDataAsync(File basePath, int chunkX, int chunkZ, byte[] data) {
        RegionFile r = getRegionFile(basePath, chunkX, chunkZ);
        return r.submitTask(regionFile -> {
            regionFile.putDeflatedBytes(chunkX, chunkZ, data);
            return null;
        });
    }

    private static void putChunkDeflatedData(File basePath, int chunkX, int chunkZ, byte[] data) {
        try {
            RegionFile r = getRegionFile(basePath, chunkX, chunkZ);
            r.putDeflatedBytes(chunkX, chunkZ, data);
        } catch (Throwable throwable) {
            System.err.println("Error when trying to write chunk " + chunkX + "," + chunkZ + " in " + basePath);
            throw throwable;
        }
    }
}
