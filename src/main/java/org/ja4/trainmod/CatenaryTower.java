package org.ja4.trainmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class CatenaryTower extends HorizontalDirectionalBlock implements EntityBlock {
    static final BooleanProperty DIAGONAL = BooleanProperty.create("diagonal");
    static final int STEEL_COLOR = 0x4c5b62;
    static final int INSULATOR_COLOR = 0xe1d8b2;
    static final double GANTRY_CONTACT = 4 / 16.0;
    static final double GANTRY_BEAM = 8 / 16.0;

    enum Kind {
        SINGLE(3, 1), DOUBLE(3, 1, 4), GANTRY(4, 1);

        final int height;
        final int[] reaches;

        Kind(int height, int... reaches) {
            this.height = height;
            this.reaches = reaches;
        }

        int reach(int track, boolean diagonal) {
            int distance = reaches[track];
            return diagonal && this == DOUBLE && track == 1 ? distance - 1 : distance;
        }

        int maxReach(boolean diagonal) { return reach(reaches.length - 1, diagonal) + 1; }

        boolean reaches(int distance, boolean angled, boolean diagonal) {
            for (int track = 0; track < reaches.length; track++) {
                int reach = reach(track, diagonal);
                if (reach == distance || angled && reach + 1 == distance) return true;
            }
            return false;
        }
    }

    final Kind kind;

    CatenaryTower(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        registerDefaultState(defaultBlockState().setValue(DIAGONAL, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING, DIAGONAL);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        int turn = Math.floorMod(Math.round(context.getRotation() / 45f), 8);
        return defaultBlockState().setValue(FACING, Direction.from2DDataValue(turn / 2))
            .setValue(DIAGONAL, kind != Kind.GANTRY && (turn & 1) != 0);
    }

    static BlockPos armStep(BlockState state) {
        return armStep(state.getValue(FACING), state.getValue(DIAGONAL));
    }

    static BlockPos armStep(Direction direction, boolean diagonal) {
        Direction right = direction.getClockWise();
        return new BlockPos(direction.getStepX() + (diagonal ? right.getStepX() : 0), 0,
            direction.getStepZ() + (diagonal ? right.getStepZ() : 0));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        if (mirror == Mirror.NONE) return state;
        Direction facing = state.getValue(FACING);
        if (state.getValue(DIAGONAL)) facing = facing.getClockWise();
        return state.setValue(FACING, mirror.mirror(facing));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return kind == Kind.GANTRY ? box(6, 4, 6, 10, 9, 10)
            : Shapes.or(box(6, 0, 6, 10, 16, 10), box(4.5, 0, 4.5, 11.5, 16 * TrackGeometry.RAIL_HEIGHT, 11.5));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Anchor(pos, state);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        if (kind != Kind.GANTRY) TransmissionExtension.removeFromPole(level, pos);
    }

    static final class Anchor extends BlockEntity {
        Anchor(BlockPos pos, BlockState state) {
            super(Trainmod.CATENARY_ANCHOR.get(), pos, state);
        }

        @Override
        public void onLoad() {
            super.onLoad();
            CatenaryPower.trackTower(level, worldPosition, true);
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            CatenaryPower.trackTower(level, worldPosition, false);
        }
    }
}
