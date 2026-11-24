package org.ja4.trainmod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

final class RailExtruderScreen extends AbstractContainerScreen<RailExtruderMenu> {
    private static final Identifier PROGRESS = Identifier.withDefaultNamespace("container/furnace/burn_progress");

    RailExtruderScreen(RailExtruderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 168);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        int x = leftPos, y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xffc6c6c6);
        graphics.outline(x, y, imageWidth, imageHeight, 0xff373737);
        graphics.fill(x + 1, y + 1, x + imageWidth - 1, y + 2, 0xffffffff);
        graphics.fill(x + 1, y + 1, x + 2, y + imageHeight - 1, 0xffffffff);
        for (var slot : menu.slots) {
            int sx = x + slot.x - 1, sy = y + slot.y - 1;
            graphics.fill(sx, sy, sx + 18, sy + 18, 0xff8b8b8b);
            graphics.fill(sx, sy, sx + 18, sy + 1, 0xff373737);
            graphics.fill(sx, sy, sx + 1, sy + 18, 0xff373737);
            graphics.fill(sx + 1, sy + 17, sx + 18, sy + 18, 0xffffffff);
            graphics.fill(sx + 17, sy + 1, sx + 18, sy + 18, 0xffffffff);
        }
        int progress = menu.progress() * 24 / RailExtruder.CYCLE_TICKS;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PROGRESS, x + 76, y + 34, 24, 16, 0.35f);
        if (progress > 0) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PROGRESS, 24, 16, 0, 0, x + 76, y + 34, progress, 16);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY);
        Component recipe = Component.translatable("menu.trainmod.rail_extruder.recipe", RailExtruder.RAILS_PER_INGOT);
        Component power = menu.requiresPower()
            ? Component.translatable("menu.trainmod.rail_extruder.energy", menu.energy(), RailExtruder.ENERGY_CAPACITY)
            : Component.translatable("menu.trainmod.rail_extruder.unpowered");
        graphics.text(font, recipe, (imageWidth - font.width(recipe)) / 2, 20, 0xff404040, false);
        graphics.text(font, power, (imageWidth - font.width(power)) / 2, 56, 0xff404040, false);
    }
}
