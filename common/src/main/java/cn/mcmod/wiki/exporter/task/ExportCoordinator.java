package cn.mcmod.wiki.exporter.task;

import cn.mcmod.wiki.exporter.McModsExporter;
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

    public boolean isBusy() {
        return activeJob != null && !activeJob.isFinished();
    }

    public boolean request(Minecraft minecraft, ExportConfig config, Consumer<Component> notifier) {
        if (isBusy()) {
            notifier.accept(Component.literal("[mcmods_exporter] 已有导出任务运行中: " + activeJob.status()));
            return false;
        }
        try {
            activeJob = new ExportJob(minecraft, config, notifier);
            lastOutput = activeJob.output();
            notifier.accept(Component.literal("[mcmods_exporter] 任务已创建: " + lastOutput.toAbsolutePath()));
            return true;
        } catch (Exception exception) {
            McModsExporter.LOGGER.warn("Could not create export job ({})", exception.getClass().getSimpleName());
            notifier.accept(Component.literal("[mcmods_exporter] 无法创建任务。请检查输出目录和本地游戏日志。"));
            return false;
        }
    }

    public void tick() {
        if (isBusy()) {
            activeJob.tick();
            releaseFinishedJob();
        }
    }

    public String status() {
        if (!isBusy()) {
            return "idle" + (lastOutput == null ? "" : " last_output=" + lastOutput.toAbsolutePath());
        }
        return activeJob.status();
    }

    /** The client may have no further ticks after the loader's stopping event. */
    public void clientStopping() {
        if (isBusy()) {
            activeJob.clientStopping();
            releaseFinishedJob();
        }
    }

    public boolean cancel() {
        return isBusy() && activeJob.cancel();
    }

    private void releaseFinishedJob() {
        if (activeJob != null && activeJob.isFinished()) {
            lastOutput = activeJob.output();
            activeJob = null;
        }
    }
}
