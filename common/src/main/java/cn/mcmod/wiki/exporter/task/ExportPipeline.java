package cn.mcmod.wiki.exporter.task;

import cn.mcmod.wiki.exporter.config.ExportConfig;
import cn.mcmod.wiki.exporter.stage.AdvancementStage;
import cn.mcmod.wiki.exporter.stage.BlockIconStage;
import cn.mcmod.wiki.exporter.stage.BlockEntityModelStage;
import cn.mcmod.wiki.exporter.stage.CompatibilityStage;
import cn.mcmod.wiki.exporter.stage.ContentAssociationStage;
import cn.mcmod.wiki.exporter.stage.EntityModelStage;
import cn.mcmod.wiki.exporter.stage.EntityRenderStage;
import cn.mcmod.wiki.exporter.stage.ItemIconStage;
import cn.mcmod.wiki.exporter.stage.JeiIngredientStage;
import cn.mcmod.wiki.exporter.stage.JeiRecipeGuiStage;
import cn.mcmod.wiki.exporter.stage.LanguageStage;
import cn.mcmod.wiki.exporter.stage.MobEffectIconStage;
import cn.mcmod.wiki.exporter.stage.RecipeDataStage;
import cn.mcmod.wiki.exporter.stage.RegistryDataStage;
import cn.mcmod.wiki.exporter.stage.ResourceStage;
import cn.mcmod.wiki.exporter.stage.TagStage;
import cn.mcmod.wiki.exporter.stage.TradeDataStage;
import cn.mcmod.wiki.exporter.stage.WorldgenDataStage;
import dev.architectury.platform.Platform;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Builds the ordered export pipeline from independently selectable modules. */
public final class ExportPipeline {
    private static final List<ModuleDefinition> MODULES = List.of(
        module("languages", ignored -> true, ignored -> List.of(new LanguageStage())),
        module("compatibility", config -> config.profile().includesData(), ignored -> List.of(new CompatibilityStage())),
        module("registries", config -> config.profile().includesData(), ignored -> List.of(new RegistryDataStage())),
        module("associations", config -> config.profile().includesData(), ignored -> List.of(new ContentAssociationStage())),
        module("trades", config -> config.profile().includesData(), ignored -> List.of(new TradeDataStage())),
        module("tags", config -> config.profile().includesData(), ignored -> List.of(new TagStage())),
        module("recipes", config -> config.profile().includesData(), ignored -> List.of(new RecipeDataStage())),
        module("advancements", config -> config.profile().includesData(), ignored -> List.of(new AdvancementStage())),
        module("worldgen", config -> config.profile().includesData(), ignored -> List.of(new WorldgenDataStage())),
        module("resources", config -> config.profile().includesData(), ignored -> List.of(new ResourceStage())),
        module("block_entity_models", config -> config.profile().includesData(), ignored -> List.of(new BlockEntityModelStage())),
        module("entity_models", config -> config.profile().includesData(), ignored -> List.of(new EntityModelStage())),
        module("item_icons", config -> config.profile().includesImages(), ignored -> List.of(new ItemIconStage())),
        module("block_icons", config -> config.profile().includesImages(), ignored -> List.of(new BlockIconStage())),
        module("effect_icons", config -> config.profile().includesImages(), ignored -> List.of(new MobEffectIconStage())),
        module("jei", ExportPipeline::jeiEnabled, ExportPipeline::jeiStages),
        module("entity_renders", config -> config.profile().includesImages(), ignored -> List.of(new EntityRenderStage()))
    );

    private ExportPipeline() {
    }

    public static Selection create(ExportConfig config) {
        List<ExportStage> stages = new ArrayList<>();
        Set<String> selectedModules = new LinkedHashSet<>();
        for (ModuleDefinition module : MODULES) {
            // Languages are required by all textual and JEI exports.
            if (!"languages".equals(module.id()) && !config.moduleEnabled(module.id())) {
                continue;
            }
            if (!module.available().test(config)) {
                continue;
            }
            List<ExportStage> moduleStages = module.factory().apply(config);
            if (moduleStages.isEmpty()) {
                continue;
            }
            selectedModules.add(module.id());
            stages.addAll(moduleStages);
        }
        return new Selection(List.copyOf(stages), List.copyOf(selectedModules));
    }

    private static boolean jeiEnabled(ExportConfig config) {
        return Platform.isModLoaded("jei")
            && (config.profile().includesData() || config.profile().includesImages());
    }

    private static List<ExportStage> jeiStages(ExportConfig config) {
        List<ExportStage> stages = new ArrayList<>();
        stages.add(new JeiIngredientStage(config.profile().includesImages()));
        if (config.profile().includesImages()) {
            stages.add(new JeiRecipeGuiStage());
        }
        return stages;
    }

    private static ModuleDefinition module(
        String id,
        Predicate<ExportConfig> available,
        Function<ExportConfig, List<ExportStage>> factory
    ) {
        return new ModuleDefinition(id, available, factory);
    }

    public record Selection(List<ExportStage> stages, List<String> modules) {
    }

    private record ModuleDefinition(
        String id,
        Predicate<ExportConfig> available,
        Function<ExportConfig, List<ExportStage>> factory
    ) {
    }
}
