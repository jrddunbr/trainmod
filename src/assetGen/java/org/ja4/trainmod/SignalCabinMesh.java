package org.ja4.trainmod;

import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class SignalCabinMesh {
    private static final double WALL_TOP = 24;
    private static final double LEFT_HINGE = 0.92;
    private static final double RIGHT_HINGE = 16 - LEFT_HINGE;
    private static final double HINGE_DEPTH = 0.90;
    private static final UnaryOperator<Vec3> STATIC = point -> point;
    private final ObjMesh mesh;

    private SignalCabinMesh(boolean upperOnly) {
        mesh = new ObjMesh(upperOnly ? BlockPos.ZERO.above() : BlockPos.ZERO, true, "signal_cabin");
    }

    static ObjMesh create(boolean lower, boolean upper, boolean open, boolean westHinge) {
        var cabin = new SignalCabinMesh(upper && !lower);
        if (lower) cabin.walls(0, 16);
        if (upper) {
            cabin.walls(16, WALL_TOP);
            cabin.box("trim", 0.88, 22.85, 0.10, 15.12, WALL_TOP, 0.88, STATIC);
            cabin.roof();
        }
        cabin.door(lower, upper, open, westHinge);
        return cabin.mesh;
    }

    private void walls(double low, double high) {
        box("panel", 0.24, low, 0.88, 0.80, high, 15.12, STATIC);
        box("panel", 15.20, low, 0.88, 15.76, high, 15.12, STATIC);
        box("panel", 0.88, low, 15.20, 15.12, high, 15.76, STATIC);
        for (double x : new double[]{0.10, 15.12}) {
            for (double z : new double[]{0.10, 15.12}) {
                box("trim", x, low, z, x + 0.78, high, z + 0.78, STATIC);
            }
        }
        for (double rib = 2; rib <= 14; rib += 2) {
            box("panel", 0.13, low, rib - 0.22, 0.24, high, rib + 0.22, STATIC);
            box("panel", 15.76, low, rib - 0.22, 15.87, high, rib + 0.22, STATIC);
            box("panel", rib - 0.22, low, 15.76, rib + 0.22, high, 15.87, STATIC);
        }
    }

    private void door(boolean lower, boolean upper, boolean open, boolean westHinge) {
        UnaryOperator<Vec3> pose = !open ? STATIC : point -> {
            double sign = westHinge ? 1 : -1;
            double hinge = westHinge ? LEFT_HINGE : RIGHT_HINGE;
            return new Vec3(hinge - sign * (point.z - HINGE_DEPTH), point.y, HINGE_DEPTH + sign * (point.x - hinge));
        };
        if (upper) box("door", LEFT_HINGE, 16, 0.23, RIGHT_HINGE, 22.75, 0.73, pose);
        if (!lower) return;
        box("door", LEFT_HINGE, 0.20, 0.23, RIGHT_HINGE, 16, 0.73, pose);
        double latch = westHinge ? 12.7 : 3.3;
        box("hardware", latch - 0.48, 10.30, -0.74, latch + 0.48, 10.75, 0.23, pose);
        box("hardware", latch - 0.48, 12.55, -0.74, latch + 0.48, 13.00, 0.23, pose);
        box("hardware", latch - 0.25, 10.48, -1.02, latch + 0.25, 12.84, -0.64, pose);
        box("trim", latch - 0.73, 6.42, 0.03, latch + 0.73, 8.48, 0.23, pose);
        box("hardware", latch - 0.43, 7.14, -0.40, latch - 0.22, 8.19, -0.16, pose);
        box("hardware", latch + 0.22, 7.14, -0.40, latch + 0.43, 8.19, -0.16, pose);
        box("hardware", latch - 0.36, 8.02, -0.36, latch + 0.36, 8.33, -0.20, pose);
        box("lock", latch - 0.60, 6.18, -0.54, latch + 0.60, 7.33, -0.10, pose);
        box("trim", latch - 0.10, 6.44, -0.56, latch + 0.10, 6.80, -0.49, pose);
    }

    private void roof() {
        double edge = SignalCabinGeometry.ROOF_OVERHANG;
        double thickness = SignalCabinGeometry.ROOF_THICKNESS;
        double front = -edge, back = 16 + edge;
        double left = -edge, right = 16 + edge;
        double eave = SignalCabinGeometry.roofUnderside(left), ridge = SignalCabinGeometry.roofUnderside(8);
        for (double[] slope : new double[][]{{left, eave, 8, ridge}, {8, ridge, right, eave}}) {
            double x1 = slope[0], y1 = slope[1], x2 = slope[2], y2 = slope[3];
            face("roof", STATIC, p(x1, y1 + thickness, front), p(x1, y1 + thickness, back),
                p(x2, y2 + thickness, back), p(x2, y2 + thickness, front));
            face("roof", STATIC, p(x1, y1, back), p(x1, y1, front), p(x2, y2, front), p(x2, y2, back));
            face("trim", STATIC, p(x1, y1, front), p(x1, y1 + thickness, front),
                p(x2, y2 + thickness, front), p(x2, y2, front));
            face("trim", STATIC, p(x2, y2, back), p(x2, y2 + thickness, back),
                p(x1, y1 + thickness, back), p(x1, y1, back));
        }
        face("trim", STATIC, p(left, eave, back), p(left, eave + thickness, back),
            p(left, eave + thickness, front), p(left, eave, front));
        face("trim", STATIC, p(right, eave, front), p(right, eave + thickness, front),
            p(right, eave + thickness, back), p(right, eave, back));
        gable(0.22, true);
        gable(0.72, false);
        gable(15.28, true);
        gable(15.78, false);
    }

    private void gable(double z, boolean north) {
        double left = 0.88, right = 15.12;
        double outer = SignalCabinGeometry.roofUnderside(left), top = SignalCabinGeometry.roofUnderside(8);
        Vec3 a = p(left, WALL_TOP, z), b = p(left, outer, z);
        Vec3 c = p(right, outer, z), d = p(right, WALL_TOP, z), peak = p(8, top, z);
        if (north) {
            face("trim", STATIC, a, b, c, d);
            face("trim", STATIC, b, peak, c, c);
        } else {
            face("trim", STATIC, d, c, b, a);
            face("trim", STATIC, c, peak, b, b);
        }
    }

    private void box(String material, double x0, double y0, double z0, double x1, double y1, double z1,
                     UnaryOperator<Vec3> pose) {
        face(material, pose, p(x0, y0, z0), p(x0, y1, z0), p(x1, y1, z0), p(x1, y0, z0));
        face(material, pose, p(x1, y0, z1), p(x1, y1, z1), p(x0, y1, z1), p(x0, y0, z1));
        face(material, pose, p(x0, y0, z1), p(x0, y1, z1), p(x0, y1, z0), p(x0, y0, z0));
        face(material, pose, p(x1, y0, z0), p(x1, y1, z0), p(x1, y1, z1), p(x1, y0, z1));
        if (y1 != 16) face(material, pose, p(x0, y1, z0), p(x0, y1, z1), p(x1, y1, z1), p(x1, y1, z0));
        if (y0 != 16) face(material, pose, p(x0, y0, z1), p(x0, y0, z0), p(x1, y0, z0), p(x1, y0, z1));
    }

    private void face(String material, UnaryOperator<Vec3> pose, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        mesh.face(material, vertex(pose.apply(a)), vertex(pose.apply(b)), vertex(pose.apply(c)), vertex(pose.apply(d)));
    }

    private static Vec3 p(double x, double y, double z) { return new Vec3(x, y, z); }

    private static Vec3 vertex(Vec3 point) { return point.scale(1 / 16.0).add(-0.5, 0, -0.5); }
}
