package cn.mcmod.wiki.exporter.task;

import cn.mcmod.wiki.exporter.util.ExportArchives;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(10)
class ExportPackageTest {
    @Test
    void successfulPublicationReleasesStagingBeforeFinalizationCompletes(@TempDir Path root) throws Exception {
        var destination = ExportArchives.reserve(root, "success");
        Files.writeString(destination.staging().resolve("fixture.json"), "{}");
        var packaging = new ExportPackage(destination);
        packaging.prepare("{\"status\":\"complete\"}");
        waitForPreparation(packaging);

        assertFalse(Files.exists(destination.archive()));
        assertFalse(Files.exists(destination.staging().resolve("manifest.json")));
        packaging.publish();
        packaging.awaitFinalization();

        assertFalse(Files.exists(destination.staging()));
        try (var zip = new ZipFile(destination.archive().toFile())) {
            assertNotNull(zip.getEntry("manifest.json"));
            assertNotNull(zip.getEntry("fixture.json"));
        }
        try (var entries = Files.list(root)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void cancellationAfterPreparationRetainsOnlyOriginalSourcesAndCancelledManifest(@TempDir Path root) throws Exception {
        var destination = ExportArchives.reserve(root, "cancel");
        Path fixture = Files.writeString(destination.staging().resolve("fixture.json"), "{\"fixture\":true}");
        var packaging = new ExportPackage(destination);
        packaging.prepare("{\"status\":\"complete\"}");
        waitForPreparation(packaging);

        String cancelled = "{\"status\":\"cancelled\"}";
        packaging.retain(cancelled);
        packaging.awaitFinalization();

        assertFalse(Files.exists(destination.archive()));
        assertEquals(cancelled, Files.readString(destination.staging().resolve("manifest.json")));
        assertEquals("{\"fixture\":true}", Files.readString(fixture));
        try (var entries = Files.list(root)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void rejectedPublicationKeepsExistingArchiveAndFailedManifest(@TempDir Path root) throws Exception {
        var destination = ExportArchives.reserve(root, "failed");
        Files.writeString(destination.staging().resolve("fixture.json"), "{}");
        var packaging = new ExportPackage(destination);
        packaging.prepare("{\"status\":\"complete\"}");
        waitForPreparation(packaging);

        Files.writeString(destination.archive(), "existing output");
        assertThrows(FileAlreadyExistsException.class, packaging::publish);
        String failed = "{\"status\":\"failed\"}";
        packaging.retain(failed);
        packaging.awaitFinalization();

        assertEquals("existing output", Files.readString(destination.archive()));
        assertEquals(failed, Files.readString(destination.staging().resolve("manifest.json")));
        try (var entries = Files.list(root)) {
            assertEquals(2, entries.count());
        }
    }

    private static void waitForPreparation(ExportPackage packaging) throws InterruptedException {
        while (!packaging.prepared()) {
            Thread.sleep(5);
        }
    }
}
