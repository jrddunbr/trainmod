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
    static final int TOGGLE_WORK = 1;
    private final @Nullable TrainVehicle regulator;
    private final DataSlot mode;
    private final DataSlot reserve;
    private final DataSlot working;

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
        reserve = addDataSlot(regulator == null ? DataSlot.standalone() : new DataSlot() {
            @Override
            public int get() { return regulator.ballastRemainder(); }

            @Override
            public void set(int value) {}
        });
        working = addDataSlot(regulator == null ? DataSlot.standalone() : new DataSlot() {
            @Override
            public int get() { return regulator.ballastWorking() ? 1 : 0; }

            @Override
            public void set(int value) {}
        });
    }

    TrainVehicle.BallastMode mode() { return TrainVehicle.BallastMode.byId(mode.get()); }

    int reserve() { return reserve.get(); }

    boolean working() { return working.get() != 0; }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (regulator == null || !stillValid(player)) return false;
        if (button == CYCLE_MODE) regulator.cycleBallastMode();
        else if (button == TOGGLE_WORK) regulator.toggleBallastWork(player);
        else return false;
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
