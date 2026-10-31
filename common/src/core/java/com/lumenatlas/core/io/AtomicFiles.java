package com.lumenatlas.core.io;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe file writes. Data is written to a temporary file next to the target and then moved over it, so a
 * crash or a full disk never leaves a half-written file behind. The previous version is kept as {@code <name>.bak}
 * and {@link #read} falls back to it when the main file is missing or unreadable.
 */
public final class AtomicFiles {
    private AtomicFiles() {}

    @FunctionalInterface
    public interface Parser<T> {
        T parse(byte[] data) throws IOException;
    }

    public static Path backupOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    public static void write(Path file, byte[] data) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, data);
        if (Files.exists(file)) {
            try {
                Files.copy(file, backupOf(file), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // A missing backup is not a reason to lose the new data.
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reads and parses {@code file}; if that fails, tries the backup. Returns {@code null} when neither exists.
     * Throws only when a file exists but neither copy could be parsed.
     */
    public static <T> T read(Path file, Parser<T> parser) throws IOException {
        IOException failure = null;
        for (Path candidate : new Path[] {file, backupOf(file)}) {
            if (!Files.isRegularFile(candidate)) continue;
            try {
                return parser.parse(Files.readAllBytes(candidate));
            } catch (IOException | RuntimeException e) {
                if (failure == null) failure = e instanceof IOException io ? io : new IOException(e);
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
        return null;
    }
}
