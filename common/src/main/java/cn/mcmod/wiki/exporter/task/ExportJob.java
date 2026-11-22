package cn.mcmod.wiki.exporter.task;

import cn.mcmod.wiki.exporter.config.ExportConfig;
import cn.mcmod.wiki.exporter.util.ExportArchives;
import cn.mcmod.wiki.exporter.util.JsonFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

final class ExportJob {
    private static final DateTimeFormatter DIRECTORY_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final ExportConfig config;
    private final Consumer<Component> notifier;
    private final Deque<ExportStage> stages;
    private final int stageCount;
    private final ClientLevel sourceLevel;
    private final ClientPacketListener sourceConnection;
    private final ResourceManager sourceServerResources;
    private final ExportContext context;
    private final Path archive;
    private Path output;
    private boolean stageStarted;
    private boolean cancelled;
    private boolean finished;
    private long workSteps;

    ExportJob(Minecraft minecraft, ExportConfig config, Consumer<Component> notifier) throws IOException {
        this.config = config;
        this.notifier = notifier;
        this.sourceLevel = minecraft.level;
        this.sourceConnection = minecraft.getConnection();
        var server = minecraft.getSingleplayerServer();
        this.sourceServerResources = server == null ? null : server.getResourceManager();
        PackagePaths paths = uniqueResultPaths(config);
        Path root = paths.staging();
        this.archive = paths.archive();
        this.output = root;
        ExportReport report = new ExportReport(minecraft.gameDirectory.toPath().resolve("logs"), root, archive.getFileName().toString());
        try {
            Files.createDirectories(root);
            this.context = new ExportContext(minecraft, config, root, report);
            ExportPipeline.Selection pipeline = ExportPipeline.create(config);
            this.stages = new ArrayDeque<>(pipeline.stages());
            this.stageCount = stages.size();
            report.modulesSelected(pipeline.modules());
        } catch (IOException | RuntimeException failure) {
            report.error("job", "create", failure);
            report.closeDiagnostics();
            throw failure;
        }
    }

    private static PackagePaths uniqueResultPaths(ExportConfig config) {
        String base = ExportArchives.modSetName(config.namespaces()) + "-" + DIRECTORY_TIME.format(LocalDateTime.now());
        Path candidateArchive = config.outputRoot().resolve(base + ".zip");
        Path candidateStaging = config.outputRoot().resolve("." + base + ".staging");
        int suffix = 2;
        while (Files.exists(candidateArchive) || Files.exists(candidateStaging)) {
            String suffixed = base + "-" + suffix++;
            candidateArchive = config.outputRoot().resolve(suffixed + ".zip");
            candidateStaging = config.outputRoot().resolve("." + suffixed + ".staging");
        }
        return new PackagePaths(candidateStaging, candidateArchive);
    }

    void tick() {
        if (finished) {
            return;
        }
        var minecraft = context.minecraft();
        var server = minecraft.getSingleplayerServer();
        if (cancelled || minecraft.level != sourceLevel || minecraft.getConnection() != sourceConnection
            || minecraft.getOverlay() instanceof LoadingOverlay
            || (server == null ? null : server.getResourceManager()) != sourceServerResources) {
            finish(true);
            return;
        }
        long deadline = System.nanoTime() + config.tickBudgetNanos();
        do {
            if (stages.isEmpty()) {
                finish(false);
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

    private void finish(boolean wasCancelled) {
        if (finished) {
            return;
        }
        finished = true;
        context.report().finish(wasCancelled);
        boolean packagedSuccessfully = false;
        try {
            JsonFiles.write(
                context.root().resolve("manifest.json"),
                context.report().manifest(config, context.languages(), wasCancelled ? null : archive.getFileName().toString())
            );
            if (wasCancelled) {
                notify(Component.literal("[mcmods_exporter] 已取消: " + context.root().toAbsolutePath()));
            } else {
                ExportArchives.createZip(context.root(), archive);
                output = archive;
                try {
                    ExportArchives.deleteTree(context.root());
                } catch (IOException cleanupFailure) {
                    context.report().error("package", "staging_cleanup", cleanupFailure);
                }
                notify(Component.literal("[mcmods_exporter] 导出完成: " + archive.toAbsolutePath()));
                packagedSuccessfully = true;
            }
        } catch (IOException exception) {
            context.report().packageFailed(exception);
            try {
                JsonFiles.write(context.root().resolve("manifest.json"), context.report().manifest(config, context.languages(), null));
            } catch (IOException manifestFailure) {
                context.report().error("package", "manifest", manifestFailure);
            }
            notify(Component.literal("[mcmods_exporter] 无法生成 ZIP。请检查输出目录和本地游戏日志。"));
        } finally {
            context.report().closeDiagnostics();
        }
        if (!wasCancelled && packagedSuccessfully && config.exitAfterExport()) {
            context.minecraft().stop();
        }
    }

    void cancel() {
        cancelled = true;
    }

    boolean isFinished() {
        return finished;
    }

    String status() {
        if (finished) {
            return "idle";
        }
        String stage = stages.isEmpty() ? "finalizing" : stages.getFirst().id();
        return "running stage=" + stage + " (" + Math.min(stageCount, stageCount - stages.size() + 1) + "/" + stageCount + ")";
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

    private record PackagePaths(Path staging, Path archive) {
    }
}
