package cn.mcmod.wiki.exporter.render;

import cn.mcmod.wiki.exporter.util.ProbabilityText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Records percentage text drawn by JEI categories and keeps it out of reusable background images. */
final class ProbabilityCapturingGuiGraphics extends GuiGraphics {
    private final List<ProbabilityText.Draw> probabilityDraws = new ArrayList<>();

    ProbabilityCapturingGuiGraphics(Minecraft minecraft, MultiBufferSource.BufferSource bufferSource) {
        super(minecraft, bufferSource);
    }

    List<ProbabilityText.Draw> probabilityDraws() {
        return Collections.unmodifiableList(probabilityDraws);
    }

    @Override
    public int drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        if (capture(text, x, y)) {
            return x + font.width(text);
        }
        return super.drawString(font, text, x, y, color, shadow);
    }

    @Override
    public int drawString(Font font, Component component, int x, int y, int color, boolean shadow) {
        String text = component.getString();
        if (capture(text, x, y)) {
            return x + font.width(component);
        }
        return super.drawString(font, component, x, y, color, shadow);
    }

    @Override
    public int drawString(Font font, FormattedCharSequence sequence, int x, int y, int color, boolean shadow) {
        String text = plainText(sequence);
        if (capture(text, x, y)) {
            return x + font.width(sequence);
        }
        return super.drawString(font, sequence, x, y, color, shadow);
    }

    private boolean capture(String text, int x, int y) {
        return ProbabilityText.parse(text).map(match -> {
            Vector3f position = new Vector3f(x, y, 0.0F).mulPosition(pose().last().pose());
            probabilityDraws.add(new ProbabilityText.Draw(match, Math.round(position.x()), Math.round(position.y())));
            return true;
        }).orElse(false);
    }

    private static String plainText(FormattedCharSequence sequence) {
        StringBuilder value = new StringBuilder();
        sequence.accept((index, style, codePoint) -> {
            value.appendCodePoint(codePoint);
            return true;
        });
        return value.toString();
    }
}
