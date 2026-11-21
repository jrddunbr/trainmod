package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Shared by placement, rendering, routing and occupancy. Coordinates are block-center relative. */
final class TrackGeometry {
    static final double RAIL_HEIGHT = 0.25;
    static final int SAMPLES = 32;

    enum Kind {
        STRAIGHT("Straight"), DIAGONAL("Diagonal"),
        CURVE_SMALL("45° Curve (Small)"), CURVE_MEDIUM("45° Curve (Medium)"),
        CURVE_LARGE("45° Curve (Large)"), GRADE_6("1:6 Grade"), GRADE_8("1:8 Grade"),
        TURNOUT_45("45° Turnout"), TURNOUT_PARALLEL("Parallel Turnout"), WYE("Small Wye Turnout"),
        STRAIGHT_3("3-Block Straight"), STRAIGHT_6("6-Block Straight"),
        STRAIGHT_8("8-Block Straight"), STRAIGHT_12("12-Block Straight"),
        CROSSOVER_90("90° Diamond Crossing"), CROSSOVER_45("45° Diamond Crossing"),
        TURNOUT_PARALLEL_LONG("Long Parallel Turnout"), SCISSORS("Scissors Crossover"),
        WYE_MEDIUM("Medium Wye Turnout"), BRIDGE_STRAIGHT("Straight Bridge Rail");

        final String title;

        Kind(String title) {
            this.title = title;
        }

        String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        int stackLimit() { return 64 / shape(rotation(0), true).cells().size(); }

        boolean turnout() {
            return this == TURNOUT_45 || this == TURNOUT_PARALLEL || this == TURNOUT_PARALLEL_LONG
                || wye() || this == SCISSORS;
        }

        boolean wye() { return this == WYE || this == WYE_MEDIUM; }

        boolean grade() { return this == GRADE_6 || this == GRADE_8; }

        boolean crossing() { return this == CROSSOVER_90 || this == CROSSOVER_45; }

        boolean mirrored() { return handed() || wye() || crossing(); }

        int[] activeRoutes(boolean powered, boolean left) {
            if (crossing()) return new int[]{0, 1};
            return new int[]{turnout() ? ((powered ? 1 : 0) ^ (wye() && !left ? 1 : 0)) : 0};
        }

        boolean flexibleStraight() {
            return this == STRAIGHT || this == STRAIGHT_3 || this == STRAIGHT_6
                || this == STRAIGHT_8 || this == STRAIGHT_12 || this == BRIDGE_STRAIGHT;
        }

        boolean hasBallast() { return this != BRIDGE_STRAIGHT; }

        List<Integer> rotations() {
            if (flexibleStraight()) return List.of(0, 2, 4, 6, 8, 10, 12, 14);
            if (this == DIAGONAL) return List.of(14, 2, 6, 10);
            return List.of(0, 4, 8, 12);
        }

        boolean bankedCurve() {
            return this == CURVE_SMALL || this == CURVE_MEDIUM || this == CURVE_LARGE;
        }

        boolean handed() {
            return this == CURVE_SMALL || this == CURVE_MEDIUM || this == CURVE_LARGE
                || this == TURNOUT_45 || this == TURNOUT_PARALLEL || this == TURNOUT_PARALLEL_LONG
                || this == CROSSOVER_45 || this == SCISSORS;
        }

        int rotation(int quarter) {
            return this == DIAGONAL ? (quarter * 4 + 14) & 15 : quarter * 4;
        }

        Shape shape(int rotation, boolean left) {
            return SHAPES[ordinal()][rotation][!left && mirrored() ? 1 : 0];
        }
    }

    record Shape(List<BlockPos> cells, List<Path> routes) {}

    static final class Path {
        private final List<Vec3> points;
        private final double[] distances;
        final double length;

        Path(List<Vec3> points) {
            this.points = List.copyOf(points);
            distances = new double[points.size()];
            for (int i = 1; i < points.size(); i++) {
                distances[i] = distances[i - 1] + points.get(i).distanceTo(points.get(i - 1));
            }
            length = distances[distances.length - 1];
        }

        List<Vec3> points() {
            return points;
        }

        Vec3 endpoint(boolean end) {
            return points.get(end ? points.size() - 1 : 0);
        }

        Vec3 point(double distance) {
            int index = interval(distance);
            double factor = (distance - distances[index - 1]) / (distances[index] - distances[index - 1]);
            return points.get(index - 1).lerp(points.get(index), Math.clamp(factor, 0, 1));
        }

        Vec3 tangent(double distance) {
            int index = interval(distance);
            return points.get(index).subtract(points.get(index - 1)).normalize();
        }

        Path section(double start, double end) {
            if (start == 0 && end == length) return this;
            List<Vec3> section = new ArrayList<>();
            section.add(point(start));
            for (int i = 1; i < distances.length - 1; i++) {
                if (distances[i] > start && distances[i] < end) section.add(points.get(i));
            }
            section.add(point(end));
            return new Path(section);
        }

        double project(Vec3 position) {
            double nearest = Double.POSITIVE_INFINITY;
            double offset = 0;
            for (int i = 1; i < points.size(); i++) {
                Vec3 start = points.get(i - 1);
                Vec3 segment = points.get(i).subtract(start);
                double t = Math.clamp(position.subtract(start).dot(segment) / segment.lengthSqr(), 0, 1);
                double error = start.add(segment.scale(t)).distanceToSqr(position);
                if (error < nearest) {
                    nearest = error;
                    offset = distances[i - 1] + t * (distances[i] - distances[i - 1]);
                }
            }
            return offset;
        }

        private int interval(double distance) {
            int index = java.util.Arrays.binarySearch(distances, distance);
            return Math.clamp(index < 0 ? -index - 1 : index + 1, 1, distances.length - 1);
        }
    }

    private static final Shape[][][] SHAPES = new Shape[Kind.values().length][16][2];

    static {
        for (Kind kind : Kind.values()) {
            for (int rotation : kind.rotations()) {
                boolean diagonal = kind.flexibleStraight() && rotation % 4 == 2;
                int quarter = kind == Kind.DIAGONAL || diagonal ? ((rotation + 2) & 15) / 4 : rotation / 4;
                List<Path> paths = diagonal ? List.of(diagonal(straightBlocks(kind))) : canonical(kind);
                for (int mirror = 0; mirror < 2; mirror++) {
                    Shape shape = orient(kind, paths, quarter, mirror == 1);
                    int aliases = kind.flexibleStraight() ? 2 : 4;
                    for (int state = 0; state < aliases; state++) {
                        SHAPES[kind.ordinal()][(rotation + state) & 15][mirror] = shape;
                    }
                }
            }
        }
    }

    private TrackGeometry() {}

    static Vec3 origin(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + RAIL_HEIGHT, pos.getZ() + 0.5);
    }

    private static List<Path> canonical(Kind kind) {
        return switch (kind) {
            case STRAIGHT, STRAIGHT_3, STRAIGHT_6, STRAIGHT_8, STRAIGHT_12, BRIDGE_STRAIGHT ->
                List.of(straight(straightBlocks(kind) - 1));
            case DIAGONAL -> List.of(diagonal(1));
            case CURVE_SMALL -> List.of(curve(4, 2, true));
            case CURVE_MEDIUM -> List.of(curve(8, 3, true));
            case CURVE_LARGE -> List.of(curve(12, 5, true));
            case GRADE_6 -> List.of(grade(6));
            case GRADE_8 -> List.of(grade(8));
            case TURNOUT_45 -> List.of(straight(4), curve(4, 2, true));
            case TURNOUT_PARALLEL -> List.of(straight(8), curve(8, 3, false));
            case WYE -> List.of(curve(4, 2, true), curve(4, -2, true));
            case WYE_MEDIUM -> List.of(curve(8, 3, true), curve(8, -3, true));
            case TURNOUT_PARALLEL_LONG -> List.of(straight(14), curve(14, 3, false));
            case CROSSOVER_90 -> List.of(line(p(0, 0, -2.5), p(0, 0, 2.5)), line(p(-2.5, 0, 0), p(2.5, 0, 0)));
            case CROSSOVER_45 -> List.of(line(p(0, 0, -2.5), p(0, 0, 2.5)), line(p(-2.5, 0, -2.5), p(2.5, 0, 2.5)));
            case SCISSORS -> List.of(straight(12), offset(straight(12), 3), scissorsRoute(false), scissorsRoute(true));
        };
    }

    private static int straightBlocks(Kind kind) {
        return switch (kind) {
            case STRAIGHT_3 -> 3;
            case STRAIGHT_6 -> 6;
            case STRAIGHT_8 -> 8;
            case STRAIGHT_12 -> 12;
            default -> 1;
        };
    }

    private static Path diagonal(int blocks) {
        return line(p(-0.5, 0, -0.5), p(blocks - 0.5, 0, blocks - 0.5));
    }

    private static Path offset(Path path, double x) {
        return new Path(path.points().stream().map(point -> point.add(x, 0, 0)).toList());
    }

    private static Path scissorsRoute(boolean opposite) {
        List<Vec3> points = new ArrayList<>();
        points.add(p(0, 0, -0.5));
        for (int i = 0; i <= SAMPLES; i++) {
            double t = i / (double) SAMPLES;
            // An eight-block eased crossing puts the two centerlines at about 31 degrees apart.
            points.add(p(3 * t * t * (3 - 2 * t), 0, 2 + 8 * t));
        }
        points.add(p(3, 0, 12.5));
        return new Path(opposite ? points.stream().map(point -> p(3 - point.x, 0, point.z)).toList() : points);
    }

    private static Path straight(int run) {
        return line(p(0, 0, -0.5), p(0, 0, run + 0.5));
    }

    private static Path grade(int run) {
        return new Path(List.of(p(0, 0, -0.5), p(0, 0, 0), p(0, 1, run), p(0, 1, run + 0.5)));
    }

    private static Path curve(int run, int offset, boolean diagonalExit) {
        Vec3 start = Vec3.ZERO;
        Vec3 end = p(offset, 0, run);
        int direction = Integer.signum(offset);
        Vec3 beforeEnd = diagonalExit ? end.subtract(p(direction * Math.abs(offset) * 0.72, 0,
            Math.abs(offset) * 0.72)) : end.subtract(p(0, 0, run / 3.0));
        Vec3 afterStart = p(0, 0, run / 3.0);
        List<Vec3> points = new ArrayList<>();
        points.add(p(0, 0, -0.5));
        for (int i = 0; i <= SAMPLES; i++) {
            double t = i / (double) SAMPLES;
            double u = 1 - t;
            points.add(start.scale(u * u * u).add(afterStart.scale(3 * u * u * t))
                .add(beforeEnd.scale(3 * u * t * t)).add(end.scale(t * t * t)));
        }
        points.add(end.add(p(diagonalExit ? direction * 0.5 : 0, 0, 0.5)));
        return new Path(points);
    }

    private static Path line(Vec3 a, Vec3 b) {
        return new Path(List.of(a, b));
    }

    private static Shape orient(Kind kind, List<Path> canonical, int quarter, boolean mirror) {
        LinkedHashSet<BlockPos> cells = new LinkedHashSet<>();
        cells.add(BlockPos.ZERO);
        for (Path path : canonical) {
            int steps = (int) Math.ceil(path.length * 80);
            for (int i = 1; i < steps; i++) {
                BlockPos cell = cell(path.point(path.length * i / steps));
                if (kind.grade()) {
                    int run = kind == Kind.GRADE_6 ? 6 : 8;
                    cell = new BlockPos(cell.getX(), cell.getZ() == run ? 1 : 0, cell.getZ());
                }
                cells.add(cell);
            }
        }
        List<BlockPos> orientedCells = cells.stream()
            .map(pos -> BlockPos.containing(transform(Vec3.atLowerCornerOf(pos), quarter, mirror)))
            .toList();
        List<Path> paths = canonical.stream().map(path -> new Path(path.points().stream()
            .map(point -> transform(point, quarter, mirror)).toList())).toList();
        return new Shape(orientedCells, paths);
    }

    static BlockPos cell(Vec3 pos) {
        return BlockPos.containing(pos.x + 0.5, pos.y + 1e-7, pos.z + 0.5);
    }

    static Vec3 transform(Vec3 point, int quarter, boolean mirror) {
        double x = mirror ? -point.x : point.x;
        double z = point.z;
        return switch (quarter) {
            case 1 -> p(-z, point.y, x);
            case 2 -> p(-x, point.y, -z);
            case 3 -> p(z, point.y, -x);
            default -> p(x, point.y, z);
        };
    }

    private static Vec3 p(double x, double y, double z) {
        return new Vec3(x, y, z);
    }
}
