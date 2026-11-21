package org.ja4.trainmod.mixin;

import org.ja4.trainmod.TrainClient;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityHitboxDebugRenderer.class)
abstract class VehicleHitboxDebug {
    @Inject(method = "showHitboxes", at = @At("HEAD"), cancellable = true)
    private void trainmod$vehicleHitboxes(Entity entity, float partial, boolean server, CallbackInfo callback) {
        if (TrainClient.showVehicleHitboxes(entity, partial)) callback.cancel();
    }
}
