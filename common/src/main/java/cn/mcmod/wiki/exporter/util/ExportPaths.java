package cn.mcmod.wiki.exporter.util;

import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;

public final class ExportPaths {
    private ExportPaths() {
    }

    public static Path idFile(Path root, String category, Integer size, ResourceLocation id, String extension) {
        Path base = size == null ? root.resolve(category) : root.resolve(category).resolve(Integer.toString(size));
        return safeResolve(base.resolve(id.getNamespace()), id.getPath() + extension);
    }

    public static Path resourceFile(Path root, String rootFolder, ResourceLocation id) {
        return safeResolve(root.resolve(rootFolder).resolve(id.getNamespace()), id.getPath());
    }

    public static Path safeResolve(Path root, String relative) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative.replace('\\', '/')).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Unsafe export path: " + relative);
        }
        return resolved;
    }
}
