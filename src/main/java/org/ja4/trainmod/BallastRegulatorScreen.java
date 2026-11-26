package org.ja4.trainmod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;

final class BallastRegulatorScreen extends ContainerScreen {
    private final BallastRegulatorMenu regulator;
    private Button mode;
    private Button work;

    BallastRegulatorScreen(ChestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        regulator = (BallastRegulatorMenu) menu;
    }

    @Override
    protected void init() {
        super.init();
        mode = addRenderableWidget(Button.builder(modeLabel(), button ->
            minecraft.gameMode.handleInventoryButtonClick(regulator.containerId, BallastRegulatorMenu.CYCLE_MODE))
            .bounds(leftPos + 104, topPos + 70, 64, 14).build());
        work = addRenderableWidget(Button.builder(workLabel(), button ->
            minecraft.gameMode.handleInventoryButtonClick(regulator.containerId, BallastRegulatorMenu.TOGGLE_WORK))
            .bounds(leftPos + 58, topPos + 70, 44, 14).build());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        mode.setMessage(modeLabel());
        work.setMessage(workLabel());
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY);
        if (regulator.reserve() > 0) {
            var reserve = Component.translatable("menu.trainmod.ballast_reserve", regulator.reserve(), TrackWork.BALLAST_PORTIONS);
            graphics.text(font, reserve, imageWidth - font.width(reserve) - 8, 6, 0xff404040, false);
        }
    }

    private Component modeLabel() {
        return Component.translatable(switch (regulator.mode()) {
            case NARROW -> "menu.trainmod.ballast_narrow";
            case WIDE -> "menu.trainmod.ballast_wide";
            case TRACK_ONLY -> "menu.trainmod.ballast_track_only";
        });
    }

    private Component workLabel() {
        return Component.translatable(regulator.working() ? "menu.trainmod.ballast_active" : "menu.trainmod.ballast_paused");
    }
}
