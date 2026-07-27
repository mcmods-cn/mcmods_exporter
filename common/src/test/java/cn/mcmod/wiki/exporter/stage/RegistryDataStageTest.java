package cn.mcmod.wiki.exporter.stage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryDataStageTest {
    @Test
    void keyMappingOwnershipUsesWholeTranslationKeySegments() {
        assertTrue(RegistryDataStage.keyTranslationMatchesNamespaces("key.mekanism.mode", List.of("mekanism")));
        assertTrue(RegistryDataStage.keyTranslationMatchesNamespaces("key.categories.create", List.of("create")));
        assertFalse(RegistryDataStage.keyTranslationMatchesNamespaces("key.some_create_addon.mode", List.of("create")));
        assertFalse(RegistryDataStage.keyTranslationMatchesNamespaces("key.minecraft.jump", List.of("mekanism")));
    }

    @Test
    void vanillaKeyMappingsAreIncludedWhenExportingMinecraft() {
        assertTrue(RegistryDataStage.keyMappingMatchesSelection(
            "key.jump",
            "key.categories.movement",
            List.of("minecraft"),
            true
        ));
        assertFalse(RegistryDataStage.keyMappingMatchesSelection(
            "key.mekanism.mode",
            "key.categories.mekanism",
            List.of("minecraft"),
            false
        ));
    }

    @Test
    void modKeyMappingsRemainSelectedByTranslationNamespace() {
        assertTrue(RegistryDataStage.keyMappingMatchesSelection(
            "key.mekanism.mode",
            "key.categories.mekanism",
            List.of("mekanism"),
            false
        ));
        assertFalse(RegistryDataStage.keyMappingMatchesSelection(
            "key.mekanism.mode",
            "key.categories.mekanism",
            List.of("create"),
            false
        ));
    }
}
