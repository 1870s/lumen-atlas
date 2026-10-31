package com.lumenatlas.world;

import com.lumenatlas.LumenAtlas;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The single background thread for disk access. All writes go through it in submission order, so a later save of a
 * file always wins over an earlier one.
 */
public final class IoWorker {
    @FunctionalInterface
    public interface IoTask {
        void run() throws Exception;
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Lumen Atlas IO");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private IoWorker() {}

    public static CompletableFuture<Void> submit(String what, IoTask task) {
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LumenAtlas.LOGGER.error("Lumen Atlas could not save/load {}", what, e);
            }
        }, EXECUTOR);
    }

    public static <T> CompletableFuture<T> supply(java.util.concurrent.Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, EXECUTOR);
    }

    /** Waits until everything submitted so far is written (used when leaving a world). */
    public static void flush(long timeoutMs) {
        try {
            CompletableFuture.runAsync(() -> {}, EXECUTOR).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LumenAtlas.LOGGER.warn("Lumen Atlas IO did not finish within {} ms", timeoutMs);
        }
    }
}
