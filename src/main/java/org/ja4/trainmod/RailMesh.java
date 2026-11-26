package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.List;
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

    enum Surface { STEEL, WEB, TIE, BALLAST }

    @FunctionalInterface
    interface QuadSink {
        void add(BlockPos cell, Surface surface, SurfaceUv.Vertex a, SurfaceUv.Vertex b, SurfaceUv.Vertex c, SurfaceUv.Vertex d);
    }

    static void fixed(TrackGeometry.Kind kind, TrackGeometry.Shape shape, int banking, List<Tie> ties, QuadSink sink) {
        var layout = SwitchLayout.of(kind, shape);
        List<Lane> lanes = lanes(shape, layout);
        List<Frog> frogs = shape.routes().size() > 1 ? frogs(shape, lanes) : List.of();
        for (Lane lane : lanes) {
            var path = shape.routes().get(lane.route);
            var bank = bank(kind, path, banking);
            double cursor = lane.from;
            var crossings = frogs.stream().filter(frog -> frog.on(lane) != null)
                .sorted(java.util.Comparator.comparingDouble(frog -> frog.on(lane).distance)).toList();
            for (Frog frog : crossings) {
                double at = frog.on(lane).distance;
                double gapStart = Math.max(cursor, at - frog.span);
                if (gapStart > cursor) stockRail(shape, path, sink, cursor, gapStart, lane.side, bank);
                cursor = Math.max(cursor, at + frog.span);
            }
            if (cursor < lane.to) stockRail(shape, path, sink, cursor, lane.to, lane.side, bank);
        }
        for (Tie tie : ties) {
            var bank = bank(kind, shape.routes().get(tie.route), banking);
            new Mesh(tie.owner, sink).tiePlates(tie.center, tie.tangent,
                across -> bank.applyAsDouble(tie.distance, across));
        }
        for (Frog frog : frogs) {
            casting(shape, sink, frog);
            checkRail(shape, sink, frog, frog.first, frogs);
            checkRail(shape, sink, frog, frog.second, frogs);
        }
        for (var panel : layout.panels()) {
            var blade = panel.first();
            for (double fraction : new double[]{0.15, 0.30, 0.45}) {
                double distance = blade.distance(fraction);
                Vec3 point = blade.path().point(distance);
                Mesh mesh = new Mesh(shape.owner(point), sink);
                mesh.box(Surface.WEB, point, horizontal(blade.path().tangent(distance)), -0.31, 0.31,
                    0.1727, 0.176, 0.055, SAME_HEIGHT);
            }
        }
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
                ties.add(new Tie(shape.owner(center), route, index, at, center, horizontal(path.tangent(at)), halfLength));
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

    static void woodTies(TrackGeometry.Kind kind, TrackGeometry.Shape shape, List<Tie> ties,
                         int banking, int pattern, QuadSink sink) {
        for (Tie tie : ties) {
            var variation = TieVariation.forTie(pattern, tie.route, tie.index);
            double angle = Math.toRadians(variation.yaw());
            Vec3 rotated = tie.tangent.yRot((float) angle);
            double halfLength = tie.halfLength * variation.length();
            var bank = bank(kind, shape.routes().get(tie.route), banking);
            double acrossScale = Math.cos(angle);
            new Mesh(tie.owner, sink).box(Surface.TIE, tie.center, rotated, -halfLength, halfLength,
                0, TIE_HEIGHT, TIE_HALF_WIDTH,
                across -> bank.applyAsDouble(tie.distance, across * acrossScale));
        }
    }

    private static double bedStart(TrackGeometry.Kind kind, TrackGeometry.Shape shape, int route) {
        if (kind.scissors()) return route >= 2 ? 3.43 : 0;
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
        return path.length - (kind.scissors() && route >= 2 ? 3.43 : 0);
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
        if (separate(a.start.x, a.end.x, b.start.x, b.end.x)
            || separate(a.start.z, a.end.z, b.start.z, b.end.z)) return null;
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

    private static boolean separate(double a, double b, double c, double d) {
        return Math.max(a, b) + 1e-12 < Math.min(c, d) || Math.max(c, d) + 1e-12 < Math.min(a, b);
    }

    private static double cross(Vec3 a, Vec3 b) { return a.x * b.z - a.z * b.x; }

    private static void casting(TrackGeometry.Shape shape, QuadSink sink, Frog frog) {
        Vec3 center = frog.point;
        Vec3 along = frog.first.heading;
        Vec3 across = lateral(along);
        List<Vec3> footprint = List.of(across.scale(-0.127).subtract(along.scale(frog.span)),
            across.scale(-0.127).add(along.scale(frog.span)), across.scale(0.127).add(along.scale(frog.span)),
            across.scale(0.127).subtract(along.scale(frog.span)));
        footprint = strip(footprint, frog.second.heading, frog.span);
        footprint = strip(footprint, lateral(frog.second.heading), 0.127);
        Mesh mesh = new Mesh(shape.owner(center), sink);
        mesh.prism(Surface.WEB, center, footprint, 0.175, 0.206);
        for (boolean gaugeA : new boolean[]{false, true}) {
            for (boolean gaugeB : new boolean[]{false, true}) {
                // Cut both flangeways through the casting down to the web. The remaining
                // raised sectors form the noses and the wing-rail running surfaces.
                var wing = flangeway(footprint, frog.first.gaugeSide(), gaugeA);
                wing = flangeway(wing, frog.second.gaugeSide(), gaugeB);
                mesh.prism(Surface.STEEL, center, wing, 0.204, 0.25);
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

    private static void checkRail(TrackGeometry.Shape shape, QuadSink sink, Frog frog, Crossing crossing,
                                  List<Frog> frogs) {
        Lane lane = crossing.lane;
        var path = shape.routes().get(lane.route);
        double size = frog.span + 0.28;
        double start = Math.max(0, crossing.distance - size), end = Math.min(path.length, crossing.distance + size);
        if (frogs.stream().anyMatch(other -> opposingFrog(other.first, lane, start, end)
            || opposingFrog(other.second, lane, start, end))) return;
        sweep(shape, path, sink, start, end, at -> -lane.side + Math.copySign(
            0.109 + Math.max(0, Math.abs(at - crossing.distance) / size - 0.68) * 0.10, lane.side), d -> 0.53,
            Surface.STEEL, FLAT, -0.019);
    }

    private static boolean opposingFrog(Crossing crossing, Lane lane, double start, double end) {
        return crossing.lane.route == lane.route && crossing.lane.side != lane.side
            && crossing.distance > start - 0.18 && crossing.distance < end + 0.18;
    }

    private static void stockRail(TrackGeometry.Shape shape, TrackGeometry.Path path, QuadSink sink,
                                  double start, double end, double side, DoubleBinaryOperator bank) {
        sweep(shape, path, sink, start, end, distance -> side, distance -> 1, Surface.STEEL, bank, 0);
    }

    /** The covered foundation is rendered with the rail so both shoulders follow the same path. */
    static void roadbed(TrackGeometry.Kind kind, TrackGeometry.Shape shape, boolean raised, int banking, QuadSink sink) {
        var bed = BallastProfile.bed(raised);
        for (int route = 0; route < shape.routes().size(); route++) {
            TrackGeometry.Path path = shape.routes().get(route);
            double start = bedStart(kind, shape, route);
            double end = bedEnd(kind, route, path);
            DoubleUnaryOperator lift = route == 0 || kind.scissors() && route == 1 ? SAME_HEIGHT : distance ->
                0.003 * Math.min(1, Math.min(distance - start, end - distance) * 2);
            DoubleBinaryOperator bank = bank(kind, path, banking);
            List<Double> cuts = roadbedCuts(path, start, end);
            BlockPos prior = null;
            for (int i = 1; i < cuts.size(); i++) {
                double from = cuts.get(i - 1);
                double to = cuts.get(i);
                BlockPos cell = shape.owner(path.point((from + to) / 2));
                Mesh mesh = new Mesh(cell, sink);
                boolean highGrade = kind.grade() && cell.getY() == 1;
                double base = cell.getY() - (raised || highGrade ? 1 : 0);
                boolean capStart = (raised || highGrade) && !cell.equals(prior) && from > 1e-7;
                boolean capEnd = (raised || highGrade) && to < path.length - 1e-7 && (i == cuts.size() - 1
                    || !cell.equals(shape.owner(path.point((to + cuts.get(i + 1)) / 2))));
                for (int j = 1; j < bed.length; j++) {
                    Vec3 a = roadbedPoint(path, from, bed[j - 1], lift, cell, raised, kind, bank);
                    Vec3 b = roadbedPoint(path, to, bed[j - 1], lift, cell, raised, kind, bank);
                    Vec3 c = roadbedPoint(path, to, bed[j], lift, cell, raised, kind, bank);
                    Vec3 d = roadbedPoint(path, from, bed[j], lift, cell, raised, kind, bank);
                    mesh.gravel(a, b, c, d, bed[j - 1].across() + 1.5, bed[j].across() + 1.5, from, to);
                    if (capStart) {
                        mesh.ballast(a, d, d.with(Direction.Axis.Y, base), a.with(Direction.Axis.Y, base), 0, 0, 1, 1);
                    }
                    if (capEnd) {
                        mesh.ballast(b, b.with(Direction.Axis.Y, base), c.with(Direction.Axis.Y, base), c, 0, 0, 1, 1);
                    }
                }
                prior = cell;
            }
        }
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

    private static void sweep(TrackGeometry.Shape shape, TrackGeometry.Path path, QuadSink sink,
                              double start, double end, DoubleUnaryOperator side, DoubleUnaryOperator thickness,
                              Surface surface, DoubleBinaryOperator bank, double verticalOffset) {
        List<Double> cuts = roadbedCuts(path, start, end);
        var next = new Vec3[RAIL.length];
        var previous = new Vec3[RAIL.length];
        var travelled = new double[RAIL.length - 1];
        java.util.Arrays.fill(travelled, start);
        section(path, start, side, thickness, bank, verticalOffset, previous);
        for (int i = 1; i < cuts.size(); i++) {
            double from = cuts.get(i - 1);
            double to = cuts.get(i);
            Mesh mesh = new Mesh(shape.owner(path.point((from + to) / 2)), sink);
            section(path, to, side, thickness, bank, verticalOffset, next);
            for (int j = 1; j < RAIL.length; j++) {
                Surface face = Math.max(RAIL[j - 1].height(), RAIL[j].height()) < 0.229 ? Surface.WEB : surface;
                mesh.face(face, previous[j - 1], next[j - 1], next[j], previous[j], 0, travelled[j - 1]);
                travelled[j - 1] += previous[j - 1].distanceTo(next[j - 1]);
            }
            var reuse = previous;
            previous = next;
            next = reuse;
        }
    }

    private static void section(TrackGeometry.Path path, double at, DoubleUnaryOperator side,
                                DoubleUnaryOperator thickness, DoubleBinaryOperator bank, double height, Vec3[] out) {
        Vec3 position = path.point(at);
        Vec3 acrossTrack = lateral(horizontal(path.tangent(at)));
        double offset = side.applyAsDouble(at);
        double width = thickness.applyAsDouble(at);
        for (int i = 0; i < RAIL.length; i++) {
            var profile = RAIL[i];
            double across = offset + profile.across() * width;
            out[i] = position.add(acrossTrack.scale(across)).add(0, profile.height() + height + bank.applyAsDouble(at, across), 0);
        }
    }

    private record Mesh(BlockPos owner, QuadSink sink) {
        void gravel(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double uStart, double uEnd, double vStart, double vEnd) {
            double uTile = Math.floor(uStart + 1e-8);
            double vTile = Math.floor(vStart + 1e-8);
            ballast(a, b, c, d, uStart - uTile, vStart - vTile, uEnd - uTile, vEnd - vTile);
        }

        void ballast(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double uStart, double vStart, double uEnd, double vEnd) {
            sink.add(owner, Surface.BALLAST, new SurfaceUv.Vertex(a, uStart, vStart), new SurfaceUv.Vertex(b, uStart, vEnd),
                new SurfaceUv.Vertex(c, uEnd, vEnd), new SurfaceUv.Vertex(d, uEnd, vStart));
        }

        void face(Surface surface, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
            face(surface, a, b, c, d, 0, 0);
        }

        void face(Surface surface, Vec3 a, Vec3 b, Vec3 c, Vec3 d, double uStart, double vStart) {
            SurfaceUv.face(a, b, c, d, uStart, vStart, (p, q, r, s) -> sink.add(owner, surface, p, q, r, s));
        }

        void prism(Surface surface, Vec3 center, List<Vec3> points, double low, double high) {
            if (points.size() < 3) return;
            Vec3 anchor = points.getFirst().add(center).add(0, high, 0);
            for (int i = 1; i + 1 < points.size(); i++) {
                Vec3 b = points.get(i).add(center).add(0, high, 0);
                Vec3 c = points.get(i + 1).add(center).add(0, high, 0);
                if (b.subtract(anchor).cross(c.subtract(anchor)).lengthSqr() > 1e-12) {
                    face(surface, anchor, b, c, c);
                }
            }
            Vec3 down = new Vec3(0, low - high, 0);
            for (int i = 0; i < points.size(); i++) {
                Vec3 a = points.get(i).add(center).add(0, high, 0);
                Vec3 b = points.get((i + 1) % points.size()).add(center).add(0, high, 0);
                if (a.distanceToSqr(b) > 1e-12) face(surface, a.add(down), b.add(down), b, a);
            }
        }

        void tiePlates(Vec3 center, Vec3 heading, DoubleUnaryOperator bank) {
            for (double side : new double[]{-GAUGE, GAUGE}) {
                box(Surface.WEB, center, heading, side - 0.055, side + 0.055, 0.1725, 0.183, 0.052, bank);
            }
        }

        private void box(Surface surface, Vec3 center, Vec3 heading, double min, double max,
                         double bottom, double top, double halfDepth, DoubleUnaryOperator bank) {
            Vec3 left = new Vec3(heading.z, 0, -heading.x);
            Vec3 back = heading.scale(-halfDepth);
            Vec3 front = heading.scale(halfDepth);
            Vec3 a = center.add(back).add(left.scale(min)).add(0, top + bank.applyAsDouble(min), 0);
            Vec3 b = center.add(front).add(left.scale(min)).add(0, top + bank.applyAsDouble(min), 0);
            Vec3 c = center.add(front).add(left.scale(max)).add(0, top + bank.applyAsDouble(max), 0);
            Vec3 d = center.add(back).add(left.scale(max)).add(0, top + bank.applyAsDouble(max), 0);
            Vec3 down = new Vec3(0, bottom - top, 0);
            Vec3 aBottom = a.add(down), bBottom = b.add(down), cBottom = c.add(down), dBottom = d.add(down);
            face(surface, a, b, c, d);
            face(surface, aBottom, bBottom, b, a);
            face(surface, d, c, cBottom, dBottom);
            face(surface, aBottom, a, d, dBottom);
            face(surface, cBottom, c, b, bBottom);
            if (surface == Surface.TIE) face(surface, bBottom, aBottom, dBottom, cBottom);
        }
    }
}
