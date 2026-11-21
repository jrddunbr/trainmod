package org.ja4.trainmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class SignalCabin extends DoorBlock {
    SignalCabin(Properties properties) {
        super(BlockSetType.COPPER, properties);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (context.getPlayer() != null && !context.getLevel().mayInteract(context.getPlayer(), context.getClickedPos().above())) {
            return null;
        }
        return super.getStateForPlacement(context);
    }

    static Direction entrance(BlockState state) { return state.getValue(FACING).getOpposite(); }

    static Direction panel(BlockState state) {
        Direction entrance = entrance(state);
        return !state.getValue(OPEN) ? entrance : state.getValue(HINGE) == DoorHingeSide.RIGHT
            ? entrance.getCounterClockWise() : entrance.getClockWise();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = walls(state);
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? Shapes.or(shape, box(0, 7, 0, 16, 8, 16)) : shape;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return walls(state);
    }

    private static VoxelShape walls(BlockState state) {
        double height = state.getValue(HALF) == DoubleBlockHalf.UPPER ? 8 : 16;
        var walls = Shapes.rotateHorizontal(box(0, 0, 0, 16, height, 0.85));
        VoxelShape result = Shapes.empty();
        Direction entrance = entrance(state);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (side != entrance || side == panel(state)) result = Shapes.or(result, walls.get(side));
        }
        return result;
    }
}
