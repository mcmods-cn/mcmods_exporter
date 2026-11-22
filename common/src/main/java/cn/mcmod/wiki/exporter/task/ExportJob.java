package cn.mcmod.wiki.exporter.task;

import cn.mcmod.wiki.exporter.config.ExportConfig;
import cn.mcmod.wiki.exporter.util.ExportArchives;
import cn.mcmod.wiki.exporter.util.JsonFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.function.Consumer;

final class ExportJob {
    private static final DateTimeFormatter DIRECTORY_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT);

    private final ExportConfig config;
    private final Consumer<Component> notifier;
    private final Deque<ExportStage> stages;
    private final int stageCount;
    private final ClientLevel sourceLevel;
    private final ClientPacketListener sourceConnection;
    private final ResourceManager sourceServerResources;
    private final ExportContext context;
    private final Path archive;
    private final ExportPackage packaging;
    private Path output;
    private boolean stageStarted;
    private boolean cancelled;
    private Phase phase = Phase.COLLECTING;
    private long workSteps;

    ExportJob(Minecraft minecraft, ExportConfig config, Consumer<Component> notifier) throws IOException {
        this.config = config;
        this.notifier = notifier;
        this.sourceLevel = minecraft.level;
        this.sourceConnection = minecraft.getConnection();
        var server = minecraft.getSingleplayerServer();
        this.sourceServerResources = server == null ? null : server.getResourceManager();
        String base = ExportArchives.modSetName(config.namespaces()) + "-" + DIRECTORY_TIME.format(LocalDateTime.now());
        ExportArchives.Destination paths = ExportArchives.reserve(config.outputRoot(), base);
        Path root = paths.staging();
        this.archive = paths.archive();
        this.output = root;
        ExportReport report = new ExportReport(minecraft.gameDirectory.toPath().resolve("logs"), root, archive.getFileName().toString());
        try {
            this.context = new ExportContext(minecraft, config, root, report);
            ExportPipeline.Selection pipeline = ExportPipeline.create(config);
            this.stages = new ArrayDeque<>(pipeline.stages());
            this.stageCount = stages.size();
            report.modulesSelected(pipeline.modules());
            this.packaging = new ExportPackage(paths);
        } catch (RuntimeException failure) {
            report.error("job", "create", failure);
            report.closeDiagnostics();
            throw failure;
        }
    }

    void tick() {
        if (isFinished()) {
            return;
        }
        if (phase == Phase.PUBLISHED || phase == Phase.RETAINING) {
            if (packaging.finalized()) {
                finish(false);
            }
            return;
        }
        if (cancelled || !sourceIsActive()) {
            retainCancelledExport();
            return;
        }
        if (phase == Phase.PACKAGING) {
            if (packaging.prepared()) {
                publishArchive();
            }
            return;
        }
        long deadline = System.nanoTime() + config.tickBudgetNanos();
        do {
            if (stages.isEmpty()) {
                context.report().finish(false);
                packaging.prepare(manifest(archive.getFileName().toString()));
                phase = Phase.PACKAGING;
                notify(Component.literal("[mcmods_exporter] 正在打包。"));
                return;
            }
            ExportStage stage = stages.getFirst();
            long stepStart = System.nanoTime();
            try {
                if (!stageStarted) {
                    stageStarted = true;
                    context.report().stageStarted(stage.id());
                    stage.start(context);
                    notify(Component.literal("[mcmods_exporter] 开始: " + stage.id()));
                }
                StageResult result = stage.step(context);
                workSteps++;
                if (result == StageResult.DONE) {
                    context.report().stageFinished(stage.id());
                    notify(Component.literal("[mcmods_exporter] 完成: " + stage.id()));
                    stages.removeFirst();
                    stageStarted = false;
                } else if (workSteps % 100 == 0 && !stage.progress().isBlank()) {
                    notify(Component.literal("[mcmods_exporter] " + stage.id() + " " + stage.progress()));
                }
            } catch (Throwable throwable) {
                ExportReport.rethrowFatal(throwable);
                context.report().stageFailed(stage.id(), throwable);
                notify(Component.literal("[mcmods_exporter] 阶段失败，已记录并继续: " + stage.id()));
                stages.removeFirst();
                stageStarted = false;
            } finally {
                context.report().recordStep(stage.id(), System.nanoTime() - stepStart);
            }
        } while (System.nanoTime() < deadline);
    }

    private boolean sourceIsActive() {
        var minecraft = context.minecraft();
        var server = minecraft.getSingleplayerServer();
        return minecraft.level == sourceLevel && minecraft.getConnection() == sourceConnection
            && !(minecraft.getOverlay() instanceof LoadingOverlay)
            && (server == null ? null : server.getResourceManager()) == sourceServerResources;
    }

    private void publishArchive() {
        try {
            packaging.publish();
            output = archive;
            phase = Phase.PUBLISHED;
        } catch (IOException failure) {
            context.report().packageFailed(failure);
            retainStaging();
        }
    }

    private void retainCancelledExport() {
        cancelled = true;
        context.report().finish(true);
        retainStaging();
    }

    private void retainStaging() {
        packaging.retain(manifest(null));
        phase = Phase.RETAINING;
    }

    private String manifest(String archiveName) {
        return JsonFiles.GSON.toJson(context.report().manifest(config, context.languages(), archiveName));
    }

    private void finish(boolean clientStopping) {
        boolean packaged = phase == Phase.PUBLISHED;
        try {
            packaging.awaitFinalization();
        } catch (IOException failure) {
            context.report().error("package", packaged ? "staging_cleanup" : "manifest", failure);
        } finally {
            phase = Phase.FINISHED;
            context.report().closeDiagnostics();
        }
        if (packaged) {
            notify(Component.literal("[mcmods_exporter] 导出完成: " + archive.toAbsolutePath()));
            if (!clientStopping && config.exitAfterExport() && sourceIsActive()) {
                context.minecraft().stop();
            }
        } else if (cancelled) {
            notify(Component.literal("[mcmods_exporter] 已取消: " + context.root().toAbsolutePath()));
        } else {
            notify(Component.literal("[mcmods_exporter] 无法生成 ZIP。请检查输出目录和本地游戏日志。"));
        }
    }

    boolean cancel() {
        if (phase != Phase.COLLECTING && phase != Phase.PACKAGING) {
            return false;
        }
        cancelled = true;
        packaging.requestCancellation();
        return true;
    }

    void clientStopping() {
        if (cancel()) {
            retainCancelledExport();
        }
        if (!isFinished()) {
            finish(true);
        }
    }

    boolean isFinished() {
        return phase == Phase.FINISHED;
    }

    String status() {
        return switch (phase) {
            case COLLECTING -> stages.isEmpty() ? "running stage=packaging"
                : "running stage=" + stages.getFirst().id()
                    + " (" + (stageCount - stages.size() + 1) + "/" + stageCount + ")";
            case PACKAGING -> "running stage=packaging";
            case PUBLISHED -> "running stage=cleanup";
            case RETAINING -> "running stage=retaining_staging";
            case FINISHED -> "idle";
        };
    }

    Path output() {
        return output;
    }

    private void notify(Component message) {
        try {
            notifier.accept(message);
        } catch (Throwable ignored) {
        }
    }

    private enum Phase {
        COLLECTING, PACKAGING, PUBLISHED, RETAINING, FINISHED
    }
}
