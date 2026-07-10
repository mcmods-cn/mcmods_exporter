package cn.mcmod.wiki.exporter.util;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComponentLocalizerTest {
    @Test
    void localizesNestedArgumentsAndSiblings() {
        Component component = Component.translatable(
            "advancement.example.title",
            Component.translatable("item.examplemod.crystal")
        ).append(Component.literal("!"));

        String localized = ComponentLocalizer.localize(component, Map.of(
            "advancement.example.title", "收集 %s",
            "item.examplemod.crystal", "水晶"
        ));

        assertEquals("收集 水晶!", localized);
    }

    @Test
    void supportsIndexedPlaceholdersAndEscapedPercent() {
        Component component = Component.translatable("format.example", "甲", "乙");

        String localized = ComponentLocalizer.localize(component, Map.of(
            "format.example", "%2$s + %1$s = 100%%"
        ));

        assertEquals("乙 + 甲 = 100%", localized);
    }
}
