package cn.mcmod.wiki.exporter.task;

import cn.mcmod.wiki.exporter.McWikiExporter;
import cn.mcmod.wiki.exporter.config.ExportConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.function.Consumer;

public final class ExportCoordinator {
    private static final ExportCoordinator INSTANCE = new ExportCoordinator();

    private ExportJob activeJob;
    private Path lastOutput;

    private ExportCoordinator() {
    }

    public static ExportCoordinator get() {
        return INSTANCE;
    }

    public boolean request(Minecraft minecraft, ExportConfig config, Consumer<Component> notifier) {
        if (activeJob != null && !activeJob.isFinished()) {
            notifier.accept(Component.literal("[mcmods_exporter] 已有导出任务运行中: " + activeJob.status()));
            return false;
        }
        try {
            activeJob = new ExportJob(minecraft, config, notifier);
            lastOutput = activeJob.output();
            notifier.accept(Component.literal("[mcmods_exporter] 任务已创建: " + lastOutput.toAbsolutePath()));
            return true;
        } catch (Exception exception) {
            McWikiExporter.LOGGER.error("Could not create export job", exception);
            notifier.accept(Component.literal("[mcmods_exporter] 无法创建任务: " + exception.getMessage()));
            return false;
        }
    }

    public void tick() {
        if (activeJob != null && !activeJob.isFinished()) {
            activeJob.tick();
            if (activeJob.isFinished()) {
                lastOutput = activeJob.output();
            }
        }
    }

    public String status() {
        if (activeJob == null || activeJob.isFinished()) {
            return "idle" + (lastOutput == null ? "" : " last_output=" + lastOutput.toAbsolutePath());
        }
        return activeJob.status();
    }

    public boolean cancel() {
        if (activeJob == null || activeJob.isFinished()) {
            return false;
        }
        activeJob.cancel();
        return true;
    }
}
