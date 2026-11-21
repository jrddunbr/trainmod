package org.ja4.trainmod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

final class CurveBanking {
    static final int START = 1;
    static final int END = 2;
    static final int VARIANTS = 9;
    static final double GAUGE = 0.49;
    static final double MAX_SLOPE = 6.0 / 56.5;
    private static final double PROTOTYPE_METERS_PER_BLOCK = 8;
    private static final double GRAVITY = 9.80665;

    private CurveBanking() {}

    static int variant(int start, int end) { return start + 3 * end; }

    static double slope(TrackGeometry.Kind kind, TrackGeometry.Path path, double distance, int connections) {
        int design = speedClass(kind);
        double speed = 20 * design * 0.44704;
        double radius = Math.max(1, path.length - 0.5 - Math.sqrt(0.5)) / (Math.PI / 4) * PROTOTYPE_METERS_PER_BLOCK;
        double nominal = Math.min(classSlope(design), speed * speed / (GRAVITY * radius));
        double transition = kind == TrackGeometry.Kind.CURVE_SMALL ? 1 : 2;
        double first = mix(Math.min(nominal, classSlope(connections % 3)), nominal, ease(distance / transition));
        double last = mix(Math.min(nominal, classSlope(connections / 3)), nominal, ease((path.length - distance) / transition));
        return outside(path) * Math.min(first, last);
    }

    static double lift(TrackGeometry.Kind kind, TrackGeometry.Path path, double distance, int connections, double lateral) {
        double slope = slope(kind, path, distance, connections);
        return Math.abs(slope) * GAUGE / 2 + slope * lateral;
    }

    static int connections(BlockGetter level, BlockPos root, TrackGeometry.Kind kind, TrackGeometry.Path path, int sides) {
        int start = (sides & START) != 0 ? neighborClass(level, root, path, false) : 0;
        int end = (sides & END) != 0 ? neighborClass(level, root, path, true) : 0;
        return variant(Math.min(start, speedClass(kind)), Math.min(end, speedClass(kind)));
    }

    private static int neighborClass(BlockGetter level, BlockPos root, TrackGeometry.Path path, boolean end) {
        Vec3 port = TrackGeometry.origin(root).add(path.endpoint(end));
        Vec3 outward = path.tangent(end ? path.length : 0).scale(end ? 1 : -1);
        BlockPos landing = BlockPos.containing(port.add(outward.scale(0.15)));
        var state = level.getBlockState(landing);
        if (!(state.getBlock() instanceof TrackBlock track) || !track.kind.bankedCurve()) return 0;
        var shape = track.shape(state);
        int segment = state.getValue(TrackBlock.INDEX);
        if (segment >= shape.cells().size()) return 0;
        BlockPos neighbor = landing.subtract(shape.cells().get(segment));
        if (root.equals(neighbor)) return 0;
        var adjacent = shape.routes().getFirst();
        return compatible(path, end, port, outward, TrackGeometry.origin(neighbor), adjacent, false)
            || compatible(path, end, port, outward, TrackGeometry.origin(neighbor), adjacent, true)
            ? speedClass(track.kind) : 0;
    }

    private static boolean compatible(TrackGeometry.Path path, boolean end, Vec3 port, Vec3 outward,
                                      Vec3 origin, TrackGeometry.Path adjacent, boolean theirEnd) {
        return origin.add(adjacent.endpoint(theirEnd)).distanceToSqr(port) < 1e-6
            && adjacent.tangent(theirEnd ? adjacent.length : 0).scale(theirEnd ? -1 : 1).dot(outward) > 0.999
            && outside(path) == outside(adjacent) * (end == theirEnd ? -1 : 1);
    }

    private static int speedClass(TrackGeometry.Kind kind) { return kind == TrackGeometry.Kind.CURVE_SMALL ? 1 : 2; }

    private static double classSlope(int speedClass) { return MAX_SLOPE * speedClass * speedClass / 4; }

    private static double outside(TrackGeometry.Path path) {
        Vec3 entrance = path.tangent(0);
        return -Math.signum(new Vec3(entrance.z, 0, -entrance.x).dot(path.tangent(path.length)));
    }

    private static double ease(double value) {
        double t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static double mix(double a, double b, double t) { return a + (b - a) * t; }
}
