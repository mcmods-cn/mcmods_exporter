package cn.mcmod.wiki.exporter.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportArchivesTest {
    @Test
    void createsRootedArchiveAndDeletesStagingDirectory(@TempDir Path temporary) throws Exception {
        Path staging = temporary.resolve(".create-20260710.staging");
        Files.createDirectories(staging.resolve("registries"));
        Files.writeString(staging.resolve("manifest.json"), "{\"status\":\"failed\"}", StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("registries/items.json"), "{}", StandardCharsets.UTF_8);
        Path archive = temporary.resolve("create-20260710.zip");

        String manifest = "{\"status\":\"complete\"}";
        try (var prepared = ExportArchives.prepare(staging, archive, manifest, () -> false)) {
            assertFalse(Files.exists(archive), "verification must finish before the candidate is public");
            prepared.publish();
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            try (var input = zip.getInputStream(zip.getEntry("manifest.json"))) {
                assertEquals(manifest, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertNotNull(zip.getEntry("registries/items.json"));
            assertEquals(2, zip.size());
        }
        ExportArchives.deleteTree(staging);
        assertFalse(Files.exists(staging));
    }

    @Test
    void preservesExistingArchiveWhenDestinationIsCreatedAfterPreparation(@TempDir Path temporary) throws Exception {
        var destination = ExportArchives.reserve(temporary, "sample");
        Files.writeString(destination.staging().resolve("fixture.json"), "{}");

        try (var prepared = ExportArchives.prepare(destination.staging(), destination.archive(), "{}", () -> false)) {
            Files.writeString(destination.archive(), "existing archive bytes");
            assertThrows(FileAlreadyExistsException.class, prepared::publish);
        }

        assertEquals("existing archive bytes", Files.readString(destination.archive()));
        assertTrue(Files.isRegularFile(destination.staging().resolve("fixture.json")));
        try (var entries = Files.list(temporary)) {
            assertEquals(2, entries.count(), "the rejected candidate must be removed");
        }
    }

    @Test
    void concurrentJobsReserveDifferentStagingAndArchivePaths(@TempDir Path temporary) throws Exception {
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> {
                start.await();
                return ExportArchives.reserve(temporary, "same-millisecond");
            });
            var second = workers.submit(() -> {
                start.await();
                return ExportArchives.reserve(temporary, "same-millisecond");
            });
            start.countDown();

            var a = first.get(5, TimeUnit.SECONDS);
            var b = second.get(5, TimeUnit.SECONDS);
            assertNotEquals(a.staging(), b.staging());
            assertNotEquals(a.archive(), b.archive());
            assertTrue(Files.isDirectory(a.staging()) && Files.isDirectory(b.staging()));
            assertTrue(Files.notExists(a.archive()) && Files.notExists(b.archive()));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void cancellationDuringPayloadCopyDiscardsOnlyPrivateArchive(@TempDir Path temporary) throws Exception {
        var destination = ExportArchives.reserve(temporary, "cancelled");
        Path data = Files.write(destination.staging().resolve("fixture.dat"), new byte[1024 * 1024]);
        var polls = new AtomicInteger();

        assertThrows(CancellationException.class, () -> ExportArchives.prepare(
            destination.staging(), destination.archive(), "{}", () -> polls.incrementAndGet() == 8
        ));
        assertFalse(Files.exists(destination.archive()));
        assertEquals(1024 * 1024, Files.size(data));
        try (var entries = Files.list(temporary)) {
            assertEquals(1, entries.count(), "only staging should remain");
        }
    }

    @Test
    void namesOneOrSeveralModNamespaces() {
        assertEquals("create", ExportArchives.modSetName(Set.of("create")));
        assertEquals("create+mekanism", ExportArchives.modSetName(Set.of("mekanism", "create")));
        assertEquals("unsafe_name", ExportArchives.modSetName(Set.of("unsafe/name")));
        assertEquals("all-mods", ExportArchives.modSetName(Set.of()));
    }
}
