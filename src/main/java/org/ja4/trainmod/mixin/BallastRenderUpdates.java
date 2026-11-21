package org.ja4.trainmod.mixin;

import org.ja4.trainmod.TrainClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class BallastRenderUpdates {
    @Shadow @Final private LevelExtractor levelExtractor;

    @Inject(method = "sendBlockUpdated", at = @At("TAIL"))
    private void trainmod$refreshBallast(BlockPos pos, BlockState oldState, BlockState newState,
                                       int flags, CallbackInfo callback) {
        TrainClient.refreshBallast(levelExtractor, pos, oldState, newState);
    }
}
