package org.ja4.trainmod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

final class SwitchPoints extends BlockEntity {
    private final PartMotion motion;

    SwitchPoints(BlockPos position, BlockState state) {
        super(Trainmod.SWITCH_POINTS.get(), position, state);
        motion = new PartMotion(position(state));
    }

    double throwPosition(double time) { return motion.at(time); }

    private static double position(BlockState state) {
        return state.getValue(BlockStateProperties.POWERED) ? 1 : 0;
    }

    @Override
    public void setBlockState(BlockState state) {
        if (level != null && level.isClientSide() && state.is(getBlockState().getBlock())) {
            motion.moveTo(position(state), level.getGameTime());
        }
        super.setBlockState(state);
    }
}
