package org.ja4.trainmod;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class FuelGeneratorMenu extends AbstractContainerMenu {
    private final Container machine;
    private final ContainerData data;

    FuelGeneratorMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(2), new SimpleContainerData(2));
    }

    FuelGeneratorMenu(int id, Inventory inventory, Container machine, ContainerData data) {
        super(Trainmod.FUEL_GENERATOR_MENU.get(), id);
        this.machine = machine;
        this.data = data;
        addSlot(new Slot(machine, FuelGenerator.FUEL, 48, 33) {
            @Override
            public boolean mayPlace(ItemStack stack) { return stack.has(DataComponents.COOKING_FUEL); }
        });
        addSlot(new Slot(machine, FuelGenerator.RETURN, 112, 33) {
            @Override
            public boolean mayPlace(ItemStack stack) { return false; }
        });
        addStandardInventorySlots(inventory, 8, 85);
        addDataSlots(data);
    }

    int energy() { return data.get(0); }
    int burnPercent() { return data.get(1); }

    @Override
    public boolean stillValid(Player player) { return machine.stillValid(player); }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack moved = stack.copy();
        if (index < 2 ? !moveItemStackTo(stack, 2, slots.size(), true) : !moveItemStackTo(stack, 0, 1, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return moved;
    }
}
