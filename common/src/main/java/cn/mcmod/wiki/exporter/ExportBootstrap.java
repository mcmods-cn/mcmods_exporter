package cn.mcmod.wiki.exporter;

import cn.mcmod.wiki.exporter.compat.jei.McModsJeiPlugin;
import cn.mcmod.wiki.exporter.config.ExportConfig;
import cn.mcmod.wiki.exporter.config.ExportProfile;
import cn.mcmod.wiki.exporter.task.ExportCoordinator;
import dev.architectury.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

public final class ExportBootstrap {
    private static final int READY_TICKS = 40;
    private static final int MAX_RETRY_TICKS = 20 * 60;
    private static boolean automaticStarted;
    private static int ticksUntilAttempt = READY_TICKS;
    private static int retryDelay = READY_TICKS;

    private ExportBootstrap() {
    }

    public static void tick(Minecraft minecraft) {
        ExportCoordinator.get().tick();
        if (automaticStarted || !ExportConfig.autoEnabled()) {
            return;
        }
        if (minecraft.level == null || minecraft.getConnection() == null || minecraft.getOverlay() instanceof LoadingOverlay) {
            ticksUntilAttempt = READY_TICKS;
            retryDelay = READY_TICKS;
            return;
        }
        if (--ticksUntilAttempt > 0) {
            return;
        }
        ticksUntilAttempt = READY_TICKS;
        if (ExportCoordinator.get().isBusy()) {
            return;
        }
        try {
            ExportConfig config = ExportConfig.fromSystemProperties(minecraft.gameDirectory.toPath(), true, null, null);
            if (config.moduleEnabled("jei") && McModsJeiPlugin.runtime() == null) {
                return;
            }
            automaticStarted = ExportCoordinator.get().request(minecraft, config, automaticNotifier(minecraft));
        } catch (RuntimeException exception) {
            McModsExporter.LOGGER.warn("Could not read automatic export configuration", exception);
        }
        if (!automaticStarted) {
            ticksUntilAttempt = retryDelay;
            retryDelay = Math.min(MAX_RETRY_TICKS, retryDelay * 2);
        }
    }

    public static boolean startManual(String namespace, ExportProfile profile, Consumer<Component> notifier) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            notifier.accept(Component.literal("[mcmods_exporter] 请先进入一个世界，确保配方、进度和动态标签已同步。"));
            return false;
        }
        ExportConfig config = ExportConfig.fromSystemProperties(minecraft.gameDirectory.toPath(), false, namespace, profile);
        return ExportCoordinator.get().request(minecraft, config, notifier);
    }

    public static Set<String> availableNamespaces() {
        TreeSet<String> result = new TreeSet<>(Platform.getModIds());
        BuiltInRegistries.ITEM.keySet().forEach(id -> result.add(id.getNamespace()));
        BuiltInRegistries.BLOCK.keySet().forEach(id -> result.add(id.getNamespace()));
        BuiltInRegistries.ENTITY_TYPE.keySet().forEach(id -> result.add(id.getNamespace()));
        result.remove(McModsExporter.MOD_ID);
        return result;
    }

    private static Consumer<Component> automaticNotifier(Minecraft minecraft) {
        return component -> {
            McModsExporter.LOGGER.info(component.getString());
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(component);
            }
        };
    }
}
