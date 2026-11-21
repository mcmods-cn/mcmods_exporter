package cn.mcmod.wiki.exporter.stage;

import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.SpawnerRenderer;
import net.minecraft.client.renderer.blockentity.TheEndGatewayRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEntityModelStageTest {
    @Test
    void onlyKnownPlacedStateRenderersSkipDetachedCapture() {
        assertTrue(BlockEntityModelStage.requiresPlacedState(BeaconRenderer.class));
        assertTrue(BlockEntityModelStage.requiresPlacedState(TheEndGatewayRenderer.class));
        assertTrue(BlockEntityModelStage.requiresPlacedState(SpawnerRenderer.class));
        assertFalse(BlockEntityModelStage.requiresPlacedState(ChestRenderer.class));
        assertFalse(BlockEntityModelStage.requiresPlacedState(ReplacementBeaconRenderer.class));
    }

    private static class ReplacementBeaconRenderer extends BeaconRenderer {
        private ReplacementBeaconRenderer(BlockEntityRendererProvider.Context context) {
            super(context);
        }
    }
}
