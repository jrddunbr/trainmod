package org.ja4.trainmod;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;

final class BallastRegulatorScreen extends ContainerScreen {
    private final BallastRegulatorMenu regulator;
    private Button width;

    BallastRegulatorScreen(ChestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        regulator = (BallastRegulatorMenu) menu;
    }

    @Override
    protected void init() {
        super.init();
        width = addRenderableWidget(Button.builder(widthLabel(), button ->
            minecraft.gameMode.handleInventoryButtonClick(regulator.containerId, BallastRegulatorMenu.TOGGLE_WIDTH))
            .bounds(leftPos + 92, topPos + 70, 76, 14).build());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        width.setMessage(widthLabel());
    }

    private Component widthLabel() {
        return Component.translatable(regulator.wide() ? "menu.trainmod.ballast_wide" : "menu.trainmod.ballast_narrow");
    }
}
