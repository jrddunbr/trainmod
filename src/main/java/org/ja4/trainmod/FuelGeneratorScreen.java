package org.ja4.trainmod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

final class FuelGeneratorScreen extends AbstractContainerScreen<FuelGeneratorMenu> {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/furnace.png");
    private static final Identifier LIT = Identifier.withDefaultNamespace("container/furnace/lit_progress");

    FuelGeneratorScreen(FuelGeneratorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 166);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        int x = leftPos, y = topPos;
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, x, y, 0, 0, imageWidth, imageHeight, 256, 256);
        graphics.fill(x + 27, y + 15, x + 151, y + 69, 0xffc6c6c6);
        for (int slot : new int[]{47, 111}) {
            graphics.fill(x + slot, y + 32, x + slot + 18, y + 50, 0xff8b8b8b);
            graphics.outline(x + slot, y + 32, 18, 18, 0xff555555);
        }
        int height = (menu.burnPercent() * 14 + 99) / 100;
        if (height > 0) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, LIT, 14, 14, 0, 14 - height,
                x + 80, y + 34 + 14 - height, 14, height);
        }
        graphics.fill(x + 42, y + 64, x + 134, y + 69, 0xff555555);
        graphics.fill(x + 42, y + 64, x + 42 + 92 * menu.energy() / FuelGenerator.CAPACITY, y + 69, 0xff557f59);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY);
        graphics.text(font, Component.translatable("menu.trainmod.fuel_generator.fuel"), 41, 21, 0xff404040, false);
        graphics.text(font, Component.translatable("menu.trainmod.fuel_generator.return"), 105, 21, 0xff404040, false);
        Component energy = Component.translatable("menu.trainmod.fuel_generator.energy", menu.energy(), FuelGenerator.CAPACITY);
        graphics.text(font, energy, (imageWidth - font.width(energy)) / 2, 53, 0xff404040, false);
    }
}
