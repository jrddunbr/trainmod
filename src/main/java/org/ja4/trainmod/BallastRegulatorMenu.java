package org.ja4.trainmod;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

final class BallastRegulatorMenu extends ChestMenu {
    static final int CYCLE_MODE = 0;
    private final @Nullable TrainVehicle regulator;
    private final DataSlot mode;

    BallastRegulatorMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(27), null);
    }

    BallastRegulatorMenu(int id, Inventory inventory, SimpleContainer cargo, @Nullable TrainVehicle regulator) {
        super(Trainmod.BALLAST_REGULATOR_MENU.get(), id, inventory, cargo, 3);
        this.regulator = regulator;
        mode = addDataSlot(regulator == null ? DataSlot.standalone() : new DataSlot() {
            @Override
            public int get() { return regulator.ballastMode().ordinal(); }

            @Override
            public void set(int value) {}
        });
    }

    TrainVehicle.BallastMode mode() { return TrainVehicle.BallastMode.byId(mode.get()); }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (button != CYCLE_MODE || regulator == null || !stillValid(player)) return false;
        regulator.cycleBallastMode();
        return true;
    }

    @Override
    protected Slot addSlot(Slot slot) {
        if (!(slot.container instanceof SimpleContainer)) return super.addSlot(slot);
        return super.addSlot(new Slot(slot.container, slot.getContainerSlot(), slot.x, slot.y) {
            @Override
            public boolean mayPlace(ItemStack stack) { return TrainVehicle.ballastSupply(stack); }
        });
    }
}
