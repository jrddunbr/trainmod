package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

final class RailNetwork {
    private static final double EPSILON = 0.0001;

    record Rail(BlockPos root, TrackGeometry.Path path, List<BlockPos> cells, int route, boolean vanilla,
                boolean startOpen, boolean endOpen, Set<BlockPos> broken) {
        Rail(BlockPos root, TrackGeometry.Path path, List<BlockPos> cells, int route, boolean vanilla) {
            this(root, path, cells, route, vanilla, true, true, Set.of());
        }

        Vec3 origin() { return TrackGeometry.origin(root); }
        Vec3 point(double distance) {
            double onRail = Math.clamp(distance, 0, path.length);
            return origin().add(path.point(onRail)).add(path.tangent(onRail).scale(distance - onRail));
        }
        Vec3 end(boolean end) { return origin().add(path.endpoint(end)); }
        double project(Vec3 position) { return path.project(position.subtract(origin())); }
        boolean open(boolean end) { return end ? endOpen : startOpen; }

        BlockPos trackCell(Vec3 point) {
            BlockPos column = BlockPos.containing(point);
            return trackCell(column.getX(), column.getZ());
        }

        private BlockPos trackCell(int x, int z) {
            // A grade's running rail can rise into the air block above its segment.
            for (BlockPos cell : cells) {
                if (cell.getX() == x && cell.getZ() == z) return cell;
            }
            return null;
        }

        boolean blocks(double from, double to) {
            return !broken.isEmpty() && crosses(from, to, broken::contains);
        }

        boolean crosses(double from, double to, Predicate<BlockPos> visit) {
            double low = Math.min(from, to), high = Math.max(from, to);
            if (high - low <= 1e-8) return false;
            var columns = path.columns();
            for (var span : from < to ? columns : columns.reversed()) {
                if (Math.min(high, span.end()) - Math.max(low, span.start()) <= 1e-8) continue;
                BlockPos column = span.column();
                BlockPos cell = trackCell(root.getX() + column.getX(), root.getZ() + column.getZ());
                if (cell != null && visit.test(cell)) return true;
            }
            return false;
        }

        boolean levelStraight() {
            Vec3 span = path.endpoint(true).subtract(path.endpoint(false));
            return Math.abs(span.y) < 1e-5 && path.length - span.horizontalDistance() < 1e-5;
        }
    }

    record Position(Rail rail, double distance, int forward) {
        Vec3 point() { return rail.point(distance); }
        Vec3 tangent() { return rail.path.tangent(distance).scale(forward); }

        BlockPos trackCell() {
            double inside = Math.clamp(distance, EPSILON, rail.path.length - EPSILON);
            return rail.trackCell(rail.point(inside));
        }
    }

    private RailNetwork() {}

    static boolean isRail(BlockState state) {
        return state.getBlock() instanceof TrackBlock || BaseRailBlock.isRail(state);
    }

    static Rail railAt(Level level, BlockPos pos) {
        return nearest(railsAt(level, pos, false), TrackGeometry.origin(pos), Vec3.ZERO, -1);
    }

    private static List<Rail> railsAt(Level level, BlockPos pos, boolean physical) {
        if (!level.isLoaded(pos)) return List.of();
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof TrackBlock track) {
            var shape = track.shape(state);
            int index = track.index(state);
            if (index >= shape.cells().size()) return List.of();
            BlockPos root = pos.subtract(shape.cells().get(index));
            if (!level.isLoaded(root)) return List.of();
            BlockState rootState = level.getBlockState(root);
            if (!matches(state, rootState, 0)) return List.of();
            List<BlockPos> cells = shape.cells().stream().map(root::offset).toList();
            Set<BlockPos> damaged = Set.of();
            for (int i = 0; i < cells.size(); i++) {
                BlockPos cell = cells.get(i);
                if (!level.isLoaded(cell)) return List.of();
                BlockState segment = level.getBlockState(cell);
                if (!matches(state, segment, i)
                    || !TrackBlock.supported(level, cell, track.kind.grade() && cell.getY() > root.getY())) {
                    return List.of();
                }
                if (TrackBlock.broken(segment)) {
                    if (damaged.isEmpty()) damaged = new HashSet<>();
                    damaged.add(cell);
                }
            }
            if (!physical && track.kind.scissors()) return scissors(level, root, SwitchLayout.of(track.kind, shape), cells, damaged);
            int[] routes = physical ? java.util.stream.IntStream.range(0, shape.routes().size()).toArray()
                : track.kind.activeRoutes(track.kind.turnout() && rootState.getValue(BlockStateProperties.POWERED),
                    rootState.getValue(TrackBlock.HAND) == net.minecraft.world.level.block.state.properties.ChestType.LEFT);
            if (!physical && track.kind.turnout()) {
                int active = routes[0];
                int inactive = 1 - active;
                var closedPath = shape.routes().get(inactive);
                var panel = SwitchLayout.of(track.kind, shape).panels().getFirst();
                var blade = inactive == 0 ? panel.first() : panel.second();
                double stop = Math.min(closedPath.length - 0.05, blade.distance(1) + SwitchLayout.POINT_CLEARANCE);
                return List.of(new Rail(root, shape.routes().get(active), cells, active, false, true, true, damaged),
                    new Rail(root, closedPath.section(stop, closedPath.length), cells, inactive, false, false, true, damaged));
            }
            List<Rail> rails = new ArrayList<>(routes.length);
            for (int route : routes) rails.add(new Rail(root, shape.routes().get(route), cells, route, false, true, true, damaged));
            return rails;
        }
        if (state.getBlock() instanceof BaseRailBlock vanilla && BaseRailBlock.isRail(state)) {
            return List.of(new Rail(pos, vanilla(vanilla.getRailDirection(state, level, pos, null)), List.of(pos), 0, true));
        }
        return List.of();
    }

    private static List<Rail> scissors(Level level, BlockPos root, SwitchLayout layout, List<BlockPos> cells, Set<BlockPos> damaged) {
        List<Rail> rails = new ArrayList<>();
        int index = 0;
        for (var route : layout.scissorRoutes()) {
            boolean entrance = level.getBlockState(root.offset(route.entrance().control())).getValue(BlockStateProperties.POWERED);
            boolean exit = level.getBlockState(root.offset(route.exit().control())).getValue(BlockStateProperties.POWERED);
            boolean startOpen = entrance == route.diverging();
            boolean endOpen = exit == route.diverging();
            rails.add(new Rail(root, route.path(startOpen, endOpen), cells, index++, false, startOpen, endOpen, damaged));
        }
        return rails;
    }

    static List<Rail> physicalRailsAt(Level level, BlockPos pos) {
        return railsAt(level, pos, true);
    }

    private static Rail nearest(List<Rail> rails, Vec3 point, Vec3 forward, int preferredRoute) {
        Rail best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Rail rail : rails) {
            if (rail.route == preferredRoute) return rail;
            double along = rail.project(point);
            double score = rail.point(along).distanceToSqr(point);
            score -= 0.02 * Math.abs(rail.path.tangent(along).dot(forward));
            if (score < bestScore) {
                bestScore = score;
                best = rail;
            }
        }
        return best;
    }

    record Port(Vec3 point, Vec3 outward, BlockPos landing) {}

    static List<Port> exposedPorts(Level level, BlockPos pos) {
        List<Port> ports = new ArrayList<>();
        for (Rail rail : physicalRailsAt(level, pos)) {
            for (boolean end : new boolean[]{false, true}) {
                Vec3 point = rail.end(end);
                Vec3 outward = rail.path().tangent(end ? rail.path().length : 0).scale(end ? 1 : -1);
                Vec3 horizontal = outward.multiply(1, 0, 1).normalize();
                BlockPos landing = BlockPos.containing(point.add(horizontal.scale(0.15)));
                if (!rail.cells().contains(landing) && !hasConnection(level, point, outward)) {
                    ports.add(new Port(point, outward, landing));
                }
            }
        }
        return ports;
    }

    static boolean hasConnection(Level level, Vec3 port, Vec3 outward) {
        for (BlockPos cell : portCells(port)) {
            for (Rail rail : physicalRailsAt(level, cell)) {
                for (boolean end : new boolean[]{false, true}) {
                    if (joins(rail, end, port, outward)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Iterable<BlockPos> portCells(Vec3 port) {
        BlockPos base = BlockPos.containing(port.add(1e-6, 0, 1e-6));
        int minX = (int) Math.floor(port.x - 1e-6);
        int minZ = (int) Math.floor(port.z - 1e-6);
        return BlockPos.betweenClosed(new BlockPos(minX, base.getY() - 1, minZ), base);
    }

    private static boolean joins(Rail rail, boolean end, Vec3 port, Vec3 outward) {
        return rail.end(end).distanceToSqr(port) <= EPSILON
            && rail.path.tangent(end ? rail.path.length : 0).scale(end ? -1 : 1).dot(outward) > 0.35;
    }

    static boolean matches(BlockState expected, BlockState actual, int index) {
        return actual.getBlock() == expected.getBlock() && ((TrackBlock) actual.getBlock()).index(actual) == index
            && actual.getValue(TrackBlock.ROTATION).equals(expected.getValue(TrackBlock.ROTATION))
            && actual.getValue(TrackBlock.HAND) == expected.getValue(TrackBlock.HAND)
            && (!expected.hasProperty(TrackBlock.COMPACT) || actual.getValue(TrackBlock.COMPACT) == expected.getValue(TrackBlock.COMPACT))
            && (!expected.hasProperty(TrackBlock.DIAGONAL_LOW)
                || actual.getValue(TrackBlock.DIAGONAL_LOW) == expected.getValue(TrackBlock.DIAGONAL_LOW));
    }

    static Position locate(Level level, BlockPos cell, Vec3 point, Vec3 forward) {
        return locate(level, cell, point, forward, -1);
    }

    static Position locate(Level level, BlockPos cell, Vec3 point, Vec3 forward, int preferredRoute) {
        Rail rail = nearest(railsAt(level, cell, false), point, forward, preferredRoute);
        return rail == null ? null : onRail(rail, point, forward);
    }

    static Position onRail(Rail rail, Vec3 point, Vec3 forward) {
        double distance = rail.project(point);
        return new Position(rail, distance, rail.path.tangent(distance).dot(forward) >= 0 ? 1 : -1);
    }

    static Position advance(Level level, Position position, double delta) {
        return advance(level, position, delta, false, null);
    }

    static Position advanceBore(Level level, Position position, double delta) {
        return advance(level, position, delta, true, null);
    }

    static boolean crossesNewCell(Level level, Position position, double delta, Predicate<BlockPos> visit) {
        double back = Math.copySign(1e-8, position.forward * delta);
        var visited = new HashSet<BlockPos>();
        visited.add(new Position(position.rail, position.distance - back, position.forward).trackCell());
        boolean[] hit = {false};
        advance(level, position, delta, false, cell -> {
            hit[0] = visited.add(cell) && visit.test(cell);
            return hit[0];
        });
        return hit[0];
    }

    private static Position advance(Level level, Position position, double delta, boolean extendFront,
                                    @Nullable Predicate<BlockPos> visit) {
        double distance = position.distance + position.forward * delta;
        for (int transitions = 0; transitions < 64; transitions++) {
            Rail rail = position.rail;
            double length = rail.path.length;
            double from = Math.clamp(position.distance, 0, length), to = Math.clamp(distance, 0, length);
            if (rail.blocks(from, to) || visit != null && rail.crosses(from, to, visit)) return null;
            if (distance >= -1e-8 && distance <= length + 1e-8) {
                return new Position(rail, Math.clamp(distance, 0, length), position.forward);
            }
            boolean end = distance > length;
            boolean front = end == (position.forward > 0);
            Position next = next(level, rail, end);
            if (next == null) {
                return extendFront && front && rail.open(end) && rail.levelStraight()
                    ? new Position(rail, distance, position.forward) : null;
            }
            double remaining = end ? distance - length : -distance;
            distance = next.distance + next.forward * remaining;
            position = new Position(next.rail, next.distance, front ? next.forward : -next.forward);
        }
        return null;
    }

    static Position next(Level level, Rail from, boolean end) {
        var connections = connections(level, from, end, false);
        return connections.isEmpty() ? null : connections.getFirst();
    }

    static List<Position> physicalConnections(Level level, Rail from, boolean end) {
        return connections(level, from, end, true);
    }

    private static List<Position> connections(Level level, Rail from, boolean end, boolean physical) {
        if (!physical && !from.open(end)) return List.of();
        Vec3 endpoint = from.end(end);
        Vec3 outward = from.path.tangent(end ? from.path.length : 0).scale(end ? 1 : -1);
        List<Position> found = new ArrayList<>();
        for (BlockPos cell : portCells(endpoint)) {
            for (Rail rail : railsAt(level, cell, physical)) {
                if (rail.root.equals(from.root)) continue;
                for (boolean candidateEnd : new boolean[]{false, true}) {
                    if ((physical || rail.open(candidateEnd)) && joins(rail, candidateEnd, endpoint, outward)) {
                        var connection = new Position(rail, candidateEnd ? rail.path.length : 0, candidateEnd ? -1 : 1);
                        if (!physical) return List.of(connection);
                        if (!found.contains(connection)) found.add(connection);
                    }
                }
            }
        }
        return found;
    }

    static boolean panelOccupied(ServerLevel level, BlockPos root, SwitchLayout.Panel panel) {
        Vec3 origin = TrackGeometry.origin(root);
        List<Vec3> points = new ArrayList<>();
        AABB bounds = null;
        for (var blade : panel.blades()) {
            int samples = Math.max(1, (int) Math.ceil(Math.abs(blade.distance(1) - blade.distance(0)) * 16));
            for (int i = 0; i <= samples; i++) {
                for (int opening : new int[]{0, 1}) {
                    Vec3 point = blade.point((double) i / samples, opening).add(origin);
                    points.add(point);
                    AABB sample = new AABB(point, point);
                    bounds = bounds == null ? sample : bounds.minmax(sample);
                }
            }
        }
        AABB vicinity = bounds.inflate(SwitchLayout.POINT_CLEARANCE);
        return !level.getEntitiesOfClass(TrainVehicle.class, vicinity,
            train -> train.isAlive() && train.foulsPoints(points, SwitchLayout.POINT_CLEARANCE)).isEmpty()
            || !level.getEntitiesOfClass(AbstractMinecart.class, vicinity, cart -> cart.isAlive()
                && points.stream().anyMatch(point -> cart.getBoundingBox().distanceToSqr(point)
                    <= SwitchLayout.POINT_CLEARANCE * SwitchLayout.POINT_CLEARANCE)).isEmpty();
    }

    static TrackGeometry.Path vanilla(RailShape shape) {
        return switch (shape) {
            case NORTH_SOUTH -> line(0, 0, -1, 0, 0, 1);
            case EAST_WEST -> line(-1, 0, 0, 1, 0, 0);
            case ASCENDING_EAST -> line(-1, 0, 0, 1, 1, 0);
            case ASCENDING_WEST -> line(1, 0, 0, -1, 1, 0);
            case ASCENDING_NORTH -> line(0, 0, 1, 0, 1, -1);
            case ASCENDING_SOUTH -> line(0, 0, -1, 0, 1, 1);
            case SOUTH_EAST -> corner(0, 1, 1, 0);
            case SOUTH_WEST -> corner(0, 1, -1, 0);
            case NORTH_WEST -> corner(0, -1, -1, 0);
            case NORTH_EAST -> corner(0, -1, 1, 0);
        };
    }

    private static TrackGeometry.Path line(int ax, int ay, int az, int bx, int by, int bz) {
        return new TrackGeometry.Path(List.of(new Vec3(ax * 0.5, ay, az * 0.5),
            new Vec3(bx * 0.5, by, bz * 0.5)));
    }

    private static TrackGeometry.Path corner(int ax, int az, int bx, int bz) {
        Vec3 a = new Vec3(ax * 0.5, 0, az * 0.5);
        Vec3 b = new Vec3(bx * 0.5, 0, bz * 0.5);
        List<Vec3> points = new ArrayList<>();
        for (int i = 0; i <= 12; i++) {
            double t = i / 12.0;
            points.add(a.scale((1 - t) * (1 - t)).add(b.scale(t * t)));
        }
        return new TrackGeometry.Path(points);
    }
}
