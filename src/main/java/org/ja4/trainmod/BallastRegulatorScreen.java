package org.ja4.trainmod;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;

final class BallastRegulatorScreen extends ContainerScreen {
    private final BallastRegulatorMenu regulator;
    private Button mode;

    BallastRegulatorScreen(ChestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        regulator = (BallastRegulatorMenu) menu;
    }

    @Override
    protected void init() {
        super.init();
        mode = addRenderableWidget(Button.builder(modeLabel(), button ->
            minecraft.gameMode.handleInventoryButtonClick(regulator.containerId, BallastRegulatorMenu.CYCLE_MODE))
            .bounds(leftPos + 76, topPos + 70, 92, 14).build());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        mode.setMessage(modeLabel());
    }

    private Component modeLabel() {
        return Component.translatable(switch (regulator.mode()) {
            case NARROW -> "menu.trainmod.ballast_narrow";
            case WIDE -> "menu.trainmod.ballast_wide";
            case TRACK_ONLY -> "menu.trainmod.ballast_track_only";
        });
    }
}
