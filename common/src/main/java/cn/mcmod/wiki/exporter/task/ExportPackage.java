package cn.mcmod.wiki.exporter.task;

import cn.mcmod.wiki.exporter.util.ExportArchives;
import cn.mcmod.wiki.exporter.util.JsonFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ExportPackage {
    private final Path staging;
    private final Path archive;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "mcmods-exporter-package");
            thread.setDaemon(true);
            return thread;
        });
    private Future<ExportArchives.PreparedArchive> preparation;
    private Future<Void> finalization;

    ExportPackage(ExportArchives.Destination destination) {
        staging = destination.staging();
        archive = destination.archive();
    }

    void prepare(String manifest) {
        preparation = worker.submit(() -> ExportArchives.prepare(staging, archive, manifest, cancelled::get));
    }

    boolean prepared() {
        return preparation.isDone();
    }

    void requestCancellation() {
        cancelled.set(true);
    }

    // Called by the client after its last world, resource and cancellation checks.
    void publish() throws IOException {
        var prepared = await(preparation);
        prepared.publish();
        finalization = worker.submit(() -> {
            try (prepared) {
                ExportArchives.deleteTree(staging);
            }
            return null;
        });
        worker.shutdown();
    }

    void retain(String manifest) {
        requestCancellation();
        Future<ExportArchives.PreparedArchive> pending = preparation;
        finalization = worker.submit(() -> {
            try (var discarded = unpublishedArchive(pending)) {
                JsonFiles.writeSerialized(staging.resolve("manifest.json"), manifest);
            }
            return null;
        });
        worker.shutdown();
    }

    boolean finalized() {
        return finalization.isDone();
    }

    void awaitFinalization() throws IOException {
        await(finalization);
    }

    private static ExportArchives.PreparedArchive unpublishedArchive(
        Future<ExportArchives.PreparedArchive> pending
    ) {
        if (pending == null) {
            return null;
        }
        try {
            return await(pending);
        } catch (IOException failedPreparation) {
            // The preparer removes its own temporary file on failure or cancellation.
            return null;
        }
    }

    private static <T> T await(Future<T> future) throws IOException {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException exception) {
                    interrupted = true;
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof IOException failure) {
                        throw failure;
                    }
                    ExportReport.rethrowFatal(cause);
                    throw new IOException("Export filesystem task failed", cause);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
