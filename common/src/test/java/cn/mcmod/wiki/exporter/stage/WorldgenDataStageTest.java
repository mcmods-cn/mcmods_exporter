package cn.mcmod.wiki.exporter.stage;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldgenDataStageTest {
    @Test
    void summarizesCompressedStructureWithoutExpandingBlockPositions(@TempDir Path temporary) throws Exception {
        CompoundTag structure = new CompoundTag();
        structure.putInt("DataVersion", 3465);
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(3));
        size.add(IntTag.valueOf(4));
        size.add(IntTag.valueOf(5));
        structure.put("size", size);

        ListTag palette = new ListTag();
        CompoundTag stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        palette.add(stone);
        CompoundTag moddedBlock = new CompoundTag();
        moddedBlock.putString("Name", "examplemod:machine_casing");
        palette.add(moddedBlock);
        structure.put("palette", palette);

        ListTag blocks = new ListTag();
        CompoundTag chest = new CompoundTag();
        CompoundTag chestNbt = new CompoundTag();
        chestNbt.putString("LootTable", "examplemod:chests/abandoned_lab");
        chest.put("nbt", chestNbt);
        blocks.add(chest);
        blocks.add(new CompoundTag());
        structure.put("blocks", blocks);
        ListTag entities = new ListTag();
        entities.add(new CompoundTag());
        structure.put("entities", entities);

        Path nbt = temporary.resolve("machine.nbt");
        try (var output = Files.newOutputStream(nbt)) {
            NbtIo.writeCompressed(structure, output);
        }

        JsonObject summary = WorldgenDataStage.summarizeStructureNbt(nbt);
        assertEquals(3465, summary.get("data_version").getAsInt());
        assertEquals(60, summary.get("volume").getAsLong());
        assertEquals(2, summary.get("block_count").getAsInt());
        assertEquals(1, summary.get("entity_count").getAsInt());
        assertEquals(2, summary.get("palette_size").getAsInt());
        assertEquals(
            "examplemod:chests/abandoned_lab",
            summary.getAsJsonArray("referenced_loot_tables").get(0).getAsString()
        );
        assertEquals("examplemod:machine_casing", summary.getAsJsonArray("used_blocks").get(0).getAsString());
        assertEquals("minecraft:stone", summary.getAsJsonArray("used_blocks").get(1).getAsString());
    }

    @Test
    void classifiesBlockChestAndEntityLootTables() {
        assertEquals("block", WorldgenDataStage.lootTableCategory(ResourceLocation.fromNamespaceAndPath("example", "blocks/machine")));
        assertEquals("chest", WorldgenDataStage.lootTableCategory(ResourceLocation.fromNamespaceAndPath("example", "chests/lab")));
        assertEquals("entity", WorldgenDataStage.lootTableCategory(ResourceLocation.fromNamespaceAndPath("example", "entities/robot")));
        assertEquals("fishing", WorldgenDataStage.lootTableCategory(ResourceLocation.fromNamespaceAndPath("minecraft", "gameplay/fishing")));
        assertEquals("fishing", WorldgenDataStage.lootTableCategory(ResourceLocation.fromNamespaceAndPath("minecraft", "gameplay/fishing/treasure")));
        assertEquals("gameplay", WorldgenDataStage.lootTableCategory(ResourceLocation.fromNamespaceAndPath("example", "gameplay/reward")));
    }
}
