package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.config.ExportConfig;
import cn.mcmod.wiki.exporter.config.ExportProfile;
import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportReport;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldgenRegistryDefinitionsTest {
    @Test
    void encodesOneEntryPerStepAndKeepsForeignDependenciesOutsideSelectedCatalog(@TempDir Path dir) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var key = ResourceKey.<String>createRegistryKey(new ResourceLocation("fixture", "features"));
        var registry = new MappedRegistry<>(key, Lifecycle.stable(), false);
        Registry.register(registry, new ResourceLocation("fixture", "placed"), "selected");
        Registry.register(registry, new ResourceLocation("base", "feature"), "dependency");
        var access = new RegistryAccess.ImmutableRegistryAccess(Map.of(key, registry.freeze()));
        var calls = new AtomicInteger();
        Codec<String> codec = Codec.STRING.xmap(value -> value, value -> {
            calls.incrementAndGet();
            return value;
        });
        var type = new RegistryDataLoader.RegistryData<>(key, codec);
        var definitions = new WorldgenRegistryDefinitions(access,
            List.<RegistryDataLoader.RegistryData<?>>of(type).iterator(), Set.of("fixture:features"));
        ExportConfig config = ExportConfig.fromSystemProperties(dir, false, "fixture", ExportProfile.DATA);
        var context = new ExportContext(null, config, dir, new ExportReport());

        boolean finished;
        do {
            int before = calls.get();
            finished = definitions.finishedAfterStep(context);
            assertTrue(calls.get() - before <= 1);
        } while (!finished);
        assertEquals(2, calls.get());
        var selected = definitions.selected.get("fixture:features");
        var dependencies = definitions.dependencies.get("fixture:features");
        assertEquals(Set.of("fixture:placed"), selected.keySet());
        assertEquals(Set.of("fixture:placed", "base:feature"), dependencies.keySet());
        assertSame(selected.get("fixture:placed"), dependencies.get("fixture:placed"));
    }
}
