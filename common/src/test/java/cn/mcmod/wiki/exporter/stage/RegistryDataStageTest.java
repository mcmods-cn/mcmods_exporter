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
}
