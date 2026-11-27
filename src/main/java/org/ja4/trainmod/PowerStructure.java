package org.ja4.trainmod;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

final class PowerStructure {
    private PowerStructure() {}

    static boolean place(Level level, ServerPlayer player, Map<BlockPos, BlockState> parts, BlockPos against) {
        return level instanceof ServerLevel server && fits(level, player, parts)
            && TrackWork.apply(server, player, parts, level.getBlockState(against));
    }

    static boolean fits(Level level, Player player, Map<BlockPos, BlockState> parts) {
        for (var entry : parts.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!level.isInWorldBounds(pos) || !level.isLoaded(pos) || !level.mayInteract(player, pos)
                || player instanceof ServerPlayer server && server.blockActionRestricted(level, pos, server.gameMode())
                || !level.getBlockState(pos).canBeReplaced()
                || !level.isUnobstructed(entry.getValue(), pos, CollisionContext.placementContext(player))) return false;
        }
        return true;
    }

    static boolean isComplete(Level level, Map<BlockPos, BlockState> parts) {
        return parts.entrySet().stream().allMatch(entry -> level.isLoaded(entry.getKey())
            && level.getBlockState(entry.getKey()) == entry.getValue());
    }

    static boolean matches(Level level, BlockPos pos, BlockState state) {
        return level.isLoaded(pos) && level.getBlockState(pos) == state;
    }

    static void remove(ServerLevel level, Map<BlockPos, BlockState> parts) {
        // A bank can straddle a chunk boundary. Resolve all its positions before removing any.
        var remaining = new LinkedHashMap<BlockPos, BlockState>();
        parts.forEach((pos, state) -> {
            if (level.getBlockState(pos) == state) remaining.put(pos, state);
        });
        TrackWork.removeTrack(level, remaining);
    }
}
