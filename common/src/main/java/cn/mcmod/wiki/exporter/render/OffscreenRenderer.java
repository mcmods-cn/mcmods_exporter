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
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
        renderGuiInternal(
            size,
            size,
            100,
            100,
            output,
            true,
            graphics -> graphics,
            graphics -> renderEntityWithoutWindowScissor(graphics, entity, scale)
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
            false,
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
            false,
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
            false,
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
        boolean requireVisiblePixels,
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
                    if (requireVisiblePixels && !hasVisiblePixels(image)) {
                        throw new IllegalStateException("Offscreen entity render produced a fully transparent image");
                    }
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

    /**
     * Minecraft 1.21's follows-mouse helper enables a scissor rectangle using the
     * main window dimensions. That rectangle does not map to our small offscreen
     * framebuffer and clips the entity completely, so invoke the lower-level
     * renderer with a fixed oblique pose instead.
     */
    private static void renderEntityWithoutWindowScissor(
        GuiGraphics graphics,
        LivingEntity entity,
        int scale
    ) {
        float oldBodyRotation = entity.yBodyRot;
        float oldYaw = entity.getYRot();
        float oldPitch = entity.getXRot();
        float oldHeadRotation = entity.yHeadRot;
        float oldPreviousHeadRotation = entity.yHeadRotO;

        float yawAngle = (float) Math.atan((50.0F - 65.0F) / 40.0F);
        float pitchAngle = (float) Math.atan((50.0F + 20.0F) / 40.0F);
        Quaternionf cameraRotation = new Quaternionf()
            .rotateX(pitchAngle * 20.0F * ((float) Math.PI / 180.0F));
        Quaternionf entityRotation = new Quaternionf()
            .rotateZ((float) Math.PI)
            .mul(cameraRotation);

        try {
            entity.yBodyRot = 180.0F + yawAngle * 20.0F;
            entity.setYRot(180.0F + yawAngle * 40.0F);
            entity.setXRot(-pitchAngle * 20.0F);
            entity.yHeadRot = entity.getYRot();
            entity.yHeadRotO = entity.getYRot();

            float entityScale = Math.max(0.0001F, entity.getScale());
            InventoryScreen.renderEntityInInventory(
                graphics,
                50.0F,
                50.0F,
                scale / entityScale,
                new Vector3f(0.0F, entity.getBbHeight() / 2.0F, 0.0F),
                entityRotation,
                cameraRotation,
                entity
            );
        } finally {
            entity.yBodyRot = oldBodyRotation;
            entity.setYRot(oldYaw);
            entity.setXRot(oldPitch);
            entity.yHeadRot = oldHeadRotation;
            entity.yHeadRotO = oldPreviousHeadRotation;
        }
    }

    static boolean hasVisiblePixels(NativeImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getPixelRGBA(x, y) >>> 24) != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
