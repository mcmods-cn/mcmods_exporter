package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.OnceStage;
import cn.mcmod.wiki.exporter.util.ExportPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public final class ResourceStage extends OnceStage {
    public static final String ID = "resources";

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected void run(ExportContext context) throws Exception {
        JsonArray index = new JsonArray();
        int assetCount = 0;
        assetCount += copyGroup(context, context.minecraft().getResourceManager(), "blockstates", "assets", index);
        assetCount += copyGroup(context, context.minecraft().getResourceManager(), "models", "assets", index);
        assetCount += copyGroup(context, context.minecraft().getResourceManager(), "textures", "assets", index);

        JsonObject output = new JsonObject();
        output.addProperty("schema_version", "mcwiki-resources/v1");
        output.addProperty("asset_count", assetCount);
        output.add("files", index);
        context.writeJson("resources/index.json", output);
        context.report().addCount(ID, "assets", assetCount);
    }

    private static int copyGroup(
        ExportContext context,
        ResourceManager manager,
        String prefix,
        String rootFolder,
        JsonArray index
    ) {
        int count = 0;
        Map<ResourceLocation, Resource> resources = manager.listResources(prefix, context::matches);
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation id = entry.getKey();
            Resource resource = entry.getValue();
            Path target = ExportPaths.resourceFile(context.root(), rootFolder, id);
            try {
                Files.createDirectories(target.getParent());
                try (InputStream input = resource.open()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                JsonObject item = new JsonObject();
                item.addProperty("kind", prefix);
                item.addProperty("id", id.toString());
                item.addProperty("path", context.root().relativize(target).toString().replace('\\', '/'));
                index.add(item);
                count++;
            } catch (Exception exception) {
                context.report().error(ID, rootFolder + ":" + id, exception);
            }
        }
        return count;
    }

}
