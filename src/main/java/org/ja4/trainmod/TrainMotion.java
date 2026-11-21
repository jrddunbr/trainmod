package org.ja4.trainmod;

import net.minecraft.world.phys.Vec3;

final class TrainMotion {
    private static final int LEAD_TICKS = 3;
    private static final double CORRECTION_LIMIT = 0.075;
    private Vec3 positionSample;
    private Vec3 displayVelocity = Vec3.ZERO;
    private int age;

    void sample(Vec3 position) {
        positionSample = position;
        age = 0;
    }

    Vec3 step(Vec3 current, Vec3 reportedVelocity) {
        if (positionSample == null) return current;
        Vec3 target = positionSample.add(reportedVelocity.scale(Math.min(age, LEAD_TICKS)));
        Vec3 pace = age <= LEAD_TICKS ? reportedVelocity : Vec3.ZERO;
        age = Math.min(age + 1, LEAD_TICKS + 1);

        Vec3 correction = target.subtract(current).subtract(pace).scale(0.35);
        if (correction.lengthSqr() > CORRECTION_LIMIT * CORRECTION_LIMIT) {
            correction = correction.normalize().scale(CORRECTION_LIMIT);
        }
        displayVelocity = displayVelocity.lerp(pace.add(correction), 0.55);

        if (pace.lengthSqr() == 0) {
            Vec3 remaining = target.subtract(current);
            if (remaining.lengthSqr() <= 1.0e-8
                || remaining.distanceToSqr(displayVelocity) < 1.0e-6
                || remaining.lengthSqr() <= displayVelocity.dot(remaining)) {
                displayVelocity = Vec3.ZERO;
                return target;
            }
        }
        return current.add(displayVelocity);
    }

    void reset() {
        positionSample = null;
        displayVelocity = Vec3.ZERO;
        age = 0;
    }
}
