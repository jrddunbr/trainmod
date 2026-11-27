package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

final class TransmissionLines {
    static final int RANGE = 256;
    static final int BANK_RANGE = 16;

    private enum Clearance { CLEAR, BLOCKED, UNLOADED }

    private TransmissionLines() {}

    static Map<BlockPos, Set<BlockPos>> connect(Level level, Set<BlockPos> tips) {
        Map<BlockPos, List<BlockPos>> columns = new HashMap<>();
        for (BlockPos tip : tips) {
            if (!validTip(level, tip)) continue;
            columns.computeIfAbsent(tip.atY(0), ignored -> new ArrayList<>()).add(tip);
        }
        Map<BlockPos, Set<BlockPos>> links = new HashMap<>();
        for (var column : columns.values()) {
            for (BlockPos tip : column) {
                links.computeIfAbsent(tip, ignored -> new LinkedHashSet<>());
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        BlockPos neighbor = nearest(level, columns, tip, dx, dz);
                        if (neighbor == null) continue;
                        links.get(tip).add(neighbor);
                        links.computeIfAbsent(neighbor, ignored -> new LinkedHashSet<>()).add(tip);
                    }
                }
            }
        }
        return links;
    }

    private static boolean validTip(Level level, BlockPos tip) {
        BlockPos pole = tip.below(TransmissionExtension.START + TransmissionExtension.TOP);
        return level.isLoaded(pole) && TransmissionExtension.compatible(level.getBlockState(pole))
            && TransmissionExtension.complete(level, pole);
    }

    static List<BlockPos> visibleNeighbors(Level level, BlockPos tip, Set<BlockPos> connected, List<BlockPos> previous) {
        var visible = new LinkedHashSet<>(connected);
        if (!validTip(level, tip)) return List.of();
        for (BlockPos neighbor : previous) {
            if (visible.contains(neighbor) || level.isLoaded(neighbor) && !validTip(level, neighbor)) continue;
            if (clearance(level, TransmissionExtension.terminal(tip), TransmissionExtension.terminal(neighbor), tip, neighbor)
                == Clearance.UNLOADED && connected.stream().noneMatch(nearer -> sameDirectionAndCloser(tip, nearer, neighbor))) {
                visible.add(neighbor);
            }
        }
        return visible.stream().sorted().toList();
    }

    private static boolean sameDirectionAndCloser(BlockPos tip, BlockPos nearer, BlockPos farther) {
        var a = nearer.subtract(tip);
        var b = farther.subtract(tip);
        return Integer.signum(a.getX()) == Integer.signum(b.getX()) && Integer.signum(a.getZ()) == Integer.signum(b.getZ())
            && a.getX() * a.getX() + a.getZ() * a.getZ() < b.getX() * b.getX() + b.getZ() * b.getZ();
    }

    private static BlockPos nearest(Level level, Map<BlockPos, List<BlockPos>> columns, BlockPos tip, int dx, int dz) {
        int limit = (dx == 0 || dz == 0) ? RANGE : (int) (RANGE / Math.sqrt(2));
        for (int step = 1; step <= limit; step++) {
            List<BlockPos> candidates = columns.get(tip.offset(dx * step, -tip.getY(), dz * step));
            if (candidates == null) continue;
            var ordered = candidates.stream().sorted(Comparator.comparingInt(candidate -> Math.abs(candidate.getY() - tip.getY()))).toList();
            for (BlockPos candidate : ordered) {
                if (clear(level, TransmissionExtension.terminal(tip), TransmissionExtension.terminal(candidate), tip, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    static BlockPos nearestBankTip(Level level, BlockPos root, Direction facing, Set<BlockPos> tips) {
        Vec3 start = TransformerBank.transmissionTerminal(root, facing);
        BlockPos bushing = BlockPos.containing(start);
        return tips.stream().filter(tip -> start.distanceToSqr(TransmissionExtension.terminal(tip)) <= BANK_RANGE * BANK_RANGE)
            .sorted(Comparator.comparingDouble((BlockPos tip) -> start.distanceToSqr(TransmissionExtension.terminal(tip)))
                .thenComparing(Comparator.naturalOrder()))
            .filter(tip -> clear(level, start, TransmissionExtension.terminal(tip), bushing, tip))
            .findFirst().orElse(null);
    }

    private static boolean clear(Level level, Vec3 start, Vec3 end, BlockPos first, BlockPos last) {
        return clearance(level, start, end, first, last) == Clearance.CLEAR;
    }

    private static Clearance clearance(Level level, Vec3 start, Vec3 end, BlockPos first, BlockPos last) {
        boolean[] unloaded = {false};
        return BlockGetter.traverseBlocks(start, end, level, (world, pos) -> {
            if (!world.isLoaded(pos)) {
                unloaded[0] = true;
                return null;
            }
            if (pos.equals(first) || pos.equals(last)) return null;
            var shape = world.getBlockState(pos).getCollisionShape(world, pos);
            return shape.clip(start, end, pos) == null ? null : Clearance.BLOCKED;
        }, ignored -> unloaded[0] ? Clearance.UNLOADED : Clearance.CLEAR);
    }
}
