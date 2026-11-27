package org.ja4.trainmod;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class HolidayDecoration extends HorizontalDirectionalBlock {
    enum Kind { SLEIGH, WREATH, WREATH_CRATE }

    private final Kind kind;
    private final Map<Direction, VoxelShape> shapes;

    HolidayDecoration(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        shapes = Shapes.rotateHorizontal(outline(switch (kind) {
            case SLEIGH -> FestiveModels.sleigh();
            case WREATH -> FestiveModels.wallWreath();
            case WREATH_CRATE -> FestiveModels.wreathCrate();
        }));
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    private static VoxelShape outline(List<VehicleModels.Box> boxes) {
        VoxelShape shape = Shapes.empty();
        for (var box : boxes) {
            shape = Shapes.or(shape, box(box.x() + 8, box.y(), box.z() + 8,
                box.x() + box.width() + 8, box.y() + box.height(), box.z() + box.length() + 8));
        }
        return shape.optimize();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state.getValue(FACING));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = kind == Kind.WREATH ? context.getClickedFace() : context.getHorizontalDirection().getOpposite();
        return facing.getAxis().isHorizontal() ? defaultBlockState().setValue(FACING, facing) : null;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (kind != Kind.WREATH) return true;
        Direction facing = state.getValue(FACING);
        BlockPos support = pos.relative(facing.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, facing);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction direction, BlockPos neighborPos, BlockState neighbor, RandomSource random) {
        if (kind == Kind.WREATH && direction == state.getValue(FACING).getOpposite() && !canSurvive(state, level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighbor, random);
    }
}
