package cn.mcmod.wiki.exporter.render;

import cn.mcmod.wiki.exporter.util.ProbabilityText;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public final class OffscreenRenderer {
    private OffscreenRenderer() {
    }

    public static void renderItem(ItemStack stack, int size, Path output) throws Exception {
        renderGui(size, size, 16, 16, output, graphics -> graphics.renderFakeItem(stack, 0, 0));
    }

    public static void renderEntity(LivingEntity entity, int size, Path output) throws Exception {
        float largestDimension = Math.max(entity.getBbWidth(), entity.getBbHeight());
        int scale = Math.max(1, Math.min(70, Math.round(68.0F / Math.max(0.1F, largestDimension))));
        renderGui(size, size, 100, 100, output, graphics ->
            InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, 0, 0, 100, 100, scale, 65.0F, -20.0F, 0.0F, entity)
        );
    }

    public static void renderGui(
        int pixelWidth,
        int pixelHeight,
        int logicalWidth,
        int logicalHeight,
        Path output,
        Consumer<GuiGraphics> renderer
    ) throws Exception {
        renderGuiInternal(
            pixelWidth,
            pixelHeight,
            logicalWidth,
            logicalHeight,
            output,
            graphics -> graphics,
            renderer
        );
    }

    /**
     * Runs a JEI layout once and records probability labels drawn by the category.
     * The labels themselves are suppressed so this same path can also produce a
     * recipe-independent template background.
     */
    public static List<ProbabilityText.Draw> captureProbabilityText(
        int logicalWidth,
        int logicalHeight,
        Consumer<GuiGraphics> renderer
    ) throws Exception {
        return renderGuiInternal(
            Math.max(1, logicalWidth),
            Math.max(1, logicalHeight),
            logicalWidth,
            logicalHeight,
            null,
            graphics -> new ProbabilityCapturingGuiGraphics(
                Minecraft.getInstance(),
                Minecraft.getInstance().renderBuffers().bufferSource()
            ),
            renderer
        );
    }

    public static void renderGuiSuppressingProbabilityText(
        int pixelWidth,
        int pixelHeight,
        int logicalWidth,
        int logicalHeight,
        Path output,
        Consumer<GuiGraphics> renderer
    ) throws Exception {
        renderGuiInternal(
            pixelWidth,
            pixelHeight,
            logicalWidth,
            logicalHeight,
            output,
            graphics -> new ProbabilityCapturingGuiGraphics(
                Minecraft.getInstance(),
                Minecraft.getInstance().renderBuffers().bufferSource()
            ),
            renderer
        );
    }

    private static List<ProbabilityText.Draw> renderGuiInternal(
        int pixelWidth,
        int pixelHeight,
        int logicalWidth,
        int logicalHeight,
        Path output,
        Function<GuiGraphics, GuiGraphics> graphicsFactory,
        Consumer<GuiGraphics> renderer
    ) throws Exception {
        RenderSystem.assertOnRenderThread();
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        TextureTarget target = new TextureTarget(pixelWidth, pixelHeight, true, Minecraft.ON_OSX);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        RenderSystem.backupProjectionMatrix();
        modelView.pushMatrix();
        try {
            target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0.0F, logicalWidth, logicalHeight, 0.0F, 1000.0F, 21000.0F),
                VertexSorting.ORTHOGRAPHIC_Z
            );
            modelView.identity();
            modelView.translate(0.0F, 0.0F, -11000.0F);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();

            GuiGraphics graphics = graphicsFactory.apply(
                new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource())
            );
            renderer.accept(graphics);
            graphics.flush();

            if (output != null) {
                Files.createDirectories(output.getParent());
                try (NativeImage image = captureWithAlpha(target)) {
                    image.writeToFile(output);
                }
            }
            if (graphics instanceof ProbabilityCapturingGuiGraphics capturing) {
                return List.copyOf(capturing.probabilityDraws());
            }
            return List.of();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            target.destroyBuffers();
            mainTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, mainTarget.viewWidth, mainTarget.viewHeight);
        }
    }

    private static NativeImage captureWithAlpha(RenderTarget target) {
        NativeImage image = new NativeImage(target.width, target.height, false);
        RenderSystem.bindTexture(target.getColorTextureId());
        // Screenshot.takeScreenshot(..., true) forces every alpha byte to 255.
        // Download directly so the transparent framebuffer clear remains transparent in the PNG.
        image.downloadTexture(0, false);
        image.flipY();
        return image;
    }
}
