package cn.mcmod.wiki.exporter.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportConfigTest {
    private static final List<String> PROPERTIES = List.of(
        "mcmods.export.output",
        "mcmods.export.namespaces",
        "mcmods.export.languages",
        "mcmods.export.profile",
        "mcmods.export.sizes",
        "mcmods.export.tickBudgetMs",
        "mcmods.export.exit"
    );

    @AfterEach
    void clearProperties() {
        PROPERTIES.forEach(System::clearProperty);
    }

    @Test
    void manualOverrideKeepsRequiredImageSizes(@TempDir Path gameDirectory) {
        System.setProperty("mcmods.export.sizes", "64,256");
        System.setProperty("mcmods.export.languages", "zh_cn,ja_jp");

        ExportConfig config = ExportConfig.fromSystemProperties(gameDirectory, false, "examplemod", ExportProfile.IMAGES);

        assertEquals(List.of(32, 64, 128, 256), config.imageSizes());
        assertEquals(List.of("zh_cn", "ja_jp", "en_us"), config.languages());
        assertTrue(config.matchesNamespace("examplemod"));
        assertFalse(config.matchesNamespace("minecraft"));
        assertEquals(ExportProfile.IMAGES, config.profile());
    }

    @Test
    void allNamespaceModeExcludesOnlyExporter(@TempDir Path gameDirectory) {
        ExportConfig config = ExportConfig.fromSystemProperties(gameDirectory, false, "all", ExportProfile.ALL);

        assertTrue(config.namespaces().isEmpty());
        assertTrue(config.matchesNamespace("minecraft"));
        assertTrue(config.matchesNamespace("examplemod"));
        assertFalse(config.matchesNamespace("mcmods_exporter"));
    }

    @Test
    void relativeOutputIsResolvedAgainstGameDirectory(@TempDir Path gameDirectory) {
        System.setProperty("mcmods.export.output", "exports/wiki");

        ExportConfig config = ExportConfig.fromSystemProperties(gameDirectory, true, "examplemod", ExportProfile.DATA);

        assertEquals(gameDirectory.resolve("exports/wiki").normalize(), config.outputRoot());
        assertFalse(config.exitAfterExport());
    }
}
