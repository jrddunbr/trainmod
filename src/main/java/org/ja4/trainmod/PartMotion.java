package org.ja4.trainmod;

import net.minecraft.util.Mth;

final class PartMotion {
    private static final double DURATION_TICKS = 5;
    private double from;
    private double target;
    private long started = Long.MIN_VALUE;

    PartMotion(double position) {
        target = position;
    }

    void moveTo(double position, long time) {
        if (position == target) return;
        from = at(time);
        target = position;
        started = time;
    }

    double at(double time) {
        if (started == Long.MIN_VALUE) return target;
        double t = Mth.clamp((time - started) / DURATION_TICKS, 0, 1);
        return Mth.lerp(t * t * (3 - 2 * t), from, target);
    }
}
