package org.ja4.trainmod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class SwitchStand extends BlockEntity {
    private final PartMotion motion;

    SwitchStand(BlockPos position, BlockState state) {
        super(Trainmod.SWITCH_STAND.get(), position, state);
        motion = new PartMotion(state.getValue(RailwayBlocks.Stand.TARGET).angle);
    }

    double angle(double time) { return motion.at(time); }

    @Override
    public void setBlockState(BlockState state) {
        if (level != null && level.isClientSide() && state.is(getBlockState().getBlock())) {
            motion.moveTo(state.getValue(RailwayBlocks.Stand.TARGET).angle, level.getGameTime());
        }
        super.setBlockState(state);
    }
}
