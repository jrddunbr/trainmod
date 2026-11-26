package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

final class TrackEnds {
    private static final double PROBE = 0.04;
    private static final double MATCH_DISTANCE_SQR = 0.0001;
    private static final Map<TrackGeometry.Shape, Map<BlockPos, List<Section>>> SECTIONS = new ConcurrentHashMap<>();

    enum Connection { OPEN, RAIL, BRIDGE }

    record Port(Vec3 center, Vec3 outward) {
        BlockPos landing() { return BlockPos.containing(center.add(outward.scale(PROBE)).add(0, 0.1, 0)); }
    }

    record Section(int route, double start, double end, int previous, int next) {}

    private TrackEnds() {}

    static List<Section> sections(TrackGeometry.Shape shape, BlockPos cell) {
        return SECTIONS.computeIfAbsent(shape, TrackEnds::sections).getOrDefault(cell, List.of());
    }

    private static Map<BlockPos, List<Section>> sections(TrackGeometry.Shape shape) {
        var result = new HashMap<BlockPos, List<Section>>();
        for (int route = 0; route < shape.routes().size(); route++) {
            var path = shape.routes().get(route);
            var spans = path.columns();
            var owners = spans.stream().map(span -> shape.cells().indexOf(
                shape.owner(path.point((span.start() + span.end()) / 2)))).toList();
            for (int i = 0; i < spans.size(); ) {
                int owner = owners.get(i);
                int last = i;
                while (last + 1 < spans.size() && owners.get(last + 1) == owner) last++;
                var section = new Section(route, spans.get(i).start(), spans.get(last).end(),
                    i == 0 ? -1 : owners.get(i - 1), last == spans.size() - 1 ? -1 : owners.get(last + 1));
                result.computeIfAbsent(shape.cells().get(owner), ignored -> new ArrayList<>()).add(section);
                i = last + 1;
            }
        }
        return result;
    }

    static List<Port> ports(TrackGeometry.Shape shape, BlockPos cell) {
        var ports = new ArrayList<Port>();
        for (var path : shape.routes()) {
            for (boolean end : new boolean[]{false, true}) {
                Vec3 center = path.endpoint(end);
                Vec3 out = horizontal(path.tangent(end ? path.length : 0)).scale(end ? 1 : -1);
                if (TrackGeometry.cell(center.subtract(out.scale(PROBE))).equals(cell)
                    && ports.stream().noneMatch(port -> port.center.distanceToSqr(center) < MATCH_DISTANCE_SQR)) {
                    ports.add(new Port(center, out));
                }
            }
        }
        return ports;
    }

    static List<Port> at(TrackGeometry.Shape shape, BlockPos cell, BlockPos root) {
        Vec3 origin = Vec3.atBottomCenterOf(root);
        return ports(shape, cell).stream().map(port -> new Port(origin.add(port.center), port.outward)).toList();
    }

    static Connection connection(BlockGetter level, BlockPos root, Port port) {
        BlockPos landing = port.landing();
        for (int dy = 0; dy >= -1; dy--) {
            BlockPos next = landing.offset(0, dy, 0);
            var state = level.getBlockState(next);
            if (state.getBlock() instanceof TrackBlock track) {
                var shape = track.shape(state);
                int index = track.index(state);
                if (index >= shape.cells().size()) continue;
                BlockPos other = next.subtract(shape.cells().get(index));
                if (other.equals(root)) continue;
                for (var path : shape.routes()) {
                    if (joins(path, Vec3.atBottomCenterOf(other), port)) {
                        return TrackBlock.ballasted(state) ? Connection.RAIL : Connection.BRIDGE;
                    }
                }
            } else if (BaseRailBlock.isRail(state)) {
                var railShape = state.getOptionalValue(BlockStateProperties.RAIL_SHAPE)
                    .or(() -> state.getOptionalValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT));
                if (railShape.isPresent() && joins(RailNetwork.vanilla(railShape.get()), Vec3.atBottomCenterOf(next), port)) {
                    return Connection.RAIL;
                }
            }
        }
        return Connection.OPEN;
    }

    private static boolean joins(TrackGeometry.Path path, Vec3 origin, Port port) {
        for (boolean end : new boolean[]{false, true}) {
            Vec3 inward = horizontal(path.tangent(end ? path.length : 0)).scale(end ? -1 : 1);
            if (origin.add(path.endpoint(end)).distanceToSqr(port.center) < MATCH_DISTANCE_SQR
                && inward.dot(port.outward) > 0.35) return true;
        }
        return false;
    }

    private static Vec3 horizontal(Vec3 vector) { return vector.multiply(1, 0, 1).normalize(); }
}
