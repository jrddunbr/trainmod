package org.ja4.trainmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import net.neoforged.neoforge.transfer.transaction.Transaction;

final class FuelGenerator extends BaseEntityBlock {
    static final int CAPACITY = 16_000;
    static final int GENERATION = 40;
    static final int FUEL = 0, RETURN = 1;

    FuelGenerator(Properties properties) { super(properties); }

    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, Trainmod.FUEL_GENERATOR_ENTITY.get(),
            (generator, side) -> generator.energy);
        event.registerBlockEntity(Capabilities.Item.BLOCK, Trainmod.FUEL_GENERATOR_ENTITY.get(), WorldlyContainerWrapper::new);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Machine(pos, state); }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level instanceof ServerLevel server ? createTickerHelper(type, Trainmod.FUEL_GENERATOR_ENTITY.get(),
            (world, pos, block, generator) -> generator.tick(server)) : null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof Machine machine) player.openMenu(machine);
        return InteractionResult.SUCCESS;
    }

    static final class Machine extends BaseContainerBlockEntity implements WorldlyContainer {
        private static final int[] FUEL_SLOTS = {FUEL};
        private static final int[] RETURN_SLOTS = {RETURN};

        private NonNullList<ItemStack> items = NonNullList.withSize(2, ItemStack.EMPTY);
        final RailPowerBuffer energy = new RailPowerBuffer(this, CAPACITY, false, () -> true);
        private int burn;
        private int duration;

        Machine(BlockPos pos, BlockState state) { super(Trainmod.FUEL_GENERATOR_ENTITY.get(), pos, state); }

        void tick(ServerLevel level) {
            produce(level);
            for (Direction side : Direction.values()) {
                int limit = Math.min(RailPowerBuffer.TRANSFER_LIMIT, energy.getAmountAsInt());
                if (limit == 0) break;
                BlockPos neighbor = getBlockPos().relative(side);
                if (!level.isLoaded(neighbor)) continue;
                var target = level.getCapability(Capabilities.Energy.BLOCK, neighbor, side.getOpposite());
                if (target == null) continue;
                try (var transaction = Transaction.openRoot()) {
                    int available;
                    try (var simulation = Transaction.open(transaction)) {
                        available = energy.extract(limit, simulation);
                    }
                    if (available == 0) break;
                    int accepted = target.insert(available, transaction);
                    if (accepted > 0 && energy.extract(accepted, transaction) == accepted) transaction.commit();
                }
            }
        }

        private void produce(ServerLevel level) {
            int available = energy.getAmountAsInt();
            if (available == CAPACITY || burn == 0 && !ignite(level)) return;
            burn--;
            energy.set(Math.min(CAPACITY, available + GENERATION));
        }

        private boolean ignite(ServerLevel level) {
            ItemStack fuel = getItem(FUEL);
            int ticks = ResolvableInt.getFromItem(fuel, DataComponents.COOKING_FUEL, CookingFuel::burnTime,
                getLootContext(level, fuel), 0);
            if (ticks <= 0) return false;
            var template = fuel.getCraftingRemainder();
            if (template != null) {
                ItemStack remainder = template.create();
                ItemStack output = getItem(RETURN);
                if (output.isEmpty()) items.set(RETURN, remainder);
                else if (ItemStack.isSameItemSameComponents(output, remainder)
                    && output.getCount() + remainder.getCount() <= output.getMaxStackSize()) output.grow(remainder.getCount());
                else return false;
            }
            fuel.shrink(1);
            duration = burn = ticks;
            return true;
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            super.loadAdditional(input);
            items = NonNullList.withSize(2, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(input, items);
            energy.set(Math.clamp(input.getIntOr("Energy", 0), 0, CAPACITY));
            duration = Math.max(input.getIntOr("Duration", 0), 0);
            burn = Math.clamp(input.getIntOr("Burn", 0), 0, duration);
        }

        @Override
        protected void saveAdditional(ValueOutput output) {
            super.saveAdditional(output);
            ContainerHelper.saveAllItems(output, items);
            output.putInt("Energy", energy.getAmountAsInt());
            output.putInt("Duration", duration);
            output.putInt("Burn", burn);
        }

        int burnRemaining() { return burn; }

        @Override
        public int getContainerSize() { return items.size(); }

        @Override
        protected NonNullList<ItemStack> getItems() { return items; }

        @Override
        protected void setItems(NonNullList<ItemStack> stacks) { items = stacks; }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) { return slot == FUEL && stack.has(DataComponents.COOKING_FUEL); }

        @Override
        public int[] getSlotsForFace(Direction side) { return side == Direction.DOWN ? RETURN_SLOTS : FUEL_SLOTS; }

        @Override
        public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) { return canPlaceItem(slot, stack); }

        @Override
        public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) { return slot == RETURN; }

        @Override
        protected Component getDefaultName() { return Trainmod.FUEL_GENERATOR.get().getName(); }

        @Override
        protected FuelGeneratorMenu createMenu(int id, Inventory inventory) {
            return new FuelGeneratorMenu(id, inventory, this, new SimpleContainerData(2) {
                @Override
                public int get(int index) { return index == 0 ? energy.getAmountAsInt() : duration == 0 ? 0 : (int) (100L * burn / duration); }
            });
        }
    }
}
