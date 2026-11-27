package org.ja4.trainmod;

import com.mojang.serialization.Codec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class TransmissionExtension extends Block implements EntityBlock {
    static final int HEIGHT = 4;
    static final int START = 4;
    static final int TOP = HEIGHT - 1;
    static final int LEGACY_TOP = 7;
    static final IntegerProperty PART = IntegerProperty.create("part", 0, LEGACY_TOP);
    private static final VoxelShape POST = beam(16);
    private static final VoxelShape INSULATED_POST = Shapes.or(beam(4), box(6, 4, 6, 10, 8.5, 10),
        box(7.2, 8.5, 7.2, 8.8, 9, 8.8));

    TransmissionExtension(Properties properties) { super(properties); }

    static boolean compatible(BlockState state) {
        return state.getBlock() instanceof CatenaryTower tower && tower.kind != CatenaryTower.Kind.GANTRY;
    }

    static BlockPos tip(BlockPos pole) { return pole.above(START + TOP); }

    static Vec3 terminal(BlockPos tip) { return Vec3.atBottomCenterOf(tip).add(0, 9 / 16.0, 0); }

    static Map<BlockPos, BlockState> parts(BlockPos pole) { return parts(pole, 0, TOP); }

    static Map<BlockPos, BlockState> parts(BlockPos pole, int first, int last) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        BlockState state = Trainmod.TRANSMISSION_EXTENSION.get().defaultBlockState();
        for (int i = first; i <= last; i++) result.put(pole.above(START + i), state.setValue(PART, i));
        return result;
    }

    static boolean complete(Level level, BlockPos pole) {
        if (!PowerStructure.isComplete(level, parts(pole))) return false;
        var next = level.getBlockState(tip(pole).above());
        return !next.is(Trainmod.TRANSMISSION_EXTENSION.get()) || next.getValue(PART) != HEIGHT;
    }

    static void removeFromPole(ServerLevel level, BlockPos pole) {
        var parts = parts(pole, 0, LEGACY_TOP);
        if (parts.entrySet().stream().noneMatch(entry -> PowerStructure.matches(level, entry.getKey(), entry.getValue()))) return;
        PowerStructure.remove(level, parts);
        popResource(level, pole, Trainmod.TRANSMISSION_KIT.toStack());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(PART); }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int part = state.getValue(PART);
        return part == TOP || part == LEGACY_TOP ? INSULATED_POST : POST;
    }

    private static VoxelShape beam(double top) {
        return Shapes.or(box(6.15, 0, 6.45, 9.85, top, 7), box(7.58, 0, 7, 8.42, top, 9),
            box(6.15, 0, 9, 9.85, top, 9.55));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        int part = state.getValue(PART);
        return part == TOP || part == LEGACY_TOP ? new Tip(pos, state) : null;
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        PowerStructure.remove(level, parts(pos.below(START + state.getValue(PART)), 0, LEGACY_TOP));
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(PART) != LEGACY_TOP) return;
        BlockPos pole = pos.below(START + LEGACY_TOP);
        if (!compatible(level.getBlockState(pole)) || !PowerStructure.isComplete(level, parts(pole, 0, LEGACY_TOP))) return;
        PowerStructure.remove(level, parts(pole, HEIGHT, LEGACY_TOP));
        // The fourth block in an old extension did not have a block entity.
        BlockPos newTip = tip(pole);
        level.getBlockEntity(newTip);
        BlockState newState = level.getBlockState(newTip);
        level.sendBlockUpdated(newTip, newState, newState, Block.UPDATE_CLIENTS);
    }

    static final class Kit extends BlockItem {
        Kit(TransmissionExtension block, Properties properties) { super(block, properties); }

        @Override
        public InteractionResult useOn(UseOnContext context) {
            Level level = context.getLevel();
            BlockPos pole = context.getClickedPos();
            if (!compatible(level.getBlockState(pole))) return InteractionResult.PASS;
            if (level.isClientSide()) return InteractionResult.SUCCESS;
            if (!(context.getPlayer() instanceof ServerPlayer player) || !level.mayInteract(player, pole)
                || !PowerStructure.place(level, player, parts(pole), pole)) return InteractionResult.FAIL;
            context.getItemInHand().consume(1, player);
            level.playSound(null, pole, net.minecraft.sounds.SoundEvents.CHAIN_PLACE, net.minecraft.sounds.SoundSource.BLOCKS);
            return InteractionResult.SUCCESS;
        }
    }

    static final class Tip extends BlockEntity {
        private static final Codec<List<BlockPos>> LINKS = BlockPos.CODEC.listOf();
        private List<BlockPos> neighbors = List.of();

        Tip(BlockPos pos, BlockState state) { super(Trainmod.TRANSMISSION_TIP.get(), pos, state); }

        List<BlockPos> neighbors() { return neighbors; }

        void updateNeighbors(ServerLevel server, List<BlockPos> connected) {
            if (neighbors.equals(connected)) return;
            neighbors = connected;
            setChanged();
            server.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            super.loadAdditional(input);
            neighbors = input.read("Neighbors", LINKS).orElseGet(List::of);
        }

        @Override
        protected void saveAdditional(ValueOutput output) {
            super.saveAdditional(output);
            if (!neighbors.isEmpty()) output.store("Neighbors", LINKS, neighbors);
        }

        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            var tag = new CompoundTag();
            if (!neighbors.isEmpty()) tag.store("Neighbors", LINKS, neighbors);
            return tag;
        }

        @Override
        public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

        @Override
        public void onLoad() {
            super.onLoad();
            if (getBlockState().getValue(PART) == TOP) CatenaryPower.trackTip(level, worldPosition, true);
            else if (level instanceof ServerLevel server) server.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            if (getBlockState().getValue(PART) == TOP) CatenaryPower.trackTip(level, worldPosition, false);
        }
    }
}
