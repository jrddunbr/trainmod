package org.ja4.trainmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class BridgeBlock extends Block {
    enum Kind { TRESTLE, GIRDER, STONE }

    static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    private static final VoxelShape TRESTLE_DECK = box(0, 13, 0, 16, 16, 16);
    private static final VoxelShape GIRDER_SHAPE = box(0, 8, 0, 16, 16, 16);
    private static final VoxelShape GIRDER_COLUMN = Shapes.or(box(2, 0, 5, 6, 16, 11), box(11, 0, 5, 15, 16, 11));
    private static final VoxelShape GIRDER_FEET = Shapes.or(box(2, 0, 5, 6, 8, 11), box(11, 0, 5, 15, 8, 11));
    private static final VoxelShape STONE_DECK = box(0, 11, 0, 16, 16, 16);

    final Kind kind;

    BridgeBlock(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        var level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState below = level.getBlockState(pos.below());
        Direction.Axis axis = context.getClickedFace().getAxis();
        if (kind != Kind.STONE && below.is(this)) axis = below.getValue(AXIS);
        else if (!axis.isHorizontal()) axis = context.getHorizontalDirection().getAxis();
        return canPlace(level, pos, axis) ? defaultBlockState().setValue(AXIS, axis) : null;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(AXIS, rotation.rotate(Direction.get(Direction.AxisDirection.POSITIVE, state.getValue(AXIS))).getAxis());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (kind) {
            case TRESTLE -> trestleShape(level, pos, state.getValue(AXIS));
            case GIRDER -> girderShape(level, pos, state.getValue(AXIS));
            case STONE -> column(level, pos) ? Shapes.block() : STONE_DECK;
        };
    }

    boolean canPlace(BlockGetter level, BlockPos pos, Direction.Axis axis) {
        if (kind == Kind.TRESTLE) return grounded(level, pos, TrestleGeometry.HEIGHT);
        boolean stacked = level.getBlockState(pos.below()).is(this);
        if (kind == Kind.GIRDER) {
            return stacked || spanSupported(level, pos, axis, 6) || sideSupported(level, pos, axis);
        }
        return stacked ? grounded(level, pos, 6) : spanSupported(level, pos, axis, 4);
    }

    private boolean spanSupported(BlockGetter level, BlockPos pos, Direction.Axis axis, int span) {
        if (pier(level, pos)) return true;
        for (Direction.AxisDirection heading : Direction.AxisDirection.values()) {
            Direction direction = Direction.get(heading, axis);
            for (int distance = 1; distance <= span; distance++) {
                BlockPos step = pos.relative(direction, distance);
                if (!aligned(level.getBlockState(step), axis)) break;
                if (pier(level, step)) return true;
            }
        }
        return false;
    }

    private boolean sideSupported(BlockGetter level, BlockPos pos, Direction.Axis axis) {
        for (Direction.AxisDirection heading : Direction.AxisDirection.values()) {
            BlockPos neighbor = pos.relative(Direction.get(heading, axis));
            BlockState support = level.getBlockState(neighbor);
            if (support.is(this) && support.getValue(AXIS) != axis
                && spanSupported(level, neighbor, support.getValue(AXIS), 6)) return true;
        }
        return false;
    }

    private boolean pier(BlockGetter level, BlockPos pos) {
        int height = kind == Kind.STONE ? 6 : Math.max(1, pos.getY() - level.getMinY());
        return grounded(level, pos, height);
    }

    boolean girderColumn(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos.above()).is(this);
    }

    boolean girderFeet(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos.below()).is(this) || solidTop(level, pos.below());
    }

    private VoxelShape girderShape(BlockGetter level, BlockPos pos, Direction.Axis axis) {
        boolean column = girderColumn(level, pos);
        if (!column && !girderFeet(level, pos)) return GIRDER_SHAPE;
        VoxelShape feet = column ? GIRDER_COLUMN : GIRDER_FEET;
        if (axis == Direction.Axis.X) feet = Shapes.rotateHorizontal(feet).get(Direction.EAST);
        return column ? feet : Shapes.or(GIRDER_SHAPE, feet);
    }

    int trestleDepth(BlockGetter level, BlockPos pos) {
        int depth = 0;
        while (depth < TrestleGeometry.HEIGHT - 1 && level.getBlockState(pos.above(depth + 1)).is(this)) depth++;
        return depth;
    }

    private VoxelShape trestleShape(BlockGetter level, BlockPos pos, Direction.Axis axis) {
        int depth = trestleDepth(level, pos);
        double inner = TrestleGeometry.offset(depth);
        double outer = TrestleGeometry.offset(depth + 1);
        double left = 8 - TrestleGeometry.LEG_HALF_WIDTH;
        double right = 8 + TrestleGeometry.LEG_HALF_WIDTH;
        VoxelShape legs = Shapes.or(box(left - outer, 0, 1, right - inner, 16, 15),
            box(left + inner, 0, 1, right + outer, 16, 15));
        if (axis == Direction.Axis.X) legs = Shapes.rotateHorizontal(legs).get(Direction.EAST);
        return depth == 0 ? Shapes.or(TRESTLE_DECK, legs) : legs;
    }

    boolean aligned(BlockState state, Direction.Axis axis) {
        return state.is(this) && state.getValue(AXIS) == axis;
    }

    boolean column(BlockGetter level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.is(this) || solidTop(level, pos.below());
    }

    private boolean grounded(BlockGetter level, BlockPos pos, int maxHeight) {
        for (int depth = 1; depth <= maxHeight; depth++) {
            BlockPos below = pos.below(depth);
            if (level.getBlockState(below).is(this)) continue;
            return solidTop(level, below);
        }
        return false;
    }

    static boolean solidTop(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP);
    }

    static boolean supportsRail(BlockState state) { return state.getBlock() instanceof BridgeBlock; }
}
