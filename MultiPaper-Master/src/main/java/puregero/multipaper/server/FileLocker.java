package puregero.multipaper.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Put any files being written into a map, and if they're read while they're
 * being written, return the bytes that are being written instead of reading
 * from the file.
 *
 * Writes to the same file are serialised via a striped {@link ReentrantLock}
 * (no busy-waiting). Reads never block on writes — they observe the
 * in-flight bytes from {@link #beingWritten} when present. This replaces a
 * previous {@code synchronized + wait(1)} loop that pinned the master main
 * thread when many writes overlapped (a real risk at 1500-player scale).
 */
public class FileLocker {

    private static final int STRIPES = 4096;
    private static final ReentrantLock[] writeLocks = new ReentrantLock[STRIPES];

    static {
        for (int i = 0; i < STRIPES; i++) {
            writeLocks[i] = new ReentrantLock();
        }
    }

    private static final ConcurrentHashMap<File, byte[]> beingWritten = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<File, CompletableFuture<Void>> locks = new ConcurrentHashMap<>();

    public static CompletableFuture<CompletableFuture<Void>> createLockAsync(File file) {
        CompletableFuture<Void> existing = locks.get(file);
        if (existing != null) {
            return existing.thenCompose(value -> createLockAsync(file));
        }

        CompletableFuture<Void> lock = new CompletableFuture<>();
        CompletableFuture<Void> chain = lock.thenRun(() -> locks.remove(file));

        // Race: if another thread inserted while we built `chain`, recurse on theirs.
        CompletableFuture<Void> race = locks.putIfAbsent(file, chain);
        if (race != null) {
            return race.thenCompose(value -> createLockAsync(file));
        }

        return CompletableFuture.completedFuture(lock);
    }

    public static byte[] readBytes(File file) throws IOException {
        byte[] pending = beingWritten.get(file);
        if (pending != null) {
            return pending;
        }
        return !file.isFile() ? new byte[0] : Files.readAllBytes(file.toPath());
    }

    public static void writeBytes(File file, byte[] bytes) throws IOException {
        ReentrantLock lock = writeLocks[(file.hashCode() & 0x7fffffff) % STRIPES];
        lock.lock();
        try {
            beingWritten.put(file, bytes);
            try {
                file.getParentFile().mkdirs();
                safeWrite(file, bytes);
            } finally {
                beingWritten.remove(file);
            }
        } finally {
            lock.unlock();
        }
    }

    private static void safeWrite(File file, byte[] bytes) throws IOException {
        File newFile = new File(file.getParentFile(), file.getName() + "_new");
        File oldFile = new File(file.getParentFile(), file.getName() + "_old");

        Files.write(newFile.toPath(), bytes);
        safeReplaceFile(file.toPath(), newFile.toPath(), oldFile.toPath());
    }

    private static void safeReplaceFile(Path file, Path newFile, Path oldFile) throws IOException {
        if (Files.exists(file)) {
            Files.move(file, oldFile, StandardCopyOption.REPLACE_EXISTING);
        }

        Files.move(newFile, file, StandardCopyOption.REPLACE_EXISTING);

        if (Files.exists(oldFile)) {
            Files.delete(oldFile);
        }
    }
}
