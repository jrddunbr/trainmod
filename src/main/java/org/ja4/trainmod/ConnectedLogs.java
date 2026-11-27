package org.ja4.trainmod;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;

final class ConnectedLogs {
    private static final int MAX_LOGS = 4096;

    private ConnectedLogs() {}

    static boolean isLog(ServerLevel level, BlockPos pos) {
        return level.isInWorldBounds(pos) && level.isLoaded(pos) && level.getBlockState(pos).is(BlockTags.LOGS);
    }

    static boolean supportsTrack(ServerLevel level, BlockPos log) {
        return RailNetwork.isRail(level.getBlockState(log.above()));
    }

    static boolean follow(ServerLevel level, List<BlockPos> roots, Predicate<BlockPos> canFollow, Set<BlockPos> logs) {
        var frontier = new LinkedHashSet<>(roots);
        while (!frontier.isEmpty()) {
            BlockPos log = frontier.removeFirst();
            if (!logs.add(log)) continue;
            if (logs.size() > MAX_LOGS) return false;
            for (BlockPos nearby : BlockPos.betweenClosed(log.offset(-1, -1, -1), log.offset(1, 1, 1))) {
                if (!logs.contains(nearby) && isLog(level, nearby) && !supportsTrack(level, nearby) && canFollow.test(nearby)) {
                    frontier.add(nearby.immutable());
                }
            }
        }
        return true;
    }
}
