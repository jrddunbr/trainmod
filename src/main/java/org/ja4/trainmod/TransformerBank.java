package org.ja4.trainmod;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

final class TransformerBank extends HorizontalDirectionalBlock implements EntityBlock {
    static final int CAPACITY = 64_000;
    static final int TRACK_OFFSET = 3;
    static final IntegerProperty PART = IntegerProperty.create("part", 0, 11);

    TransformerBank(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(PART, 0));
    }

    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.Energy.BLOCK, (level, pos, state, entity, side) -> {
            Direction facing = state.getValue(FACING);
            if (side != facing.getOpposite() || (state.getValue(PART) & 2) == 0) return null;
            BlockPos root = root(pos, state);
            return level.isLoaded(root) && level.getBlockEntity(root) instanceof Bank bank && bank.complete()
                ? bank.energy : null;
        }, Trainmod.TRANSFORMER_BANK.get());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, PART); }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return mirror == Mirror.NONE ? state : state.setValue(FACING, mirror.mirror(state.getValue(FACING)))
            .setValue(PART, state.getValue(PART) ^ 1);
    }

    private static BlockPos part(BlockPos root, Direction facing, int index) {
        return root.relative(facing.getClockWise(), index & 1).relative(facing.getOpposite(), (index >> 1) & 1)
            .above(index >> 2);
    }

    private static BlockPos root(BlockPos pos, BlockState state) {
        int index = state.getValue(PART);
        Direction facing = state.getValue(FACING);
        return pos.relative(facing.getCounterClockWise(), index & 1).relative(facing, (index >> 1) & 1)
            .below(index >> 2);
    }

    static Vec3 transmissionTerminal(BlockPos root, Direction facing) {
        return Vec3.atBottomCenterOf(root).add(Vec3.atLowerCornerOf(facing.getOpposite().getUnitVec3i()).scale(1.125))
            .add(0, 2.94, 0);
    }

    static Map<BlockPos, BlockState> parts(BlockPos root, BlockState state) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) result.put(part(root, state.getValue(FACING), i), state.setValue(PART, i));
        return result;
    }

    private static void invalidateInput(Level level, BlockPos root, BlockState state) {
        if (!(level instanceof ServerLevel server)) return;
        Direction facing = state.getValue(FACING);
        for (int y = 0; y < 3; y++) {
            int rear = 4 * y + 2;
            server.invalidateCapabilities(part(root, facing, rear));
            server.invalidateCapabilities(part(root, facing, rear + 1));
        }
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == 0 ? new Bank(pos, state) : null;
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        BlockPos root = root(pos, state);
        PowerStructure.remove(level, parts(root, state));
        invalidateInput(level, root, state);
    }

    static final class Kit extends BlockItem {
        Kit(TransformerBank block, Properties properties) { super(block, properties); }

        Map<BlockPos, BlockState> connectedPreview(BlockPlaceContext context) {
            if (!context.canPlace() || context.getPlayer() == null) return Map.of();
            BlockState state = getPlacementState(context);
            if (state == null) return Map.of();
            var root = context.getClickedPos();
            var level = context.getLevel();
            var layout = parts(root, state);
            return PowerStructure.fits(level, context.getPlayer(), layout) && CatenaryPower.canTap(level, root, state.getValue(FACING))
                ? layout : Map.of();
        }

        @Override
        protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
            if (context.getLevel().isClientSide()) return true;
            if (!(context.getPlayer() instanceof ServerPlayer player)) return false;
            BlockPos root = context.getClickedPos();
            var layout = parts(root, state);
            if (!PowerStructure.place(player.level(), player, layout, root.relative(context.getClickedFace().getOpposite()))) return false;
            invalidateInput(player.level(), root, state);
            return true;
        }
    }

    static final class Bank extends BlockEntity {
        final RailPowerBuffer energy = new RailPowerBuffer(this, CAPACITY, true, this::complete);
        private long available;
        private long capacity = CAPACITY;
        private Vec3 tap;
        private BlockPos transmission;

        Bank(BlockPos pos, BlockState state) { super(Trainmod.TRANSFORMER_BANK_ENTITY.get(), pos, state); }

        boolean complete() {
            if (level == null) return false;
            BlockState owner = getBlockState();
            Direction facing = owner.getValue(FACING);
            for (int i = 0; i < 12; i++) {
                if (!PowerStructure.matches(level, part(worldPosition, facing, i), owner.setValue(PART, i))) return false;
            }
            return true;
        }

        int draw(int amount, TransactionContext transaction) {
            return energy.draw(amount, transaction);
        }

        @Override
        public void onLoad() {
            super.onLoad();
            CatenaryPower.trackBank(level, worldPosition, true);
            invalidateInput(level, worldPosition, getBlockState());
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            CatenaryPower.trackBank(level, worldPosition, false);
            invalidateInput(level, worldPosition, getBlockState());
        }

        void updateMeter(long available, long capacity, Vec3 tap, BlockPos transmission) {
            if (this.available == available && this.capacity == capacity && java.util.Objects.equals(this.tap, tap)
                && java.util.Objects.equals(this.transmission, transmission)) return;
            this.available = available;
            this.capacity = capacity;
            this.tap = tap;
            this.transmission = transmission;
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }

        float meterFraction() { return (float) ((double) available / capacity); }
        boolean live() { return available >= CatenaryPower.DRAW; }
        Vec3 tap() { return tap; }
        BlockPos transmission() { return transmission; }

        @Override
        protected void loadAdditional(ValueInput input) {
            super.loadAdditional(input);
            energy.set(Math.clamp(input.getIntOr("Energy", 0), 0, CAPACITY));
            available = input.getLongOr("Available", 0);
            capacity = Math.max(CAPACITY, input.getLongOr("Capacity", CAPACITY));
            tap = input.read("Tap", Vec3.CODEC).orElse(null);
            transmission = input.read("Transmission", BlockPos.CODEC).orElse(null);
        }

        @Override
        protected void saveAdditional(ValueOutput output) {
            super.saveAdditional(output);
            output.putInt("Energy", energy.getAmountAsInt());
        }

        @Override
        public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.putLong("Available", available);
            tag.putLong("Capacity", capacity);
            if (tap != null) tag.store("Tap", Vec3.CODEC, tap);
            if (transmission != null) tag.store("Transmission", BlockPos.CODEC, transmission);
            return tag;
        }

        @Override
        public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    }
}
