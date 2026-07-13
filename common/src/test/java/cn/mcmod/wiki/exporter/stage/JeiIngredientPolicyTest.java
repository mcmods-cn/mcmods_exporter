package cn.mcmod.wiki.exporter.stage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiIngredientPolicyTest {
    @Test
    void keepsAndClassifiesAllFourMekanismChemicalTypes() {
        assertTrue(JeiIngredientPolicy.includeInCatalog("gas", "mekanism.api.chemical.gas.GasStack"));
        assertTrue(JeiIngredientPolicy.includeInCatalog("infusion", "mekanism.api.chemical.infuse.InfusionStack"));
        assertTrue(JeiIngredientPolicy.includeInCatalog("pigment", "mekanism.api.chemical.pigment.PigmentStack"));
        assertTrue(JeiIngredientPolicy.includeInCatalog("slurry", "mekanism.api.chemical.slurry.SlurryStack"));
        assertEquals("gas", JeiIngredientPolicy.mekanismChemicalType("mekanism.api.chemical.gas.GasStack"));
        assertEquals("infuse_type", JeiIngredientPolicy.mekanismChemicalType("mekanism.api.chemical.infuse.InfusionStack"));
        assertEquals("pigment", JeiIngredientPolicy.mekanismChemicalType("mekanism.api.chemical.pigment.PigmentStack"));
        assertEquals("slurry", JeiIngredientPolicy.mekanismChemicalType("mekanism.api.chemical.slurry.SlurryStack"));
    }

    @Test
    void identifiesForgeAndFabricFluidIngredientTypes() {
        assertEquals("fluid", JeiIngredientPolicy.kind("fluid_stack", "net.minecraftforge.fluids.FluidStack"));
        assertEquals("fluid", JeiIngredientPolicy.kind(
            "fluid_stack",
            "mezz.jei.api.fabric.ingredients.fluids.IJeiFluidIngredient"
        ));
        assertEquals("mekanism_gas", JeiIngredientPolicy.kind("gas", "mekanism.api.chemical.gas.GasStack"));
        assertEquals("mekanism_infuse_type", JeiIngredientPolicy.kind(
            "infusion",
            "mekanism.api.chemical.infuse.InfusionStack"
        ));
    }
}
