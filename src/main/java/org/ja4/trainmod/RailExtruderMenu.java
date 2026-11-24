package org.ja4.trainmod;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class RailExtruderMenu extends AbstractContainerMenu {
    static final int PROGRESS = 0;
    static final int ENERGY = 1;
    static final int POWER_REQUIRED = 2;
    static final int DATA_COUNT = 3;
    private static final int MACHINE_SLOTS = 2;

    private final Container machine;
    private final ContainerData data;

    RailExtruderMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(MACHINE_SLOTS), new SimpleContainerData(DATA_COUNT));
    }

    RailExtruderMenu(int id, Inventory inventory, Container machine, ContainerData data) {
        super(Trainmod.RAIL_EXTRUDER_MENU.get(), id);
        this.machine = machine;
        this.data = data;
        addSlot(new Slot(machine, RailExtruder.INPUT, 53, 34) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(Items.IRON_INGOT);
            }
        });
        addSlot(new Slot(machine, RailExtruder.OUTPUT, 107, 34) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });
        addStandardInventorySlots(inventory, 8, 85);
        addDataSlots(data);
    }

    int progress() { return data.get(PROGRESS); }
    int energy() { return data.get(ENERGY); }
    boolean requiresPower() { return data.get(POWER_REQUIRED) != 0; }

    @Override
    public boolean stillValid(Player player) {
        return machine.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack moved = stack.copy();
        boolean transferred = index < MACHINE_SLOTS
            ? moveItemStackTo(stack, MACHINE_SLOTS, slots.size(), true)
            : moveItemStackTo(stack, RailExtruder.INPUT, RailExtruder.INPUT + 1, false);
        if (!transferred) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return moved;
    }
}
