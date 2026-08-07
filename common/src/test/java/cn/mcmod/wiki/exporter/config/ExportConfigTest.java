package cn.mcmod.wiki.exporter.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
        "mcmods.export.modules",
        "mcmods.export.tickBudgetMs",
        "mcmods.export.exit",
        "mcmods.export.config",
        "mcmods.export.recipeTypes.exclude",
        "mcmods.export.recipeTypes.templateOnly"
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

    @Test
    void parsesOptionalModuleSelection(@TempDir Path gameDirectory) {
        System.setProperty("mcmods.export.modules", "registries, tags, item-icons");

        ExportConfig config = ExportConfig.fromSystemProperties(gameDirectory, false, "examplemod", ExportProfile.ALL);

        assertEquals(List.of("registries", "tags", "item_icons"), List.copyOf(config.modules()));
        assertTrue(config.moduleEnabled("registries"));
        assertFalse(config.moduleEnabled("worldgen"));
    }

    @Test
    void loadsRecipeTypePoliciesFromFileAndLetsJvmPropertiesOverride(@TempDir Path gameDirectory) throws Exception {
        Path configFile = gameDirectory.resolve("config/mcmods_exporter.properties");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
            mcmods.export.recipeTypes.exclude=create:automatic_shaped,minecraft:smithing
            mcmods.export.recipeTypes.templateOnly=minecraft:anvil,minecraft:smithing
            """);

        ExportConfig fromFile = ExportConfig.fromSystemProperties(
            gameDirectory,
            false,
            "minecraft",
            ExportProfile.ALL
        );

        assertTrue(fromFile.excludesRecipeType("create:automatic_shaped"));
        assertTrue(fromFile.excludesRecipeType("minecraft:smithing"));
        assertTrue(fromFile.exportsRecipeTypeTemplatesOnly("minecraft:anvil"));
        assertFalse(fromFile.exportsRecipeTypeTemplatesOnly("minecraft:smithing"));
        assertEquals(configFile, fromFile.configFile());

        System.setProperty("mcmods.export.recipeTypes.templateOnly", "minecraft:stonecutting");
        ExportConfig overridden = ExportConfig.fromSystemProperties(
            gameDirectory,
            false,
            "minecraft",
            ExportProfile.ALL
        );
        assertTrue(overridden.exportsRecipeTypeTemplatesOnly("minecraft:stonecutting"));
        assertFalse(overridden.exportsRecipeTypeTemplatesOnly("minecraft:anvil"));
    }
}
