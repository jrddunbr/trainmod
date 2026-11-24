package org.ja4.trainmod;

import java.util.Map;
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
    private static final Map<Direction, VoxelShape> ROOF = Shapes.rotateHorizontal(roofOutline());

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
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? Shapes.or(shape, ROOF.get(entrance(state))) : shape;
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

    private static VoxelShape roofOutline() {
        VoxelShape result = Shapes.empty();
        double edge = SignalCabinGeometry.ROOF_OVERHANG;
        double width = 16 + 2 * edge;
        for (int i = 0; i < 16; i++) {
            double x1 = -edge + width * i / 16;
            double x2 = x1 + width / 16;
            double y1 = SignalCabinGeometry.roofUnderside(x1), y2 = SignalCabinGeometry.roofUnderside(x2);
            result = Shapes.or(result, box(x1, Math.min(y1, y2) - 16, -edge,
                x2, Math.max(y1, y2) + SignalCabinGeometry.ROOF_THICKNESS - 16, 16 + edge));
        }
        return result;
    }
}
