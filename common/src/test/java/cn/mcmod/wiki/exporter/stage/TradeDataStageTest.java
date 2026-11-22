package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.config.ExportConfig;
import cn.mcmod.wiki.exporter.config.ExportProfile;
import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportReport;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeDataStageTest {
    @Test
    void samplesTypedListingsDeterministicallyAndRetainsHealthyOffers(@TempDir Path root) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var config = ExportConfig.fromSystemProperties(root, false, "minecraft", ExportProfile.DATA);
        var context = new ExportContext(null, config, root, new ExportReport());
        var profession = new ResourceLocation("minecraft", "farmer");
        var listings = new Int2ObjectOpenHashMap<VillagerTrades.ItemListing[]>();
        VillagerTrades.ItemListing healthy = (entity, random) -> new MerchantOffer(
            new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD, random.nextInt(12) + 1), 8, 3, 0.05F
        );
        VillagerTrades.ItemListing broken = (entity, random) -> {
            throw new IllegalArgumentException("fixture listing failed");
        };
        listings.put(2, new VillagerTrades.ItemListing[]{healthy});
        listings.put(1, new VillagerTrades.ItemListing[]{broken, healthy});

        var levels = TradeDataStage.sampleLevels(context, profession, listings, null, true);
        assertEquals(levels, TradeDataStage.sampleLevels(context, profession, listings, null, true));
        assertEquals(2, levels.size());
        var first = levels.get(0).getAsJsonObject();
        assertEquals(1, first.get("level").getAsInt());
        assertEquals(1, first.get("offer_count").getAsInt());
        var offer = first.getAsJsonArray("offers").get(0).getAsJsonObject();
        assertEquals(1, offer.get("listing_index").getAsInt());
        var result = offer.getAsJsonObject("result");
        assertEquals("minecraft:bread", result.get("item").getAsString());
        assertTrue(result.get("count").getAsInt() >= 1 && result.get("count").getAsInt() <= 12);
    }
}
