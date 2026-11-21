package org.ja4.trainmod.mixin;

import org.ja4.trainmod.TrainClient;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LocalPlayer.class)
abstract class VehiclePicking {
    @Redirect(method = "pick", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;"))
    private static EntityHitResult trainmod$pickVehicle(Entity viewer, Vec3 from, Vec3 to, AABB search,
                                                        Predicate<Entity> filter, double distanceSqr,
                                                        Entity camera, double blockRange, double entityRange, float partial) {
        return TrainClient.pickVehicles(viewer, from, to, search, filter, distanceSqr, partial);
    }
}
