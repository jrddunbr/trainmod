package org.ja4.trainmod;

import net.minecraft.core.PositionAndRotation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

final class TrainInterpolation implements InterpolationHandler {
    private final TrainVehicle vehicle;
    private final TrainMotion motion = new TrainMotion();
    private Vec3 velocity = Vec3.ZERO;
    private @Nullable Vec3 lastDisplayed;
    private @Nullable PositionAndRotation target;

    TrainInterpolation(TrainVehicle vehicle) {
        this.vehicle = vehicle;
    }

    void setVelocity(Vec3 velocity) {
        this.velocity = velocity;
    }

    @Override
    public @Nullable PositionAndRotation target() { return target; }

    @Override
    public boolean interpolateTo(@Nullable PositionPath position, float yaw, float pitch, boolean hasRotation) {
        PositionAndRotation previous = target != null ? target : vehicle.storePositionAndRotation();
        Vec3 location = position != null ? position.endPosition() : previous.position();
        if (position != null || target == null) motion.sample(location);
        target = PositionAndRotation.of(location, hasRotation ? yaw : previous.yRot(), hasRotation ? pitch : previous.xRot());
        return true;
    }

    @Override
    public void interpolate() {
        if (target == null) return;
        Vec3 current = vehicle.position();
        if (lastDisplayed != null && current.distanceToSqr(lastDisplayed) > 4) {
            // Vanilla applies large teleports directly, outside the interpolation handler.
            cancel();
            return;
        }

        Vec3 displayed = motion.step(current, velocity);
        if (!displayed.equals(current)) vehicle.setPos(displayed);
        vehicle.setYRot(Mth.wrapDegrees(Mth.rotLerp(0.5f, vehicle.getYRot(), target.yRot())));
        vehicle.setXRot(Mth.lerp(0.5f, vehicle.getXRot(), target.xRot()));
        lastDisplayed = displayed;
    }

    @Override
    public void applyPredictedMovement(Vec3 delta) {
        // The server reports the train's entire movement, including its consist.
    }

    @Override
    public boolean hasActiveInterpolation() { return target != null; }

    @Override
    public void cancel() {
        motion.reset();
        target = null;
        lastDisplayed = null;
    }
}
