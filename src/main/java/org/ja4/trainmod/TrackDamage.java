package org.ja4.trainmod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class TrackDamage {
    private TrackDamage() {}

    static Vec3 shift(TrackGeometry.Shape shape, BlockPos cell, Vec3 point) {
        Vec3 shift = Vec3.ZERO;
        double closest = Double.POSITIVE_INFINITY;
        for (var section : TrackEnds.sections(shape, cell)) {
            var path = shape.routes().get(section.route());
            double at = Math.clamp(path.projectHorizontally(point), section.start(), section.end());
            double distance = path.point(at).subtract(point).horizontalDistanceSqr();
            if (distance >= closest) continue;
            closest = distance;
            double length = section.end() - section.start();
            double offset = displacement((at - section.start()) / length, length);
            shift = SwitchLayout.lateral(path.tangent(at)).scale(offset);
        }
        return point.add(shift);
    }

    static double displacement(double fraction, double length) {
        if (fraction <= 1e-6 || fraction >= 1 - 1e-6) return 0;
        // Both rails follow the same S and meet undamaged track at the original position and heading.
        double phase = 2 * Math.PI * fraction;
        return Math.min(0.20, 0.20 * length) * Math.sin(phase) * (1 - Math.cos(phase));
    }
}
