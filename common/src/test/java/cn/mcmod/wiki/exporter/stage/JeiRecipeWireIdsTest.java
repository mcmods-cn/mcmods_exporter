package cn.mcmod.wiki.exporter.stage;

import mezz.jei.api.recipe.RecipeType;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ResourceLock(Resources.LOCALE)
class JeiRecipeWireIdsTest {
    @Test
    void generatedRecipeIdUsesAsciiOnArabicSystems() {
        Locale original = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-SA"));
            var category = new RecipeType<>(new ResourceLocation("fixture", "generated"), Object.class);
            assertEquals("fixture:generated/__generated/recipe_000027",
                JeiRecipeGuiStage.generatedRecipeId(category, 27).toString());
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, original);
        }
    }
}
