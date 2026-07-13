package cn.mcmod.wiki.exporter.stage;

import java.util.Locale;

/** String-only policy so supported third-party ingredient types do not become compile-time dependencies. */
final class JeiIngredientPolicy {
    private static final String MEKANISM_CHEMICAL_PREFIX = "mekanism.api.chemical.";
    private static final String MEKANISM_GAS_STACK = "mekanism.api.chemical.gas.GasStack";
    private static final String MEKANISM_INFUSION_STACK = "mekanism.api.chemical.infuse.InfusionStack";
    private static final String MEKANISM_PIGMENT_STACK = "mekanism.api.chemical.pigment.PigmentStack";
    private static final String MEKANISM_SLURRY_STACK = "mekanism.api.chemical.slurry.SlurryStack";

    private JeiIngredientPolicy() {
    }

    static boolean includeInCatalog(String typeUid, String ingredientClassName) {
        return true;
    }

    static String kind(String typeUid, String ingredientClassName) {
        String uid = typeUid.toLowerCase(Locale.ROOT);
        String className = ingredientClassName.toLowerCase(Locale.ROOT);
        if (uid.equals("fluid_stack")
            || className.contains("fluidstack")
            || className.contains("fluidingredient")) {
            return "fluid";
        }
        String chemicalType = mekanismChemicalType(ingredientClassName);
        if (chemicalType != null && !chemicalType.equals("unknown")) return "mekanism_" + chemicalType;
        return "custom";
    }

    static String mekanismChemicalType(String ingredientClassName) {
        return switch (ingredientClassName) {
            case MEKANISM_GAS_STACK -> "gas";
            case MEKANISM_INFUSION_STACK -> "infuse_type";
            case MEKANISM_PIGMENT_STACK -> "pigment";
            case MEKANISM_SLURRY_STACK -> "slurry";
            default -> ingredientClassName.startsWith(MEKANISM_CHEMICAL_PREFIX) ? "unknown" : null;
        };
    }
}
