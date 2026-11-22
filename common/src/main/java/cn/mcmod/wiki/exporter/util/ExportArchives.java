package cn.mcmod.wiki.exporter.util;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ExportArchives {
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final int IO_BUFFER_BYTES = 64 * 1024;

    private ExportArchives() {
    }

    public static String modSetName(Set<String> namespaces) {
        if (namespaces.isEmpty()) {
            return "all-mods";
        }
        List<String> sorted = namespaces.stream()
            .map(ExportArchives::safeFilePart)
            .distinct()
            .sorted()
            .toList();
        String joined = String.join("+", sorted);
        if (joined.length() <= 96) {
            return joined;
        }
        return String.join("+", sorted.subList(0, Math.min(3, sorted.size())))
            + "+" + Math.max(0, sorted.size() - 3) + "more";
    }

    public static Destination reserve(Path outputRoot, String base) throws IOException {
        Files.createDirectories(outputRoot);
        for (int suffix = 1; ; suffix++) {
            String name = suffix == 1 ? base : base + "-" + suffix;
            Path archive = outputRoot.resolve(name + ".zip");
            Path staging = outputRoot.resolve("." + name + ".staging");
            if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                Files.createDirectory(staging);
            } catch (FileAlreadyExistsException occupied) {
                continue;
            }
            // A prior job may have published and removed this staging name while we reserved it.
            if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(staging);
                continue;
            }
            return new Destination(staging, archive);
        }
    }

    public static PreparedArchive prepare(Path sourceDirectory, Path archive, String manifest,
                                          BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        Path source = sourceDirectory.toAbsolutePath().normalize();
        Path target = archive.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(target.getParent(), ".mcmods-export-", ".zip.tmp");
        PreparedArchive prepared = new PreparedArchive(temporary, target);
        try {
            byte[] buffer = new byte[IO_BUFFER_BYTES];
            int entries = 1;
            try (OutputStream output = Files.newOutputStream(temporary);
                 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output, buffer.length))) {
                zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
                zip.write(manifest.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                try (Stream<Path> walk = Files.walk(source)) {
                    for (Path file : walk
                        .filter(path -> {
                            checkCancelled(cancelled);
                            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
                        })
                        .sorted()
                        .toList()) {
                        checkCancelled(cancelled);
                        String relative = source.relativize(file).toString().replace('\\', '/');
                        if (relative.equals(MANIFEST_ENTRY)) {
                            continue;
                        }
                        ZipEntry entry = new ZipEntry(relative);
                        entry.setTime(Files.getLastModifiedTime(file).toMillis());
                        zip.putNextEntry(entry);
                        try (InputStream input = Files.newInputStream(file)) {
                            for (int count; (count = input.read(buffer)) != -1; ) {
                                checkCancelled(cancelled);
                                zip.write(buffer, 0, count);
                            }
                        }
                        zip.closeEntry();
                        entries++;
                    }
                }
            }
            verify(temporary, entries, buffer, cancelled);
            return prepared;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                prepared.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void verify(Path temporary, int entries, byte[] buffer,
                               BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        try (ZipFile zip = new ZipFile(temporary.toFile())) {
            if (zip.size() != entries || zip.getEntry(MANIFEST_ENTRY) == null) {
                throw new IOException("Incomplete export archive");
            }
            CRC32 checksum = new CRC32();
            var files = zip.entries();
            while (files.hasMoreElements()) {
                checkCancelled(cancelled);
                ZipEntry file = files.nextElement();
                checksum.reset();
                long size = 0;
                try (InputStream input = zip.getInputStream(file)) {
                    for (int count; (count = input.read(buffer)) != -1; ) {
                        checkCancelled(cancelled);
                        checksum.update(buffer, 0, count);
                        size += count;
                    }
                }
                if (size != file.getSize() || checksum.getValue() != file.getCrc()) {
                    throw new IOException("Export archive entry failed verification: " + file.getName());
                }
            }
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Export archive cancelled");
        }
    }

    public record Destination(Path staging, Path archive) {
    }

    public static final class PreparedArchive implements AutoCloseable {
        private final Path temporary;
        private final Path archive;

        private PreparedArchive(Path temporary, Path archive) {
            this.temporary = temporary;
            this.archive = archive;
        }

        public void publish() throws IOException {
            // A hard link publishes complete bytes atomically and fails if the name already exists.
            try {
                Files.createLink(archive, temporary);
            } catch (UnsupportedOperationException failure) {
                throw new IOException("The export directory does not support atomic ZIP publication", failure);
            }
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(temporary);
        }
    }

    public static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(directory)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        IOException failure = null;
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String safeFilePart(String value) {
        String safe = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
        return safe.isBlank() ? "mod" : safe;
    }
}
