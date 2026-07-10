package cn.mcmod.wiki.exporter.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExportArchivesTest {
    @Test
    void createsRootedArchiveAndDeletesStagingDirectory(@TempDir Path temporary) throws Exception {
        Path staging = temporary.resolve(".create-20260710.staging");
        Files.createDirectories(staging.resolve("registries"));
        Files.writeString(staging.resolve("manifest.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("registries/items.json"), "{}", StandardCharsets.UTF_8);
        Path archive = temporary.resolve("create-20260710.zip");

        ExportArchives.createZip(staging, archive);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertNotNull(zip.getEntry("manifest.json"));
            assertNotNull(zip.getEntry("registries/items.json"));
            assertEquals(2, zip.size());
        }
        ExportArchives.deleteTree(staging);
        assertFalse(Files.exists(staging));
    }

    @Test
    void namesOneOrSeveralModNamespaces() {
        assertEquals("create", ExportArchives.modSetName(Set.of("create")));
        assertEquals("create+mekanism", ExportArchives.modSetName(Set.of("mekanism", "create")));
        assertEquals("unsafe_name", ExportArchives.modSetName(Set.of("unsafe/name")));
        assertEquals("all-mods", ExportArchives.modSetName(Set.of()));
    }
}
