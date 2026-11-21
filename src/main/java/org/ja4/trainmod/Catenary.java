package org.ja4.trainmod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Finds continuous wire across the physical rail routes, including both turnout branches. */
final class Catenary {
    static final int SPAN_LIMIT = 16;
    private static final double EPSILON = 0.0001;
    private static final double POLE_CLEARANCE = 0.90;

    record Attachment(BlockPos tower, RailNetwork.Rail rail, double distance, int offset) {
        Vec3 contact() { return rail.point(distance).add(0, offset, 0); }
        double height() { return contact().y; }
    }
    record Span(Attachment end, double length, List<Vec3> wire) {}
    private record Step(RailNetwork.Rail rail, double distance, int direction, double walked, List<Vec3> wire, Set<RouteEnd> visited) {}
    private record RouteEnd(BlockPos root, int route, boolean end) {}

    static boolean powered(Level level, RailNetwork.Position position) {
        if (position == null) return false;
        Vec3 point = position.point();
        RailNetwork.Rail rail = RailNetwork.physicalRailsAt(level, position.rail().root()).stream()
            .filter(candidate -> candidate.route() == position.rail().route()).findFirst().orElse(position.rail());
        double distance = project(rail, point);
        for (int offset : new int[]{CatenaryTower.Kind.SINGLE.height, CatenaryTower.Kind.GANTRY.height}) {
            for (int direction : new int[]{-1, 1}) {
                var starts = follow(level, rail, distance, direction, offset, null, false);
                for (Span start : starts) {
                    var ends = follow(level, rail, distance, -direction, offset, start.end.tower, false);
                    if (ends.stream().anyMatch(end -> start.length + end.length <= SPAN_LIMIT + EPSILON)) return true;
                }
            }
        }
        return false;
    }

    static List<Attachment> attachments(Level level, BlockPos tower, BlockState state) {
        if (!(state.getBlock() instanceof CatenaryTower block)) return List.of();
        BlockPos step = CatenaryTower.armStep(state);
        boolean diagonal = state.getValue(CatenaryTower.DIAGONAL);
        Set<Attachment> found = new LinkedHashSet<>();
        int maxReach = block.kind == CatenaryTower.Kind.GANTRY ? 1 : block.kind.maxReach(diagonal);
        for (int reach = 1; reach <= maxReach; reach++) {
            BlockPos cell = block.kind == CatenaryTower.Kind.GANTRY
                ? tower.below(gantryMount(level, tower)) : tower.offset(step.multiply(reach));
            for (var rail : RailNetwork.physicalRailsAt(level, cell)) {
                Attachment anchor = attachment(rail, cell, tower, block.kind, reach, diagonal);
                if (anchor != null) found.add(anchor);
            }
        }
        return List.copyOf(found);
    }

    static int gantryMount(Level level, BlockPos tower) {
        int height = CatenaryTower.Kind.GANTRY.height;
        if (RailNetwork.isRail(level.getBlockState(tower.below(height)))
            || !RailNetwork.isRail(level.getBlockState(tower.below(height - 1)))) return height;
        return height - 1;
    }

    static List<Span> spansFrom(Level level, Attachment start, int direction) {
        return follow(level, start.rail(), start.distance(), direction, start.offset(), start.tower(), true);
    }

    private static Attachment attachment(RailNetwork.Rail rail, BlockPos cell, BlockPos tower,
                                         CatenaryTower.Kind kind, int reach, boolean diagonal) {
        Vec3 center = TrackGeometry.origin(cell);
        double distance = project(rail, center);
        Vec3 point = rail.point(distance);
        if (point.subtract(center).horizontalDistance() > 0.475) return null;
        if (kind != CatenaryTower.Kind.GANTRY) {
            Vec3 tangent = rail.path().tangent(distance).multiply(1, 0, 1).normalize();
            Vec3 sideways = Vec3.atLowerCornerOf(tower.subtract(cell)).normalize();
            if (Math.abs(tangent.dot(sideways)) > 0.82 || !kind.reaches(reach, angled(rail.path(), tangent), diagonal)) return null;
            Vec3 pole = TrackGeometry.origin(tower);
            Vec3 closest = rail.point(project(rail, pole));
            if (closest.subtract(pole).horizontalDistance() < POLE_CLEARANCE) return null;
        }
        return new Attachment(tower, rail, distance, kind.height);
    }

    private static double project(RailNetwork.Rail rail, Vec3 point) {
        return rail.path().projectHorizontally(point.subtract(rail.origin()));
    }

    private static boolean angled(TrackGeometry.Path path, Vec3 tangent) {
        if (Math.min(Math.abs(tangent.x), Math.abs(tangent.z)) > 0.12) return true;
        Vec3 first = path.endpoint(false);
        Vec3 chord = path.endpoint(true).subtract(first).multiply(1, 0, 1).normalize();
        return path.points().stream().map(point -> point.subtract(first))
            .anyMatch(point -> Math.abs(chord.x * point.z - chord.z * point.x) > 0.01);
    }

    private static List<Span> follow(Level level, RailNetwork.Rail rail, double distance, int direction, int offset,
                                     BlockPos origin, boolean collectWire) {
        var queue = new ArrayDeque<Step>();
        queue.add(new Step(rail, distance, direction, 0, List.of(), Set.of()));
        List<Span> spans = new ArrayList<>();
        while (!queue.isEmpty()) {
            Step step = queue.removeFirst();
            double remaining = SPAN_LIMIT - step.walked;
            boolean end = step.direction > 0;
            RouteEnd routeEnd = new RouteEnd(step.rail.root(), step.rail.route(), end);
            if (step.visited.contains(routeEnd)) continue;
            Attachment next = nearest(level, step.rail, step.distance, step.direction, remaining, offset, origin);
            List<Vec3> wire = collectWire ? new ArrayList<>(step.wire) : List.of();
            if (next != null) {
                if (collectWire) append(wire, step.rail, step.distance, next.distance(), offset);
                spans.add(new Span(next, step.walked + Math.abs(next.distance() - step.distance), List.copyOf(wire)));
                continue;
            }
            double limit = end ? step.rail.path().length : 0;
            double walked = step.walked + Math.abs(limit - step.distance);
            if (walked > SPAN_LIMIT) continue;
            if (collectWire) append(wire, step.rail, step.distance, limit, offset);
            Set<RouteEnd> visited = new HashSet<>(step.visited);
            visited.add(routeEnd);
            for (var adjacent : RailNetwork.physicalConnections(level, step.rail, end)) {
                queue.add(new Step(adjacent.rail(), adjacent.distance(), adjacent.forward(), walked, wire, visited));
            }
        }
        return spans;
    }

    private static Attachment nearest(Level level, RailNetwork.Rail rail, double distance, int direction, double range,
                                       int offset, BlockPos origin) {
        Attachment nearest = null;
        double best = range + EPSILON;
        for (BlockPos cell : rail.cells()) {
            var center = TrackGeometry.origin(cell);
            double along = (project(rail, center) - distance) * direction;
            if (along < -EPSILON || along > best) continue;
            Attachment candidate = findTower(level, rail, cell, offset, origin);
            if (candidate != null && (nearest == null || along < best)) {
                nearest = candidate;
                best = along;
            }
        }
        return nearest;
    }

    private static Attachment findTower(Level level, RailNetwork.Rail rail, BlockPos cell, int offset, BlockPos origin) {
        if (offset == CatenaryTower.Kind.GANTRY.height) {
            for (int mount : new int[]{offset, offset - 1}) {
                BlockPos tower = cell.above(mount);
                if (!tower.equals(origin) && level.getBlockState(tower).is(Trainmod.CATENARY_GANTRY.get())
                    && gantryMount(level, tower) == mount) {
                    return attachment(rail, cell, tower, CatenaryTower.Kind.GANTRY, 1, false);
                }
            }
            return null;
        }
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (boolean diagonal : new boolean[]{false, true}) {
                BlockPos step = CatenaryTower.armStep(facing, diagonal);
                for (int reach = 1; reach <= CatenaryTower.Kind.DOUBLE.maxReach(diagonal); reach++) {
                    BlockPos tower = cell.offset(step.multiply(-reach));
                    if (tower.equals(origin) || !level.isLoaded(tower)) continue;
                    BlockState state = level.getBlockState(tower);
                    if (!(state.getBlock() instanceof CatenaryTower block) || block.kind.height != offset
                        || reach > block.kind.maxReach(diagonal) || state.getValue(CatenaryTower.FACING) != facing
                        || state.getValue(CatenaryTower.DIAGONAL) != diagonal) continue;
                    Attachment anchor = attachment(rail, cell, tower, block.kind, reach, diagonal);
                    if (anchor != null) return anchor;
                }
            }
        }
        return null;
    }

    private static void append(List<Vec3> wire, RailNetwork.Rail rail, double start, double end, int offset) {
        if (Math.abs(end - start) <= EPSILON) return;
        var points = new ArrayList<>(rail.path().section(Math.min(start, end), Math.max(start, end)).points());
        if (end < start) java.util.Collections.reverse(points);
        for (int i = wire.isEmpty() ? 0 : 1; i < points.size(); i++) {
            Vec3 next = points.get(i).add(rail.origin()).add(0, offset, 0);
            if (wire.size() >= 2) {
                Vec3 last = wire.getLast();
                Vec3 before = wire.get(wire.size() - 2);
                if (last.subtract(before).normalize().dot(next.subtract(last).normalize()) > 0.999999) wire.removeLast();
            }
            wire.add(next);
        }
    }
}
