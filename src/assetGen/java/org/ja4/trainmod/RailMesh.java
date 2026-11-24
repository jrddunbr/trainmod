package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.DoubleUnaryOperator;
import java.util.function.DoubleBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Generates continuous cross sections. Neighboring spans share edges instead of overlapping cuboids. */
final class RailMesh {
    static final double TIE_HEIGHT = 0.172;
    static final double TIE_HALF_WIDTH = 0.064;
    private static final double POINT_END = 2.60;
    private static final double GAUGE = CurveBanking.GAUGE / 2;
    private static final DoubleBinaryOperator FLAT = (distance, lateral) -> 0;
    private static final DoubleUnaryOperator SAME_HEIGHT = distance -> 0;
    private static final RailProfile.Section[] RAIL = RailProfile.SECTIONS;

    private RailMesh() {}

    record Tie(BlockPos owner, int route, int index, double distance, Vec3 center, Vec3 tangent, double halfLength) {}

    static Map<BlockPos, Mesh> fixed(TrackGeometry.Kind kind, TrackGeometry.Shape shape, int banking, List<Tie> ties) {
        Map<BlockPos, Mesh> meshes = new LinkedHashMap<>();
        var layout = SwitchLayout.of(kind, shape);
        List<Lane> lanes = lanes(shape, layout);
        List<Frog> frogs = frogs(shape, lanes);
        for (Lane lane : lanes) {
            var path = shape.routes().get(lane.route);
            var bank = bank(kind, path, banking);
            double cursor = lane.from;
            var crossings = frogs.stream().filter(frog -> frog.on(lane) != null)
                .sorted(java.util.Comparator.comparingDouble(frog -> frog.on(lane).distance)).toList();
            for (Frog frog : crossings) {
                double at = frog.on(lane).distance;
                double gapStart = Math.max(cursor, at - frog.span);
                if (gapStart > cursor) stockRail(shape, path, meshes, cursor, gapStart, lane.side, bank);
                cursor = Math.max(cursor, at + frog.span);
            }
            if (cursor < lane.to) stockRail(shape, path, meshes, cursor, lane.to, lane.side, bank);
        }
        for (Tie tie : ties) {
            var bank = bank(kind, shape.routes().get(tie.route), banking);
            meshes.computeIfAbsent(tie.owner, Mesh::new).tiePlates(tie.center, tie.tangent,
                across -> bank.applyAsDouble(tie.distance, across));
        }
        for (Frog frog : frogs) {
            casting(shape, meshes, frog);
            checkRail(shape, meshes, frog, frog.first, frogs);
            checkRail(shape, meshes, frog, frog.second, frogs);
        }
        for (var panel : layout.panels()) {
            var blade = panel.first();
            for (double fraction : new double[]{0.15, 0.30, 0.45}) {
                double distance = blade.distance(fraction);
                Vec3 point = blade.path().point(distance);
                Mesh mesh = meshes.computeIfAbsent(owner(shape, point), Mesh::new);
                mesh.box("web", point, horizontal(blade.path().tangent(distance)), -0.31, 0.31,
                    0.1727, 0.176, 0.055, SAME_HEIGHT);
            }
        }
        return meshes;
    }

    static List<Tie> ties(TrackGeometry.Kind kind, TrackGeometry.Shape shape) {
        List<Tie> ties = new ArrayList<>();
        for (int route = 0; route < shape.routes().size(); route++) {
            var path = shape.routes().get(route);
            double start = bedStart(kind, shape, route);
            double end = bedEnd(kind, route, path);
            int count = (int) Math.ceil(path.length / 0.36);
            for (int index = 0; index < count; index++) {
                double at = path.length * (index + 0.5) / count;
                if (at < start || at > end) continue;
                Vec3 center = path.point(at);
                // Keep one set of sleepers where routes share the same footprint.
                if (sharesBed(kind, shape, route, center)) continue;
                double halfLength = kind.turnout() && route == 0 && at < POINT_END + 0.4 ? 0.64 : 0.44;
                ties.add(new Tie(owner(shape, center), route, index, at, center, horizontal(path.tangent(at)), halfLength));
            }
        }
        return ties;
    }

    private static boolean sharesBed(TrackGeometry.Kind kind, TrackGeometry.Shape shape, int route, Vec3 center) {
        for (int other = 0; other < route; other++) {
            var previous = shape.routes().get(other);
            double projected = previous.project(center);
            if (projected >= bedStart(kind, shape, other) && projected <= bedEnd(kind, other, previous)
                && center.distanceToSqr(previous.point(projected)) < 0.44) return true;
        }
        return false;
    }

    static Map<BlockPos, Mesh> woodTies(TrackGeometry.Kind kind, TrackGeometry.Shape shape, List<Tie> ties,
                                        int banking, int pattern) {
        Map<BlockPos, Mesh> meshes = new LinkedHashMap<>();
        for (Tie tie : ties) {
            var variation = TieVariation.forTie(pattern, tie.route, tie.index);
            double angle = Math.toRadians(variation.yaw());
            Vec3 rotated = tie.tangent.yRot((float) angle);
            double halfLength = tie.halfLength * variation.length();
            var bank = bank(kind, shape.routes().get(tie.route), banking);
            double acrossScale = Math.cos(angle);
            meshes.computeIfAbsent(tie.owner, Mesh::new).box("tie", tie.center, rotated, -halfLength, halfLength,
                kind.hasBallast() ? 0.126 : 0, TIE_HEIGHT, TIE_HALF_WIDTH,
                across -> bank.applyAsDouble(tie.distance, across * acrossScale));
        }
        return meshes;
    }

    private static double bedStart(TrackGeometry.Kind kind, TrackGeometry.Shape shape, int route) {
        if (kind == TrackGeometry.Kind.SCISSORS) return route >= 2 ? 3.43 : 0;
        if (!kind.turnout() || route == 0) return 0;
        var path = shape.routes().get(route);
        var stock = shape.routes().getFirst();
        for (double distance = 0.5; distance < POINT_END; distance += 0.05) {
            Vec3 point = path.point(distance);
            if (point.distanceToSqr(stock.point(stock.project(point))) > 0.009) return distance;
        }
        return POINT_END;
    }

    private static double bedEnd(TrackGeometry.Kind kind, int route, TrackGeometry.Path path) {
        return path.length - (kind == TrackGeometry.Kind.SCISSORS && route >= 2 ? 3.43 : 0);
    }

    private static Vec3 horizontal(Vec3 tangent) { return tangent.multiply(1, 0, 1).normalize(); }

    private static Vec3 lateral(Vec3 heading) { return new Vec3(heading.z, 0, -heading.x); }

    private record Lane(int route, double side, double from, double to) {}

    private record Crossing(Lane lane, double distance, Vec3 heading) {
        Vec3 gaugeSide() { return lateral(heading).scale(-Math.signum(lane.side)); }
    }

    private record Frog(Crossing first, Crossing second, Vec3 point, double span) {
        Crossing on(Lane lane) {
            if (first.lane.equals(lane)) return first;
            return second.lane.equals(lane) ? second : null;
        }
    }

    private record RailEdge(double from, double to, Vec3 start, Vec3 end) {}

    private static List<Lane> lanes(TrackGeometry.Shape shape, SwitchLayout layout) {
        List<Lane> rails = new ArrayList<>();
        for (int route = 0; route < shape.routes().size(); route++) {
            for (double side : new double[]{-GAUGE, GAUGE}) {
                rails.add(new Lane(route, side, layout.fixedStart(route, side),
                    layout.fixedEnd(route, side, shape.routes().get(route).length)));
            }
        }
        return rails;
    }

    private static List<RailEdge> edges(TrackGeometry.Shape shape, Lane lane) {
        var path = shape.routes().get(lane.route);
        List<RailEdge> result = new ArrayList<>();
        int count = (int) Math.ceil((lane.to - lane.from) / 0.10);
        for (int i = 0; i < count; i++) {
            double from = lane.from + (lane.to - lane.from) * i / count;
            double to = lane.from + (lane.to - lane.from) * (i + 1) / count;
            result.add(new RailEdge(from, to, SwitchLayout.railPoint(path, from, lane.side),
                SwitchLayout.railPoint(path, to, lane.side)));
        }
        return result;
    }

    private static List<Frog> frogs(TrackGeometry.Shape shape, List<Lane> lanes) {
        List<Frog> frogs = new ArrayList<>();
        var edges = lanes.stream().map(lane -> edges(shape, lane)).toList();
        for (int i = 0; i < lanes.size(); i++) {
            for (int j = i + 1; j < lanes.size(); j++) {
                Lane first = lanes.get(i), second = lanes.get(j);
                if (first.route == second.route) continue;
                for (RailEdge a : edges.get(i)) {
                    for (RailEdge b : edges.get(j)) {
                        var frog = intersection(first, second, a, b);
                        if (frog != null && frogs.stream().noneMatch(found -> found.point.distanceToSqr(frog.point) < 0.0064)) {
                            frogs.add(frog);
                        }
                    }
                }
            }
        }
        return frogs;
    }

    private static Frog intersection(Lane first, Lane second, RailEdge a, RailEdge b) {
        Vec3 deltaA = a.end.subtract(a.start), deltaB = b.end.subtract(b.start);
        double divisor = cross(deltaA, deltaB);
        double sine = Math.abs(divisor) / (deltaA.length() * deltaB.length());
        if (sine < 0.14) return null;
        Vec3 difference = b.start.subtract(a.start);
        double alongA = cross(difference, deltaB) / divisor;
        double alongB = cross(difference, deltaA) / divisor;
        if (alongA < 0 || alongA > 1 || alongB < 0 || alongB > 1) return null;
        double distanceA = a.from + alongA * (a.to - a.from);
        double distanceB = b.from + alongB * (b.to - b.from);
        double span = Math.clamp(0.082 / sine, 0.106, 0.38);
        if (distanceA - span < first.from || distanceA + span > first.to
            || distanceB - span < second.from || distanceB + span > second.to) return null;
        return new Frog(new Crossing(first, distanceA, horizontal(deltaA)), new Crossing(second, distanceB, horizontal(deltaB)),
            a.start.add(deltaA.scale(alongA)), span);
    }

    private static double cross(Vec3 a, Vec3 b) { return a.x * b.z - a.z * b.x; }

    private static void casting(TrackGeometry.Shape shape, Map<BlockPos, Mesh> meshes, Frog frog) {
        Vec3 center = frog.point;
        Vec3 along = frog.first.heading;
        Vec3 across = lateral(along);
        List<Vec3> footprint = List.of(across.scale(-0.127).subtract(along.scale(frog.span)),
            across.scale(-0.127).add(along.scale(frog.span)), across.scale(0.127).add(along.scale(frog.span)),
            across.scale(0.127).subtract(along.scale(frog.span)));
        footprint = strip(footprint, frog.second.heading, frog.span);
        footprint = strip(footprint, lateral(frog.second.heading), 0.127);
        Mesh mesh = meshes.computeIfAbsent(owner(shape, center), Mesh::new);
        mesh.prism("web", center, footprint, 0.175, 0.206);
        for (boolean gaugeA : new boolean[]{false, true}) {
            for (boolean gaugeB : new boolean[]{false, true}) {
                // Cut both flangeways through the casting down to the web. The remaining
                // raised sectors form the noses and the wing-rail running surfaces.
                var wing = flangeway(footprint, frog.first.gaugeSide(), gaugeA);
                wing = flangeway(wing, frog.second.gaugeSide(), gaugeB);
                mesh.prism("frog", center, wing, 0.204, 0.25);
            }
        }
    }

    private static List<Vec3> strip(List<Vec3> points, Vec3 normal, double distance) {
        return clip(clip(points, normal, distance), normal.scale(-1), distance);
    }

    private static List<Vec3> flangeway(List<Vec3> points, Vec3 gaugeSide, boolean beyond) {
        return beyond ? clip(points, gaugeSide.scale(-1), -0.077) : clip(points, gaugeSide, 0.031);
    }

    private static List<Vec3> clip(List<Vec3> polygon, Vec3 normal, double offset) {
        if (polygon.isEmpty()) return polygon;
        List<Vec3> result = new ArrayList<>();
        Vec3 previous = polygon.getLast();
        double old = previous.dot(normal) - offset;
        for (Vec3 point : polygon) {
            double next = point.dot(normal) - offset;
            if ((old < 0) != (next < 0)) result.add(previous.lerp(point, old / (old - next)));
            if (next < 0) result.add(point);
            previous = point;
            old = next;
        }
        return result;
    }

    private static void checkRail(TrackGeometry.Shape shape, Map<BlockPos, Mesh> meshes, Frog frog, Crossing crossing,
                                  List<Frog> frogs) {
        Lane lane = crossing.lane;
        var path = shape.routes().get(lane.route);
        double size = frog.span + 0.28;
        double start = Math.max(0, crossing.distance - size), end = Math.min(path.length, crossing.distance + size);
        if (frogs.stream().anyMatch(other -> opposingFrog(other.first, lane, start, end)
            || opposingFrog(other.second, lane, start, end))) return;
        sweep(shape, path, meshes, start, end, at -> -lane.side + Math.copySign(
            0.109 + Math.max(0, Math.abs(at - crossing.distance) / size - 0.68) * 0.10, lane.side), d -> 0.53,
            "guard", FLAT, -0.019);
    }

    private static boolean opposingFrog(Crossing crossing, Lane lane, double start, double end) {
        return crossing.lane.route == lane.route && crossing.lane.side != lane.side
            && crossing.distance > start - 0.18 && crossing.distance < end + 0.18;
    }

    private static void stockRail(TrackGeometry.Shape shape, TrackGeometry.Path path, Map<BlockPos, Mesh> meshes,
                                  double start, double end, double side, DoubleBinaryOperator bank) {
        sweep(shape, path, meshes, start, end, distance -> side, distance -> 1, "steel", bank, 0);
    }

    /** The covered foundation is rendered with the rail so both shoulders follow the same path. */
    static Map<BlockPos, Mesh> roadbed(TrackGeometry.Kind kind, TrackGeometry.Shape shape, boolean raised, int banking) {
        Map<BlockPos, Mesh> meshes = new LinkedHashMap<>();
        var bed = BallastProfile.bed(raised);
        for (int route = 0; route < shape.routes().size(); route++) {
            TrackGeometry.Path path = shape.routes().get(route);
            double start = bedStart(kind, shape, route);
            double end = bedEnd(kind, route, path);
            DoubleUnaryOperator lift = route == 0 || kind == TrackGeometry.Kind.SCISSORS && route == 1 ? SAME_HEIGHT : distance ->
                0.003 * Math.min(1, Math.min(distance - start, end - distance) * 2);
            DoubleBinaryOperator bank = bank(kind, path, banking);
            List<Double> cuts = roadbedCuts(path, start, end);
            BlockPos prior = null;
            for (int i = 1; i < cuts.size(); i++) {
                double from = cuts.get(i - 1);
                double to = cuts.get(i);
                BlockPos cell = owner(shape, path.point((from + to) / 2));
                Mesh mesh = meshes.computeIfAbsent(cell, pos -> new Mesh(pos, false));
                boolean highGrade = kind.grade() && cell.getY() == 1;
                double base = cell.getY() - (raised || highGrade ? 1 : 0);
                boolean capStart = (raised || highGrade) && !cell.equals(prior) && from > 1e-7;
                boolean capEnd = (raised || highGrade) && to < path.length - 1e-7 && (i == cuts.size() - 1
                    || !cell.equals(owner(shape, path.point((to + cuts.get(i + 1)) / 2))));
                for (int j = 1; j < bed.length; j++) {
                    Vec3 a = roadbedPoint(path, from, bed[j - 1], lift, cell, raised, kind, bank);
                    Vec3 b = roadbedPoint(path, to, bed[j - 1], lift, cell, raised, kind, bank);
                    Vec3 c = roadbedPoint(path, to, bed[j], lift, cell, raised, kind, bank);
                    Vec3 d = roadbedPoint(path, from, bed[j], lift, cell, raised, kind, bank);
                    mesh.gravel(a, b, c, d, bed[j - 1].across() + 1.5, bed[j].across() + 1.5, from, to);
                    if (capStart) {
                        mesh.face("ballast", a, d, d.with(Direction.Axis.Y, base),
                            a.with(Direction.Axis.Y, base));
                    }
                    if (capEnd) {
                        mesh.face("ballast", b, b.with(Direction.Axis.Y, base),
                            c.with(Direction.Axis.Y, base), c);
                    }
                }
                prior = cell;
            }
        }
        return meshes;
    }

    private static Vec3 roadbedPoint(TrackGeometry.Path path, double distance, BallastProfile.Section profile,
                                     DoubleUnaryOperator lift, BlockPos owner, boolean raised, TrackGeometry.Kind kind,
                                     DoubleBinaryOperator bank) {
        Vec3 center = path.point(distance);
        Vec3 heading = path.point(distance + 0.02).subtract(path.point(distance - 0.02));
        Vec3 point = center.add(lateral(horizontal(heading)).scale(profile.across()));
        double weight = (profile.height() - BallastProfile.FOOT) / (BallastProfile.CREST - BallastProfile.FOOT);
        double bankedCrest = center.y + BallastProfile.CREST
            + bank.applyAsDouble(distance, Math.clamp(profile.across(), -BallastProfile.CORE_HALF_WIDTH, BallastProfile.CORE_HALF_WIDTH));
        double ground = (raised ? owner.getY() - 1 : kind.grade() ? 0 : center.y) + BallastProfile.FOOT;
        return point.with(Direction.Axis.Y, Math.max(ground, ground + (bankedCrest - ground) * weight)
            + lift.applyAsDouble(distance));
    }

    private static DoubleBinaryOperator bank(TrackGeometry.Kind kind, TrackGeometry.Path path, int banking) {
        return banking < 0 ? FLAT : (distance, across) -> CurveBanking.lift(kind, path, distance, banking, across);
    }

    private static List<Double> roadbedCuts(TrackGeometry.Path path, double start, double end) {
        TreeSet<Double> cuts = new TreeSet<>();
        cuts.add(start);
        cuts.add(end);
        for (int tile = (int) Math.floor(start) + 1; tile < end; tile++) cuts.add((double) tile);
        int count = (int) Math.ceil((end - start) / 0.26);
        for (int i = 1; i < count; i++) {
            cuts.add(start + (end - start) * i / count);
        }
        double offset = 0;
        for (int i = 1; i < path.points().size(); i++) {
            Vec3 a = path.points().get(i - 1);
            Vec3 b = path.points().get(i);
            double length = a.distanceTo(b);
            addCellCuts(cuts, start, end, offset, length, a.x, b.x);
            addCellCuts(cuts, start, end, offset, length, a.z, b.z);
            offset += length;
            if (offset > start && offset < end) {
                cuts.add(offset);
            }
        }
        List<Double> distinct = new ArrayList<>();
        for (double cut : cuts) {
            if (distinct.isEmpty() || cut - distinct.getLast() > 1e-7) {
                distinct.add(cut);
            }
        }
        return distinct;
    }

    private static void addCellCuts(TreeSet<Double> cuts, double start, double end, double offset, double length,
                                    double a, double b) {
        double min = Math.min(a, b);
        double max = Math.max(a, b);
        for (double boundary = Math.floor(min + 0.5) + 0.5; boundary < max - 1e-9; boundary++) {
            double distance = offset + length * (boundary - a) / (b - a);
            if (distance > start && distance < end && boundary > min + 1e-9) {
                cuts.add(distance);
            }
        }
    }

    private static void sweep(TrackGeometry.Shape shape, TrackGeometry.Path path, Map<BlockPos, Mesh> meshes,
                              double start, double end, DoubleUnaryOperator side, DoubleUnaryOperator thickness,
                              String material, DoubleBinaryOperator bank, double verticalOffset) {
        List<Double> cuts = roadbedCuts(path, start, end);
        DoubleUnaryOperator height = ignored -> verticalOffset;
        for (int i = 1; i < cuts.size(); i++) {
            double from = cuts.get(i - 1);
            double to = cuts.get(i);
            Mesh mesh = meshes.computeIfAbsent(owner(shape, path.point((from + to) / 2)), Mesh::new);
            for (int j = 1; j < RAIL.length; j++) {
                var a = RAIL[j - 1];
                var b = RAIL[j];
                mesh.face(Math.max(a.height(), b.height()) < 0.229 ? "web" : material,
                    section(path, from, a, side.applyAsDouble(from), height, thickness, bank),
                    section(path, to, a, side.applyAsDouble(to), height, thickness, bank),
                    section(path, to, b, side.applyAsDouble(to), height, thickness, bank),
                    section(path, from, b, side.applyAsDouble(from), height, thickness, bank));
            }
        }
    }

    private static Vec3 section(TrackGeometry.Path path, double at, RailProfile.Section profile, double side,
                                DoubleUnaryOperator height, DoubleUnaryOperator thickness, DoubleBinaryOperator bank) {
        Vec3 position = path.point(at);
        Vec3 tangent = path.tangent(at).multiply(1, 0, 1).normalize();
        double across = side + profile.across() * thickness.applyAsDouble(at);
        return position.add(new Vec3(tangent.z, 0, -tangent.x).scale(across))
            .add(0, profile.height() + height.applyAsDouble(at) + bank.applyAsDouble(at, across), 0);
    }

    private static BlockPos owner(TrackGeometry.Shape shape, Vec3 point) {
        BlockPos cell = TrackGeometry.cell(point);
        return shape.cells().stream().filter(pos -> pos.getX() == cell.getX() && pos.getZ() == cell.getZ())
            .findFirst().orElseGet(() -> shape.cells().stream()
                .min(java.util.Comparator.comparingDouble(pos -> Vec3.atLowerCornerOf(pos).distanceToSqr(point)))
                .orElseThrow());
    }

    static final class Mesh {
        private final BlockPos owner;
        private final boolean shadeQuads;
        private final StringBuilder text;
        private int vertex = 1;
        private int uv = 5;
        private String currentMaterial;

        Mesh(BlockPos owner) { this(owner, true); }

        Mesh(BlockPos owner, boolean shadeQuads) {
            this(owner, shadeQuads, "railway");
        }

        Mesh(BlockPos owner, boolean shadeQuads, String materialLibrary) {
            this.owner = owner;
            this.shadeQuads = shadeQuads;
            String object = materialLibrary.equals("railway") ? "rail" : materialLibrary;
            text = new StringBuilder("mtllib trainmod:models/block/" + materialLibrary + ".mtl\no " + object + "\n"
                + "vt 0 0\nvt 0 1\nvt 1 1\nvt 1 0\n");
        }

        boolean shadeQuads() { return shadeQuads; }

        void gravel(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double uStart, double uEnd, double vStart, double vEnd) {
            double uTile = Math.floor(uStart + 1e-8);
            double vTile = Math.floor(vStart + 1e-8);
            mapped("ballast", a, b, c, d, uStart - uTile, vStart - vTile, uEnd - uTile, vEnd - vTile);
        }

        void mapped(String material, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                    double uStart, double vStart, double uEnd, double vEnd) {
            for (double[] coordinates : new double[][]{{uStart, vStart}, {uStart, vEnd}, {uEnd, vEnd}, {uEnd, vStart}}) {
                text.append("vt ").append(decimal(coordinates[0])).append(' ')
                    .append(decimal(coordinates[1])).append('\n');
            }
            face(material, a, b, c, d, uv);
            uv += 4;
        }

        void face(String material, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
            face(material, a, b, c, d, 1);
        }

        private void face(String material, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int uvIndex) {
            if (!material.equals(currentMaterial)) {
                text.append("usemtl ").append(material).append('\n');
                currentMaterial = material;
            }
            for (Vec3 point : new Vec3[]{a, b, c, d}) {
                text.append("v ").append(decimal(point.x - owner.getX() + 0.5)).append(' ')
                    .append(decimal(point.y - owner.getY())).append(' ')
                    .append(decimal(point.z - owner.getZ() + 0.5)).append('\n');
            }
            text.append("f");
            for (int i = 0; i < 4; i++) text.append(' ').append(vertex++).append('/').append(uvIndex + i);
            text.append('\n');
        }

        void prism(String material, Vec3 center, List<Vec3> points, double low, double high) {
            if (points.size() < 3) return;
            Vec3 anchor = points.getFirst().add(center).add(0, high, 0);
            for (int i = 1; i + 1 < points.size(); i++) {
                Vec3 b = points.get(i).add(center).add(0, high, 0);
                Vec3 c = points.get(i + 1).add(center).add(0, high, 0);
                if (b.subtract(anchor).cross(c.subtract(anchor)).lengthSqr() > 1e-12) {
                    face(material, anchor, b, c, c);
                }
            }
            Vec3 down = new Vec3(0, low - high, 0);
            for (int i = 0; i < points.size(); i++) {
                Vec3 a = points.get(i).add(center).add(0, high, 0);
                Vec3 b = points.get((i + 1) % points.size()).add(center).add(0, high, 0);
                if (a.distanceToSqr(b) > 1e-12) face(material, a.add(down), b.add(down), b, a);
            }
        }

        private static String decimal(double value) {
            return Double.toString(Math.round(value * 100_000) / 100_000.0);
        }

        void tiePlates(Vec3 center, Vec3 heading, DoubleUnaryOperator bank) {
            for (double side : new double[]{-GAUGE, GAUGE}) {
                box("web", center, heading, side - 0.055, side + 0.055, 0.1725, 0.183, 0.052, bank);
            }
        }

        private void box(String material, Vec3 center, Vec3 heading, double min, double max,
                         double bottom, double top, double halfDepth, DoubleUnaryOperator bank) {
            Vec3 left = new Vec3(heading.z, 0, -heading.x);
            Vec3 back = heading.scale(-halfDepth);
            Vec3 front = heading.scale(halfDepth);
            Vec3 a = center.add(back).add(left.scale(min)).add(0, top + bank.applyAsDouble(min), 0);
            Vec3 b = center.add(front).add(left.scale(min)).add(0, top + bank.applyAsDouble(min), 0);
            Vec3 c = center.add(front).add(left.scale(max)).add(0, top + bank.applyAsDouble(max), 0);
            Vec3 d = center.add(back).add(left.scale(max)).add(0, top + bank.applyAsDouble(max), 0);
            Vec3 down = new Vec3(0, bottom - top, 0);
            if (material.equals("tie")) {
                double width = max - min;
                double depth = 8 * halfDepth;
                double height = 4 * (top - bottom);
                int sections = (int) Math.ceil(width);
                for (int i = 0; i < sections; i++) {
                    double u = Math.min(1, width - i);
                    Vec3 p = a.lerp(d, i / width);
                    Vec3 q = b.lerp(c, i / width);
                    Vec3 r = b.lerp(c, (i + u) / width);
                    Vec3 s = a.lerp(d, (i + u) / width);
                    mapped(material, p, q, r, s, 0, 0, u, depth / 4);
                    mapped(material, p, s, s.add(down), p.add(down), 0, 0, height / 4, u);
                    mapped(material, q.add(down), r.add(down), r, q, 0, 0, height / 4, u);
                    if (bottom == 0) {
                        mapped(material, q.add(down), p.add(down), s.add(down), r.add(down), 0, 0, u, depth / 4);
                    }
                }
                mapped(material, a.add(down), b.add(down), b, a, 0, 0, height / 4, depth / 4);
                mapped(material, d, c, c.add(down), d.add(down), 0, 0, height / 4, depth / 4);
            } else {
                face(material, a, b, c, d);
                face(material, a.add(down), b.add(down), b, a);
                face(material, d, c, c.add(down), d.add(down));
                face(material, a, d, d.add(down), a.add(down));
                face(material, b.add(down), c.add(down), c, b);
            }
        }

        String contents() { return text.toString(); }
    }
}
