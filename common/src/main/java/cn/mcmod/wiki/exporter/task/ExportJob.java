package cn.mcmod.wiki.exporter.task;

import cn.mcmod.wiki.exporter.McModsExporter;
import cn.mcmod.wiki.exporter.config.ExportConfig;
import cn.mcmod.wiki.exporter.stage.AdvancementStage;
import cn.mcmod.wiki.exporter.stage.BlockIconStage;
import cn.mcmod.wiki.exporter.stage.EntityModelStage;
import cn.mcmod.wiki.exporter.stage.EntityRenderStage;
import cn.mcmod.wiki.exporter.stage.ItemIconStage;
import cn.mcmod.wiki.exporter.stage.JeiIngredientStage;
import cn.mcmod.wiki.exporter.stage.JeiRecipeGuiStage;
import cn.mcmod.wiki.exporter.stage.LanguageStage;
import cn.mcmod.wiki.exporter.stage.PotionIconStage;
import cn.mcmod.wiki.exporter.stage.RecipeDataStage;
import cn.mcmod.wiki.exporter.stage.RegistryDataStage;
import cn.mcmod.wiki.exporter.stage.ResourceStage;
import cn.mcmod.wiki.exporter.stage.TagStage;
import cn.mcmod.wiki.exporter.util.ExportArchives;
import cn.mcmod.wiki.exporter.util.JsonFiles;
import dev.architectury.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class ExportJob {
    private static final DateTimeFormatter DIRECTORY_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final ExportConfig config;
    private final Consumer<Component> notifier;
    private final List<ExportStage> stages;
    private final ExportContext context;
    private final Path archive;
    private Path output;
    private int stageIndex;
    private boolean stageStarted;
    private boolean cancelled;
    private boolean finished;
    private long workSteps;

    ExportJob(Minecraft minecraft, ExportConfig config, Consumer<Component> notifier) throws IOException {
        this.config = config;
        this.notifier = notifier;
        Files.createDirectories(config.outputRoot());
        PackagePaths paths = uniqueResultPaths(config);
        Path root = paths.staging();
        this.archive = paths.archive();
        this.output = root;
        Files.createDirectories(root);
        ExportReport report = new ExportReport();
        this.context = new ExportContext(minecraft, config, root, report);
        this.stages = createStages(config);
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

    private static List<ExportStage> createStages(ExportConfig config) {
        List<ExportStage> result = new ArrayList<>();
        // Every profile can emit textual metadata (notably JEI category and ingredient names),
        // so language discovery must always run before any other stage.
        result.add(new LanguageStage());
        if (config.profile().includesData()) {
            result.add(new RegistryDataStage());
            result.add(new TagStage());
            result.add(new RecipeDataStage());
            result.add(new AdvancementStage());
            result.add(new ResourceStage());
            result.add(new EntityModelStage());
            if (Platform.isModLoaded("jei") && !config.profile().includesImages()) {
                result.add(new JeiIngredientStage(false));
            }
        }
        if (config.profile().includesImages()) {
            result.add(new ItemIconStage());
            result.add(new BlockIconStage());
            result.add(new PotionIconStage());
            if (Platform.isModLoaded("jei")) {
                result.add(new JeiIngredientStage(true));
                result.add(new JeiRecipeGuiStage());
            }
            result.add(new EntityRenderStage());
        }
        return result;
    }

    void tick() {
        if (finished) {
            return;
        }
        if (cancelled) {
            finish(true);
            return;
        }
        long deadline = System.nanoTime() + config.tickBudgetNanos();
        do {
            if (stageIndex >= stages.size()) {
                finish(false);
                return;
            }
            ExportStage stage = stages.get(stageIndex);
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
                    stageIndex++;
                    stageStarted = false;
                } else if (workSteps % 100 == 0 && !stage.progress().isBlank()) {
                    notify(Component.literal("[mcmods_exporter] " + stage.id() + " " + stage.progress()));
                }
            } catch (Throwable throwable) {
                McModsExporter.LOGGER.error("Export stage {} failed", stage.id(), throwable);
                context.report().stageFailed(stage.id(), throwable);
                notify(Component.literal("[mcmods_exporter] 阶段失败，已记录并继续: " + stage.id()));
                stageIndex++;
                stageStarted = false;
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
                    McModsExporter.LOGGER.warn("Could not remove export staging directory {}", context.root(), cleanupFailure);
                }
                notify(Component.literal("[mcmods_exporter] 导出完成: " + archive.toAbsolutePath()));
                packagedSuccessfully = true;
            }
        } catch (IOException exception) {
            McModsExporter.LOGGER.error("Could not write export package", exception);
            context.report().error("package", "zip", exception);
            try {
                JsonFiles.write(context.root().resolve("manifest.json"), context.report().manifest(config, context.languages(), null));
            } catch (IOException ignored) {
            }
            notify(Component.literal("[mcmods_exporter] 无法生成 ZIP: " + exception.getMessage()));
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
        String stage = stageIndex < stages.size() ? stages.get(stageIndex).id() : "finalizing";
        return "running stage=" + stage + " (" + (stageIndex + 1) + "/" + stages.size() + ")";
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
