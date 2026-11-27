package org.ja4.trainmod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class ChristmasTree extends Block {
    static final IntegerProperty PART = IntegerProperty.create("part", 0, FestiveModels.TREE_HEIGHT - 1);
    private static final List<VoxelShape> SECTIONS = IntStream.range(0, FestiveModels.TREE_HEIGHT)
        .mapToObj(part -> shape(FestiveModels.treeSection(part))).toList();

    ChristmasTree(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(PART, 0));
    }

    private static VoxelShape shape(List<VehicleModels.Box> boxes) {
        VoxelShape shape = Shapes.empty();
        for (var box : boxes) {
            shape = Shapes.or(shape, box(box.x() + 8, box.y(), box.z() + 8,
                box.x() + box.width() + 8, box.y() + box.height(), box.z() + box.length() + 8));
        }
        return shape.optimize();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SECTIONS.get(state.getValue(PART));
    }

    private boolean isSection(BlockState state, int part) {
        return state.is(this) && state.getValue(PART) == part;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        int part = state.getValue(PART);
        BlockState support = level.getBlockState(below);
        return part == 0 ? support.isFaceSturdy(level, below, Direction.UP) : isSection(support, part - 1);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction direction, BlockPos neighborPos, BlockState neighbor, RandomSource random) {
        int part = state.getValue(PART);
        if (direction == Direction.DOWN && !canSurvive(state, level, pos)
            || direction == Direction.UP && part < FestiveModels.TREE_HEIGHT - 1 && !isSection(neighbor, part + 1)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighbor, random);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        int part = state.getValue(PART);
        if (!level.isClientSide() && part > 0) {
            BlockPos base = pos.below(part);
            BlockState baseState = level.getBlockState(base);
            if (isSection(baseState, 0)) {
                if (!player.preventsBlockDrops()) {
                    dropResources(baseState, level, base, null, player, player.getMainHandItem());
                }
                level.setBlock(base, Blocks.AIR.defaultBlockState(), UPDATE_ALL | UPDATE_SUPPRESS_DROPS);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    static final class Kit extends BlockItem {
        Kit(ChristmasTree block, Properties properties) {
            super(block, properties);
        }

        @Override
        protected BlockState getPlacementState(BlockPlaceContext context) {
            BlockState state = super.getPlacementState(context);
            if (state == null) return null;
            var level = context.getLevel();
            var base = context.getClickedPos();
            if (!level.getFluidState(base).isEmpty()) return null;
            var collision = CollisionContext.placementContext(context.getPlayer());
            for (int part = 1; part < FestiveModels.TREE_HEIGHT; part++) {
                BlockPos pos = base.above(part);
                if (!level.isInWorldBounds(pos) || !level.isLoaded(pos) || !level.getFluidState(pos).isEmpty()
                    || !level.getBlockState(pos).canBeReplaced() || !level.isUnobstructed(state.setValue(PART, part), pos, collision)) {
                    return null;
                }
            }
            return state;
        }

        @Override
        protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
            if (context.getLevel().isClientSide()) return super.placeBlock(context, state);
            if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) return false;
            BlockPos base = context.getClickedPos();
            var changes = new LinkedHashMap<BlockPos, BlockState>();
            for (int part = 0; part < FestiveModels.TREE_HEIGHT; part++) {
                BlockPos pos = base.above(part);
                if (!level.mayInteract(player, pos) || player.blockActionRestricted(level, pos, player.gameMode())) return false;
                changes.put(pos, state.setValue(PART, part));
            }
            return TrackWork.apply(level, player, changes, level.getBlockState(base.relative(context.getClickedFace().getOpposite())));
        }
    }
}
