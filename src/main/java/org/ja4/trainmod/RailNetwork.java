package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.List;
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

final class RailNetwork {
    private static final double EPSILON = 0.0001;

    record Rail(BlockPos root, TrackGeometry.Path path, List<BlockPos> cells, int route, boolean vanilla,
                boolean startOpen, boolean endOpen) {
        Rail(BlockPos root, TrackGeometry.Path path, List<BlockPos> cells, int route, boolean vanilla) {
            this(root, path, cells, route, vanilla, true, true);
        }

        Vec3 origin() { return TrackGeometry.origin(root); }
        Vec3 point(double distance) { return origin().add(path.point(distance)); }
        Vec3 end(boolean end) { return origin().add(path.endpoint(end)); }
        double project(Vec3 position) { return path.project(position.subtract(origin())); }
        boolean open(boolean end) { return end ? endOpen : startOpen; }
    }

    record Position(Rail rail, double distance, int forward) {
        Vec3 point() { return rail.point(distance); }
        Vec3 tangent() { return rail.path.tangent(distance).scale(forward); }
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
            int index = state.getValue(TrackBlock.INDEX);
            if (index >= shape.cells().size()) return List.of();
            BlockPos root = pos.subtract(shape.cells().get(index));
            if (!level.isLoaded(root)) return List.of();
            BlockState rootState = level.getBlockState(root);
            if (!matches(state, rootState, 0)) return List.of();
            List<BlockPos> cells = shape.cells().stream().map(root::offset).toList();
            for (int i = 0; i < cells.size(); i++) {
                BlockPos cell = cells.get(i);
                if (!level.isLoaded(cell) || !matches(state, level.getBlockState(cell), i)
                    || !TrackBlock.supported(level, cell, track.kind.grade() && cell.getY() > root.getY())) {
                    return List.of();
                }
            }
            if (!physical && track.kind == TrackGeometry.Kind.SCISSORS) return scissors(level, root, shape, cells);
            int[] routes = physical ? java.util.stream.IntStream.range(0, shape.routes().size()).toArray()
                : track.kind.activeRoutes(track.kind.turnout() && rootState.getValue(BlockStateProperties.POWERED),
                    rootState.getValue(TrackBlock.HAND) == net.minecraft.world.level.block.state.properties.ChestType.LEFT);
            List<Rail> rails = new ArrayList<>(routes.length);
            for (int route : routes) rails.add(new Rail(root, shape.routes().get(route), cells, route, false));
            return rails;
        }
        if (state.getBlock() instanceof BaseRailBlock vanilla && BaseRailBlock.isRail(state)) {
            return List.of(new Rail(pos, vanilla(vanilla.getRailDirection(state, level, pos, null)), List.of(pos), 0, true));
        }
        return List.of();
    }

    private static List<Rail> scissors(Level level, BlockPos root, TrackGeometry.Shape shape, List<BlockPos> cells) {
        var layout = SwitchLayout.of(TrackGeometry.Kind.SCISSORS, shape);
        List<Rail> rails = new ArrayList<>();
        int index = 0;
        for (var route : layout.scissorRoutes()) {
            boolean entrance = level.getBlockState(root.offset(route.entrance().control())).getValue(BlockStateProperties.POWERED);
            boolean exit = level.getBlockState(root.offset(route.exit().control())).getValue(BlockStateProperties.POWERED);
            boolean startOpen = entrance == route.diverging();
            boolean endOpen = exit == route.diverging();
            rails.add(new Rail(root, route.path(startOpen, endOpen), cells, index++, false, startOpen, endOpen));
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
        return actual.getBlock() == expected.getBlock()
            && actual.getValue(TrackBlock.INDEX) == index
            && actual.getValue(TrackBlock.ROTATION).equals(expected.getValue(TrackBlock.ROTATION))
            && actual.getValue(TrackBlock.HAND) == expected.getValue(TrackBlock.HAND);
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
        int travel = delta >= 0 ? 1 : -1;
        double remaining = Math.abs(delta);
        for (int transitions = 0; transitions < 64; transitions++) {
            int direction = position.forward * travel;
            double distance = position.distance + direction * remaining;
            if (distance >= 0 && distance <= position.rail.path.length) {
                return new Position(position.rail, distance, position.forward);
            }
            boolean end = direction > 0;
            remaining -= end ? position.rail.path.length - position.distance : position.distance;
            Position next = next(level, position.rail, end);
            if (next == null) {
                return null;
            }
            position = new Position(next.rail, next.distance, next.forward * travel);
        }
        return null;
    }

    static Position next(Level level, Rail from, boolean end) {
        if (!from.open(end)) return null;
        Vec3 endpoint = from.end(end);
        Vec3 outward = from.path.tangent(end ? from.path.length : 0).scale(end ? 1 : -1);
        for (BlockPos cell : portCells(endpoint)) {
            for (Rail rail : railsAt(level, cell, false)) {
                if (rail.root.equals(from.root)) continue;
                for (boolean candidateEnd : new boolean[]{false, true}) {
                    if (rail.open(candidateEnd) && joins(rail, candidateEnd, endpoint, outward)) {
                        return new Position(rail, candidateEnd ? rail.path.length : 0, candidateEnd ? -1 : 1);
                    }
                }
            }
        }
        return null;
    }

    static boolean turnoutOccupied(ServerLevel level, BlockPos root, TrackGeometry.Shape shape) {
        List<AABB> volumes = shape.cells().stream().map(root::offset).map(AABB::new)
            .map(box -> box.inflate(1.25, 1.5, 1.25)).toList();
        AABB bounds = volumes.getFirst();
        for (AABB volume : volumes) {
            bounds = bounds.minmax(volume);
        }
        return level.getEntitiesOfClass(TrainVehicle.class, bounds).stream()
            .filter(vehicle -> vehicle.railRoot() != null)
            .anyMatch(vehicle -> volumes.stream().anyMatch(volume -> volume.contains(vehicle.position())));
    }

    static boolean panelOccupied(ServerLevel level, BlockPos root, SwitchLayout.Panel panel) {
        Vec3 origin = TrackGeometry.origin(root);
        Vec3 toe = panel.first().path().point(panel.first().distance(0)).add(origin);
        Vec3 heel = panel.first().path().point(panel.first().distance(1)).add(origin);
        AABB vicinity = new AABB(toe, heel).inflate(SwitchLayout.POINT_CLEARANCE + 1.05, 1.2,
            SwitchLayout.POINT_CLEARANCE + 1.05);
        return !level.getEntitiesOfClass(TrainVehicle.class, vicinity,
            vehicle -> vehicle.railRoot() != null && panel.containsVehicle(vehicle.position().subtract(origin))).isEmpty();
    }

    static boolean occupied(ServerLevel level, Rail rail, double begin, double end) {
        List<BlockPos> cells = new ArrayList<>();
        int count = Math.max(1, (int) Math.ceil(Math.abs(end - begin) * 4));
        for (int i = 0; i <= count; i++) {
            BlockPos cell = BlockPos.containing(rail.point(net.minecraft.util.Mth.lerp(i / (double) count, begin, end))
                .add(0, 0.0001, 0));
            if (!cells.contains(cell)) {
                cells.add(cell);
            }
        }
        for (BlockPos cell : cells) {
            AABB box = new AABB(cell).inflate(0.15, 0.35, 0.15);
            if (!level.getEntitiesOfClass(TrainVehicle.class, box,
                vehicle -> vehicle.railRoot() != null).isEmpty()
                || !level.getEntitiesOfClass(AbstractMinecart.class, box).isEmpty()) {
                return true;
            }
        }
        return false;
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
