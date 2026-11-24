package cn.mcmod.wiki.exporter.util;

import net.minecraft.server.packs.resources.IoSupplier;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Staged files become visible to later stages and the archiver only after their writer closes. */
public final class ExportFiles {
    private ExportFiles() {
    }

    public static void copy(Path file, IoSupplier<InputStream> source) throws IOException {
        replace(file, temporary -> {
            try (InputStream input = source.get(); OutputStream output = Files.newOutputStream(temporary)) {
                input.transferTo(output);
            }
        });
    }

    static void replace(Path file, WriteFile content) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".mcmods-write-", ".tmp");
        try {
            content.write(temporary);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @FunctionalInterface
    interface WriteFile {
        void write(Path temporary) throws IOException;
    }
}
