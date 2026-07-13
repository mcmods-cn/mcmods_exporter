package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.OnceStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Exports deterministic samples of code-registered villager trades. Minecraft
 * does not expose the original ranges of ItemListing implementations, so the
 * format explicitly marks these rows as samples instead of pretending they are
 * complete definitions.
 */
public final class TradeDataStage extends OnceStage {
    public static final String ID = "trades";

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected void run(ExportContext context) throws Exception {
        JsonArray professions = new JsonArray();
        Object tradeMap = staticField("net.minecraft.world.entity.npc.VillagerTrades", "TRADES");
        Map<?, ?> trades = tradeMap instanceof Map<?, ?> map ? map : Map.of();
        Entity trader = context.minecraft().level == null ? null : EntityType.VILLAGER.create(context.minecraft().level);
        try {
            BuiltInRegistries.VILLAGER_PROFESSION.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
                .forEach(entry -> {
                    ResourceLocation professionId = entry.getKey().location();
                    boolean selectedProfession = context.matches(professionId);
                    Object levelsObject = trades.get(entry.getValue());
                    Map<?, ?> levels = levelsObject instanceof Map<?, ?> map ? map : Map.of();
                    JsonArray levelRows = sampleLevels(context, professionId, levels, trader, selectedProfession);
                    if (!selectedProfession && levelRows.isEmpty()) {
                        return;
                    }
                    JsonObject profession = baseId(professionId);
                    profession.addProperty("selected_namespace", selectedProfession);
                    profession.addProperty("inclusion_reason", selectedProfession ? "target_profession" : "trade_contains_target_item");
                    profession.add("names", professionNames(context, professionId));
                    profession.addProperty("level_count", levelRows.size());
                    profession.add("levels", levelRows);
                    professions.add(profession);
                });
        } finally {
            if (trader != null) {
                trader.discard();
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-villager-trades/v1");
        root.addProperty("source", trades.isEmpty() ? "unavailable" : "runtime_item_listing_sample");
        root.addProperty("complete", false);
        root.addProperty("sampling_note", "Code-registered ItemListing implementations expose generated offers, not their original random ranges; one deterministic sample is stored per listing.");
        root.addProperty("profession_count", professions.size());
        root.add("professions", professions);
        Object wanderingMap = staticField("net.minecraft.world.entity.npc.VillagerTrades", "WANDERING_TRADER_TRADES");
        Entity wanderingTrader = context.minecraft().level == null ? null : EntityType.WANDERING_TRADER.create(context.minecraft().level);
        try {
            Map<?, ?> wanderingLevels = wanderingMap instanceof Map<?, ?> map ? map : Map.of();
            JsonArray wandering = sampleLevels(
                context,
                ResourceLocation.fromNamespaceAndPath("minecraft", "wandering_trader"),
                wanderingLevels,
                wanderingTrader,
                context.config().namespaces().isEmpty()
            );
            root.addProperty("wandering_trader_level_count", wandering.size());
            root.add("wandering_trader", wandering);
        } finally {
            if (wanderingTrader != null) {
                wanderingTrader.discard();
            }
        }
        context.writeJson("registries/villager_trades.json", root);
        context.report().addCount(ID, "villager_professions", professions.size());
    }

    private static JsonArray sampleLevels(
        ExportContext context,
        ResourceLocation professionId,
        Map<?, ?> levels,
        Entity trader,
        boolean selectedProfession
    ) {
        JsonArray result = new JsonArray();
        levels.entrySet().stream()
            .sorted(Comparator.comparingInt(entry -> ((Number) entry.getKey()).intValue()))
            .forEach(entry -> {
                int level = ((Number) entry.getKey()).intValue();
                Object listings = entry.getValue();
                JsonArray offers = new JsonArray();
                if (listings != null && listings.getClass().isArray()) {
                    for (int index = 0; index < Array.getLength(listings); index++) {
                        Object listing = Array.get(listings, index);
                        try {
                            MerchantOffer offer = invokeOffer(listing, trader, professionId, level, index);
                            if (offer == null || (!selectedProfession && !containsTargetItem(context, offer))) {
                                continue;
                            }
                            JsonObject offerJson = offerJson(offer);
                            offerJson.addProperty("listing_index", index);
                            offers.add(offerJson);
                        } catch (Throwable throwable) {
                            context.report().error(ID, professionId + ":" + level + ":" + index, throwable);
                        }
                    }
                }
                if (!offers.isEmpty()) {
                    JsonObject levelJson = new JsonObject();
                    levelJson.addProperty("level", level);
                    levelJson.addProperty("offer_count", offers.size());
                    levelJson.add("offers", offers);
                    result.add(levelJson);
                }
            });
        return result;
    }

    private static MerchantOffer invokeOffer(
        Object listing,
        Entity trader,
        ResourceLocation profession,
        int level,
        int index
    ) throws Exception {
        Method method = null;
        for (Method candidate : listing.getClass().getMethods()) {
            if (candidate.getName().equals("getOffer") && candidate.getParameterCount() == 2) {
                method = candidate;
                break;
            }
        }
        if (method == null) {
            return null;
        }
        method.setAccessible(true);
        long seed = 31L * profession.toString().hashCode() + 17L * level + index;
        Object value = method.invoke(listing, trader, RandomSource.create(seed));
        return value instanceof MerchantOffer offer ? offer : null;
    }

    private static boolean containsTargetItem(ExportContext context, MerchantOffer offer) {
        return targetStack(context, offer.getBaseCostA())
            || targetStack(context, offer.getCostB())
            || targetStack(context, offer.getResult());
    }

    private static boolean targetStack(ExportContext context, ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && context.matches(id);
    }

    private static JsonObject offerJson(MerchantOffer offer) {
        JsonObject json = new JsonObject();
        json.add("base_cost_a", ExportContext.stackJson(offer.getBaseCostA()));
        json.add("cost_a", ExportContext.stackJson(offer.getCostA()));
        json.add("cost_b", ExportContext.stackJson(offer.getCostB()));
        json.add("result", ExportContext.stackJson(offer.getResult()));
        json.addProperty("uses", offer.getUses());
        json.addProperty("max_uses", offer.getMaxUses());
        json.addProperty("villager_xp", offer.getXp());
        json.addProperty("price_multiplier", offer.getPriceMultiplier());
        json.addProperty("demand", offer.getDemand());
        json.addProperty("special_price", offer.getSpecialPriceDiff());
        json.addProperty("reward_experience", offer.shouldRewardExp());
        return json;
    }

    private static JsonObject professionNames(ExportContext context, ResourceLocation id) {
        List<String> keys = List.of(
            "entity." + id.getNamespace() + ".villager." + id.getPath().replace('/', '.'),
            "entity.minecraft.villager." + id.getPath().replace('/', '.'),
            "profession." + id.getNamespace() + "." + id.getPath().replace('/', '.')
        );
        for (String key : keys) {
            JsonObject names = context.names(key);
            if (!names.entrySet().isEmpty()) {
                return names;
            }
        }
        return new JsonObject();
    }

    private static Object staticField(String className, String fieldName) {
        try {
            Field field = Class.forName(className).getField(fieldName);
            return field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject baseId(ResourceLocation id) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id.toString());
        json.addProperty("namespace", id.getNamespace());
        json.addProperty("path", id.getPath());
        return json;
    }
}
