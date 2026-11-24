package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.config.ExportConfig;
import cn.mcmod.wiki.exporter.config.ExportProfile;
import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportReport;
import cn.mcmod.wiki.exporter.task.StageResult;
import cn.mcmod.wiki.exporter.util.ExportArchives;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceStageTest {
    @Test
    void visitsOneAssetPerStepAndIndexesTheSortedDependencyClosure(@TempDir Path directory) throws IOException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path pack = directory.resolve("pack");
        write(pack, "minecraft/blockstates/stone.json", "{\"variants\": {\"\": {\"model\": \"example:mix\"}}}");
        write(pack, "minecraft/models/item/torch.json", "{\"parent\": \"example:mix\"}");
        write(pack, "example/models/mix.json", "{\"parent\": \"example:mix\", \"textures\": {\"all\": \"example:shared\"}}");
        write(pack, "example/textures/shared.png", "texture bytes");
        write(pack, "example/textures/shared.png.mcmeta", "{\"animation\": {}}");
        Path output = directory.resolve("out");
        Path indexPath = output.resolve("resources/index.json");
        var lookups = new ArrayList<ResourceLocation>();

        try (var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
            List.of(new PathPackResources("pack", pack, false))) {
            @Override
            public Optional<Resource> getResource(ResourceLocation id) {
                lookups.add(id);
                return super.getResource(id);
            }
        }) {
            var config = ExportConfig.fromSystemProperties(directory, false, "minecraft", ExportProfile.DATA);
            var context = new ExportContext(null, config, output, new ExportReport());
            var stage = new ResourceStage();
            stage.prepare(context, manager);
            assertTrue(lookups.isEmpty());
            StageResult result = StageResult.MORE_WORK;
            for (int step = 0; step < 20_000 && result == StageResult.MORE_WORK; step++) {
                assertFalse(Files.exists(indexPath));
                int before = lookups.size();
                result = stage.step(context);
                // A texture step also checks the presence of its optional .mcmeta.
                assertTrue(lookups.size() - before <= 2);
            }
            assertEquals(StageResult.DONE, result);
        }

        var index = JsonParser.parseString(Files.readString(indexPath)).getAsJsonObject();
        assertEquals(5, index.get("asset_count").getAsInt());
        assertEquals(3, index.get("dependency_count").getAsInt());
        var files = index.getAsJsonArray("files");
        var ids = new ArrayList<String>();
        var reasons = new ArrayList<String>();
        files.forEach(element -> {
            var file = element.getAsJsonObject();
            ids.add(file.get("id").getAsString());
            reasons.add(file.get("inclusion_reason").getAsString());
        });
        assertEquals(List.of("example:models/mix.json", "example:textures/shared.png", "example:textures/shared.png.mcmeta",
            "minecraft:blockstates/stone.json", "minecraft:models/item/torch.json"), ids);
        assertEquals(List.of("referenced_model", "model_texture", "texture_metadata", "registered_block", "registered_item"), reasons);
        assertEquals(1L, lookups.stream().filter(new ResourceLocation("example:models/mix.json")::equals).count());
        assertEquals("texture bytes", Files.readString(output.resolve("assets/example/textures/shared.png")));
    }

    @Test
    void isolatesPackLookupFailuresAndPublishesOnlyFullyCopiedResources(@TempDir Path directory) throws IOException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path pack = directory.resolve("pack");
        write(pack, "minecraft/models/item/torch.json", "{}");
        var destination = ExportArchives.reserve(directory, "resources");
        Path output = destination.staging();
        var stone = new ResourceLocation("minecraft:blockstates/stone.json");
        var torch = new ResourceLocation("minecraft:blockstates/torch.json");
        var packResources = new PathPackResources("pack", pack, false);
        try (var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(packResources)) {
            @Override
            public Optional<Resource> getResource(ResourceLocation id) {
                if (stone.equals(id)) {
                    return Optional.of(new Resource(packResources, () -> new InputStream() {
                        private int remaining = 100;

                        @Override
                        public int read() throws IOException {
                            if (remaining-- > 0) {
                                return 'x';
                            }
                            throw new IOException("pack stream interrupted");
                        }
                    }));
                }
                if (torch.equals(id)) {
                    throw new IllegalStateException("pack lookup unavailable");
                }
                return super.getResource(id);
            }
        }) {
            var config = ExportConfig.fromSystemProperties(directory, false, "minecraft", ExportProfile.DATA);
            var context = new ExportContext(null, config, output, new ExportReport());
            var stage = new ResourceStage();
            stage.prepare(context, manager);
            StageResult result = StageResult.MORE_WORK;
            for (int step = 0; step < 20_000 && result == StageResult.MORE_WORK; step++) {
                result = stage.step(context);
            }
            assertEquals(StageResult.DONE, result);
        }
        var index = JsonParser.parseString(Files.readString(output.resolve("resources/index.json"))).getAsJsonObject();
        assertEquals(1, index.get("asset_count").getAsInt());
        assertEquals("minecraft:models/item/torch.json", index.getAsJsonArray("files")
            .get(0).getAsJsonObject().get("id").getAsString());
        try (var draft = ExportArchives.prepare(output, destination.archive(), "{}", () -> false)) {
            draft.publish();
        }
        try (var zip = new ZipFile(destination.archive().toFile())) {
            assertNull(zip.getEntry("assets/minecraft/blockstates/stone.json"));
            assertEquals(3, zip.size(), "only the manifest, index and complete asset are publishable");
        }
    }

    private static void write(Path pack, String resourcePath, String content) throws IOException {
        Path file = pack.resolve("assets").resolve(resourcePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
