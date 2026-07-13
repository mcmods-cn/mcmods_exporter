package cn.mcmod.wiki.exporter.stage;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Conservative, cross-version extraction of common machine-recipe scalars. */
final class RecipeParameterExtractor {
    private static final Set<String> EXACT_METHODS = Set.of(
        "getEnergy", "getEnergyRequired", "getEnergyUsage", "getEnergyPerTick",
        "getDuration", "getTime", "getProcessingTime", "getCookTime", "getCookingTime", "getTicks",
        "getTemperature", "getMinTemperature", "getMaxTemperature", "getPressure",
        "getChance", "getExperience", "getXp", "getHeat", "getRequiredHeat"
    );

    private RecipeParameterExtractor() {
    }

    static JsonObject extract(Object recipe) {
        JsonObject result = new JsonObject();
        if (recipe == null) {
            return result;
        }
        TreeSet<Method> methods = new TreeSet<>(Comparator.comparing(Method::getName));
        for (Method method : recipe.getClass().getMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                && method.getParameterCount() == 0
                && isAllowed(method.getName())) {
                methods.add(method);
            }
        }
        for (Method method : methods) {
            try {
                Object value = method.invoke(recipe);
                addScalar(result, snakeCase(method.getName().substring(3)), value);
            } catch (Throwable ignored) {
                // Optional mod APIs are allowed to reject calls without a live machine.
            }
        }
        return result;
    }

    private static boolean isAllowed(String method) {
        if (EXACT_METHODS.contains(method)) {
            return true;
        }
        return method.matches("get(Min|Max)?(Energy|Time|Duration|Ticks|Temperature|Pressure|Chance|Heat)(Required|Usage|PerTick)?");
    }

    private static void addScalar(JsonObject target, String key, Object value) {
        if (value instanceof Byte number) target.addProperty(key, number);
        else if (value instanceof Short number) target.addProperty(key, number);
        else if (value instanceof Integer number) target.addProperty(key, number);
        else if (value instanceof Long number) target.addProperty(key, number);
        else if (value instanceof Float number) target.addProperty(key, number);
        else if (value instanceof Double number) target.addProperty(key, number);
        else if (value instanceof Boolean bool) target.addProperty(key, bool);
        else if (value instanceof String string) target.addProperty(key, string);
        else if (value instanceof Enum<?> enumeration) target.addProperty(key, enumeration.name().toLowerCase(Locale.ROOT));
        else if (value instanceof ResourceLocation id) target.addProperty(key, id.toString());
        else if (value != null && value.getClass().getSimpleName().equals("FloatingLong")) {
            target.addProperty(key, value.toString());
        }
    }

    private static String snakeCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
