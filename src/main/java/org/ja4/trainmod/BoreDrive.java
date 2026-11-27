package org.ja4.trainmod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

final class BoreDrive {
    static final double RETRACTED_TIRE_CLEARANCE = 0.1875;
    private static final double TIRE_SPACING = 0.60;
    private static final double MAX_DROP = 1.0;

    private BoreDrive() {}

    static RailNetwork.Position placement(Level level, RailNetwork.Position rearTruck) {
        double back = VehicleModels.rearAxle(TrainVehicle.Kind.TUNNEL_BORE);
        double inner = VehicleModels.boreInnerRearAxle();
        var center = RailNetwork.advanceBore(level, rearTruck, -(back + inner) / 2);
        return center != null && onRearRails(level, center) ? center : null;
    }

    static RailNetwork.Position restoreCenter(Level level, RailNetwork.Position closest, Vec3 actual) {
        boolean atFront = closest.forward() > 0 ? closest.distance() >= closest.rail().path().length - 1e-8
            : closest.distance() <= 1e-8;
        double beyond = actual.subtract(closest.point()).dot(closest.tangent());
        if (atFront && beyond > 1e-7) return RailNetwork.advanceBore(level, closest, beyond);
        return closest;
    }

    static boolean onRearRails(Level level, RailNetwork.Position center) {
        return RailNetwork.advance(level, center, VehicleModels.rearAxle(TrainVehicle.Kind.TUNNEL_BORE)) != null
            && RailNetwork.advance(level, center, VehicleModels.boreInnerRearAxle()) != null;
    }

    static double tireExtension(Level level, RailNetwork.Position center) {
        double front = VehicleModels.frontAxle(TrainVehicle.Kind.TUNNEL_BORE);
        if (RailNetwork.advance(level, center, front) != null) return 0;
        Vec3 forward = center.tangent().multiply(1, 0, 1).normalize();
        Vec3 axle = center.point().add(forward.scale(front));
        Vec3 offset = SwitchLayout.lateral(forward).scale(TIRE_SPACING);
        double left = groundDistance(level, axle.add(offset));
        double right = groundDistance(level, axle.subtract(offset));
        return Math.max(0, Math.max(left, right)) + RETRACTED_TIRE_CLEARANCE;
    }

    private static double groundDistance(Level level, Vec3 axle) {
        if (!level.isLoaded(BlockPos.containing(axle))) return -1;
        var hit = level.clip(new ClipContext(axle, axle.add(0, -MAX_DROP, 0),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.BLOCK ? axle.y - hit.getLocation().y : -1;
    }

    static void pushMobs(ServerLevel level, TrainVehicle bore, RailNetwork.Position target, double movement) {
        Vec3 point = target.point();
        Vec3 forward = target.tangent().multiply(1, 0, 1).normalize();
        Vec3 lateral = SwitchLayout.lateral(forward);
        double halfLength = bore.kind.length / 2.0;
        double halfWidth = bore.kind.width / 2.0;
        double searchRadius = halfLength + halfWidth + Math.abs(movement) + 0.25;
        AABB sweep = AABB.ofSize(point.add(0, bore.kind.height / 2, 0), 2 * searchRadius,
            bore.kind.height, 2 * searchRadius);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, sweep, entity -> entity.isAlive() && !entity.isPassenger())) {
            Vec3 offset = mob.position().subtract(point);
            double along = offset.dot(forward), across = offset.dot(lateral);
            double padding = mob.getBbWidth() / 2 * (Math.abs(forward.x) + Math.abs(forward.z));
            if (along < -halfLength - padding - Math.max(0, movement) || along > halfLength + padding - Math.min(0, movement)
                || Math.abs(across) >= halfWidth + padding) continue;
            double side = across < 0 ? -1 : 1;
            double clearance = halfWidth + padding + 0.05;
            mob.move(MoverType.SELF, lateral.scale(side * clearance - across));
            double shifted = mob.position().subtract(point).dot(lateral);
            if (Math.abs(shifted) < halfWidth + padding) {
                mob.move(MoverType.SELF, lateral.scale(-side * clearance - shifted));
            }
            shifted = mob.position().subtract(point).dot(lateral);
            if (Math.abs(shifted) < halfWidth + padding) {
                mob.push(forward.x * movement, 0.15, forward.z * movement);
            }
        }
    }
}
