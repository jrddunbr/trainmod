package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

final class WideGirder extends BridgeBlock {
    static final IntegerProperty PART = IntegerProperty.create("part", 0, 2);
    static final int CENTER = 1;
    static final int GIRDER_DEPTH = 8;
    static final int WEST_EDGE = 0;
    static final int EAST_EDGE = 12;
    private static final VoxelShape DECK = box(0, GIRDER_DEPTH, 0, 16, 16, 16);
    private static final List<Map<Direction.Axis, VoxelShape>> FEET = supports(GIRDER_DEPTH);
    private static final List<Map<Direction.Axis, VoxelShape>> COLUMNS = supports(16);

    WideGirder(Properties properties) {
        super(Kind.WIDE_GIRDER, properties);
        registerDefaultState(defaultBlockState().setValue(PART, CENTER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(PART);
    }

    static Direction across(Direction.Axis axis) {
        return axis == Direction.Axis.Z ? Direction.EAST : Direction.SOUTH;
    }

    static BlockPos center(BlockPos pos, BlockState state) {
        return pos.relative(across(state.getValue(AXIS)), CENTER - state.getValue(PART));
    }

    private static List<Map<Direction.Axis, VoxelShape>> supports(int height) {
        return List.of(support(0, height), Map.of(), support(2, height));
    }

    private static Map<Direction.Axis, VoxelShape> support(int part, int height) {
        int x = part == 0 ? WEST_EDGE : EAST_EDGE;
        return Shapes.rotateHorizontalAxis(box(x, 0, 5, x + 4, height, 11));
    }

    @Override
    boolean spanCenter(BlockState state) {
        return state.getValue(PART) == CENTER;
    }

    @Override
    boolean pier(BlockGetter level, BlockPos pos, Direction.Axis axis) {
        if (super.pier(level, pos, axis)) return true;
        Direction across = across(axis);
        int height = pos.getY() - level.getMinY();
        BlockState section = defaultBlockState().setValue(AXIS, axis);
        return grounded(level, pos.relative(across.getOpposite()), height, section.setValue(PART, 0)::equals)
            || grounded(level, pos.relative(across), height, section.setValue(PART, 2)::equals);
    }

    @Override
    boolean girderColumn(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(this) && hasColumnAbove(level, pos, state);
    }

    @Override
    boolean girderFeet(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(this) && hasFooting(level, pos, state);
    }

    private static boolean hasColumnAbove(BlockGetter level, BlockPos pos, BlockState state) {
        return level.getBlockState(pos.above()) == state;
    }

    private static boolean hasFooting(BlockGetter level, BlockPos pos, BlockState state) {
        return state.getValue(PART) != CENTER && (level.getBlockState(pos.below()) == state || solidTop(level, pos.below()));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int part = state.getValue(PART);
        boolean column = hasColumnAbove(level, pos, state);
        if (part == CENTER) return column ? Shapes.empty() : DECK;
        Direction.Axis axis = state.getValue(AXIS);
        if (column) return COLUMNS.get(part).get(axis);
        return hasFooting(level, pos, state) ? Shapes.or(DECK, FEET.get(part).get(axis)) : DECK;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return turnPart(super.rotate(state, rotation), state, rotation.rotate(across(state.getValue(AXIS))));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return turnPart(state, state, mirror.mirror(across(state.getValue(AXIS))));
    }

    private static BlockState turnPart(BlockState result, BlockState original, Direction across) {
        int part = original.getValue(PART);
        return result.setValue(PART, across.getAxisDirection() == Direction.AxisDirection.POSITIVE ? part : 2 - part);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel server && player instanceof ServerPlayer miner) {
            liftSection(server, miner, pos, state);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    private static void liftSection(ServerLevel level, ServerPlayer miner, BlockPos pos, BlockState state) {
        BlockPos root = center(pos, state);
        Direction across = across(state.getValue(AXIS));
        var remaining = new ArrayList<BlockPos>();
        for (int part = 0; part < 3; part++) {
            if (part == state.getValue(PART)) continue;
            BlockPos cell = root.relative(across, part - CENTER);
            if (!level.isLoaded(cell)) return;
            BlockState actual = level.getBlockState(cell);
            if (actual != state.setValue(PART, part)) continue;
            if (!level.mayInteract(miner, cell) || miner.blockActionRestricted(level, cell, miner.gameMode())
                || NeoForge.EVENT_BUS.post(new BreakBlockEvent(level, cell, actual, miner)).isCanceled()) return;
            remaining.add(cell);
        }
        if (remaining.contains(root) && !miner.preventsBlockDrops()) {
            dropResources(level.getBlockState(root), level, root, null, miner, miner.getMainHandItem());
        }
        remaining.forEach(cell -> level.removeBlock(cell, false));
    }

    static final class Kit extends BlockItem {
        Kit(WideGirder block, Properties properties) {
            super(block, properties);
        }

        @Override
        protected BlockState getPlacementState(BlockPlaceContext context) {
            BlockState state = super.getPlacementState(context);
            if (state == null) return null;
            var level = context.getLevel();
            var pos = context.getClickedPos();
            Direction across = across(state.getValue(AXIS));
            for (int part : new int[]{0, 2}) {
                BlockPos wing = pos.relative(across, part - CENTER);
                if (!level.isInWorldBounds(wing) || !level.isLoaded(wing) || !level.getBlockState(wing).canBeReplaced()
                    || !level.isUnobstructed(state.setValue(PART, part), wing, CollisionContext.placementContext(context.getPlayer()))) {
                    return null;
                }
            }
            return state;
        }

        @Override
        protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
            if (context.getLevel().isClientSide()) return super.placeBlock(context, state);
            if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) return false;
            BlockPos center = context.getClickedPos();
            Direction across = across(state.getValue(AXIS));
            var changes = new LinkedHashMap<BlockPos, BlockState>();
            for (int part = 0; part < 3; part++) {
                BlockPos pos = center.relative(across, part - CENTER);
                if (!level.mayInteract(player, pos) || player.blockActionRestricted(level, pos, player.gameMode())) return false;
                changes.put(pos, state.setValue(PART, part));
            }
            return TrackWork.apply(level, player, changes, level.getBlockState(center.relative(context.getClickedFace().getOpposite())));
        }
    }
}
