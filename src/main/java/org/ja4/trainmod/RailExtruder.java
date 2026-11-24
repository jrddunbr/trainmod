package org.ja4.trainmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import org.jspecify.annotations.Nullable;

final class RailExtruder extends BaseEntityBlock {
    static final int INPUT = 0;
    static final int OUTPUT = 1;
    static final int RAILS_PER_INGOT = 16;
    static final int CYCLE_TICKS = 40;
    static final int ENERGY_PER_TICK = 10;
    static final int ENERGY_CAPACITY = 4000;

    RailExtruder(Properties properties) {
        super(properties);
    }

    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, Trainmod.RAIL_EXTRUDER_ENTITY.get(),
            (machine, side) -> machine.energy);
        event.registerBlockEntity(Capabilities.Item.BLOCK, Trainmod.RAIL_EXTRUDER_ENTITY.get(), WorldlyContainerWrapper::new);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Machine(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, Trainmod.RAIL_EXTRUDER_ENTITY.get(),
            (world, pos, block, machine) -> machine.process(ServerConfig.railExtruderRequiresPower()));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof Machine machine) {
            player.openMenu(machine);
        }
        return InteractionResult.SUCCESS;
    }

    static final class Machine extends BaseContainerBlockEntity implements WorldlyContainer {
        private static final int[] INPUT_SLOTS = {INPUT};
        private static final int[] OUTPUT_SLOTS = {OUTPUT};

        private NonNullList<ItemStack> items = NonNullList.withSize(2, ItemStack.EMPTY);
        private final SimpleEnergyHandler energy = new SimpleEnergyHandler(ENERGY_CAPACITY, 200, 0) {
            @Override
            protected void onEnergyChanged(int previousAmount) {
                setChanged();
            }
        };
        private int progress;

        Machine(BlockPos pos, BlockState state) {
            super(Trainmod.RAIL_EXTRUDER_ENTITY.get(), pos, state);
        }

        void process(boolean requiresPower) {
            ItemStack input = getItem(INPUT);
            if (!input.is(Items.IRON_INGOT)) {
                if (progress != 0) {
                    progress = 0;
                    setChanged();
                }
                return;
            }
            ItemStack output = getItem(OUTPUT);
            var rails = new ItemStack(Trainmod.RAIL.get(), RAILS_PER_INGOT);
            if (!output.isEmpty() && (!ItemStack.isSameItemSameComponents(output, rails)
                || output.getCount() + RAILS_PER_INGOT > output.getMaxStackSize())) {
                return;
            }
            int available = (int) energy.getAmountAsLong();
            if (requiresPower && available < ENERGY_PER_TICK) return;

            progress++;
            if (progress == CYCLE_TICKS) {
                progress = 0;
                input.shrink(1);
                if (output.isEmpty()) items.set(OUTPUT, rails);
                else output.grow(RAILS_PER_INGOT);
            }
            if (requiresPower) energy.set(available - ENERGY_PER_TICK);
            else setChanged();
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            super.loadAdditional(input);
            items = NonNullList.withSize(2, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(input, items);
            energy.set(Math.clamp(input.getIntOr("Energy", 0), 0, ENERGY_CAPACITY));
            progress = Math.clamp(input.getIntOr("Progress", 0), 0, CYCLE_TICKS - 1);
        }

        @Override
        protected void saveAdditional(ValueOutput output) {
            super.saveAdditional(output);
            ContainerHelper.saveAllItems(output, items);
            output.putInt("Energy", (int) energy.getAmountAsLong());
            output.putInt("Progress", progress);
        }

        @Override
        public int getContainerSize() { return items.size(); }

        @Override
        protected NonNullList<ItemStack> getItems() { return items; }

        @Override
        protected void setItems(NonNullList<ItemStack> stacks) { items = stacks; }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return slot == INPUT && stack.is(Items.IRON_INGOT);
        }

        @Override
        public int[] getSlotsForFace(Direction side) {
            return side == Direction.DOWN ? OUTPUT_SLOTS : INPUT_SLOTS;
        }

        @Override
        public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
            return canPlaceItem(slot, stack);
        }

        @Override
        public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
            return slot == OUTPUT;
        }

        @Override
        protected Component getDefaultName() {
            return Trainmod.RAIL_EXTRUDER.get().getName();
        }

        @Override
        protected RailExtruderMenu createMenu(int id, Inventory inventory) {
            ContainerData data = new SimpleContainerData(RailExtruderMenu.DATA_COUNT) {
                @Override
                public int get(int index) {
                    return switch (index) {
                        case RailExtruderMenu.PROGRESS -> progress;
                        case RailExtruderMenu.ENERGY -> (int) energy.getAmountAsLong();
                        case RailExtruderMenu.POWER_REQUIRED -> ServerConfig.railExtruderRequiresPower() ? 1 : 0;
                        default -> throw new IndexOutOfBoundsException(index);
                    };
                }
            };
            return new RailExtruderMenu(id, inventory, this, data);
        }
    }
}
