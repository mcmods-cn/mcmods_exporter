package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.config.ExportConfig;
import cn.mcmod.wiki.exporter.config.ExportProfile;
import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportReport;
import cn.mcmod.wiki.exporter.task.StageResult;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LanguageStageTest {
    @Test
    void eachStepWritesOneLocaleAndPreservesDependencyAndPackPriority(@TempDir Path dir) throws IOException {
        Path base = dir.resolve("base");
        Path top = dir.resolve("top");
        write(base, "aaa_target", "en_us", "{\"same\":\"base target\",\"base.only\":\"base\"}");
        write(top, "aaa_target", "en_us", "{\"same\":\"top target\"}");
        write(base, "zzz_dependency", "en_us", "{\"same\":\"dependency\",\"dep.only\":\"dependency\"}");
        write(base, "aaa_target", "ru_ru", "{\"same\":\"selected locale\"}");
        write(base, "zzz_dependency", "fr_fr", "{\"dep.only\":\"dependency locale\"}");

        var scans = new AtomicInteger();
        try (var resources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(
            new PathPackResources("base", base, false), new PathPackResources("top", top, false)
        )) {
            @Override
            public Map<ResourceLocation, List<Resource>> listResourceStacks(String path, Predicate<ResourceLocation> filter) {
                scans.incrementAndGet();
                return super.listResourceStacks(path, filter);
            }
        }) {
            ExportConfig config = ExportConfig.fromSystemProperties(dir, false, "aaa_target", ExportProfile.DATA);
            Path out = dir.resolve("export");
            ExportContext context = new ExportContext(null, config, out, new ExportReport());
            LanguageStage stage = new LanguageStage();
            stage.prepare(context, resources);
            assertEquals(List.of("en_us", "ru_ru", "zh_cn"), context.languages());
            assertFalse(Files.exists(out));

            assertEquals(StageResult.MORE_WORK, stage.step(context));
            var english = JsonParser.parseString(Files.readString(out.resolve("translations/en_us.json"))).getAsJsonObject();
            assertEquals(2, english.get("translation_count").getAsInt());
            assertEquals("top target", english.getAsJsonObject("translations").get("same").getAsString());
            assertFalse(english.getAsJsonObject("translations").has("dep.only"));
            assertEquals("dependency", context.translations().get("en_us").get("dep.only"));
            assertFalse(Files.exists(out.resolve("translations/ru_ru.json")));

            assertEquals(StageResult.MORE_WORK, stage.step(context));
            assertEquals(StageResult.MORE_WORK, stage.step(context));
            assertEquals(StageResult.DONE, stage.step(context));
            var index = JsonParser.parseString(Files.readString(out.resolve("translations/languages.json"))).getAsJsonObject();
            assertEquals(3, index.get("count").getAsInt());
            assertEquals(1, scans.get());
        }
    }

    private static void write(Path pack, String namespace, String locale, String json) throws IOException {
        Path dir = Files.createDirectories(pack.resolve("assets").resolve(namespace).resolve("lang"));
        Files.writeString(dir.resolve(locale + ".json"), json);
    }
}
