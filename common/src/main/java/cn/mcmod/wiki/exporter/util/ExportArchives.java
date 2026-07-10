package cn.mcmod.wiki.exporter.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ExportArchives {
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

    public static void createZip(Path sourceDirectory, Path archive) throws IOException {
        Path source = sourceDirectory.toAbsolutePath().normalize();
        Path target = archive.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        try {
            try (OutputStream output = Files.newOutputStream(temporary);
                 ZipOutputStream zip = new ZipOutputStream(output)) {
                try (Stream<Path> walk = Files.walk(source)) {
                    for (Path file : walk
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .sorted()
                        .toList()) {
                        String relative = source.relativize(file).toString().replace('\\', '/');
                        ZipEntry entry = new ZipEntry(relative);
                        entry.setTime(Files.getLastModifiedTime(file).toMillis());
                        zip.putNextEntry(entry);
                        Files.copy(file, zip);
                        zip.closeEntry();
                    }
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
        } catch (IOException | RuntimeException | Error throwable) {
            Files.deleteIfExists(temporary);
            throw throwable;
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
