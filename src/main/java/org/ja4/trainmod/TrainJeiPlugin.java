package org.ja4.trainmod;

import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

@JeiPlugin
public final class TrainJeiPlugin implements IModPlugin {
    private static final Identifier EXTRUDER_ID = Identifier.fromNamespaceAndPath(Trainmod.ID, "rail_extrusion");
    static final IRecipeType<Extrusion> EXTRUSION = IRecipeType.create(EXTRUDER_ID, Extrusion.class);

    record Extrusion(ItemStack input, ItemStack output) {}

    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath(Trainmod.ID, "jei");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new ExtruderCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addRecipes(EXTRUSION, List.of(new Extrusion(new ItemStack(Items.IRON_INGOT),
            new ItemStack(Trainmod.RAIL.get(), RailExtruder.RAILS_PER_INGOT))));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addCraftingStation(EXTRUSION, Trainmod.RAIL_EXTRUDER.get());
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addRecipeClickArea(RailExtruderScreen.class, RailExtruderScreen.PROGRESS_X, RailExtruderScreen.PROGRESS_Y,
            RailExtruderScreen.PROGRESS_WIDTH, RailExtruderScreen.PROGRESS_HEIGHT, EXTRUSION);
    }

    private static final class ExtruderCategory implements IRecipeCategory<Extrusion> {
        private final IDrawable icon;
        private final IDrawable slot;
        private final IDrawable arrow;

        private ExtruderCategory(IGuiHelper gui) {
            icon = gui.createDrawableItemLike(Trainmod.RAIL_EXTRUDER.get());
            slot = gui.getSlotDrawable();
            arrow = gui.getRecipeArrow();
        }

        @Override
        public IRecipeType<Extrusion> getRecipeType() { return EXTRUSION; }

        @Override
        public Component getTitle() { return Trainmod.RAIL_EXTRUDER.get().getName(); }

        @Override
        public int getWidth() { return 84; }

        @Override
        public int getHeight() { return 24; }

        @Override
        public IDrawable getIcon() { return icon; }

        @Override
        public Identifier getIdentifier(Extrusion recipe) { return EXTRUDER_ID; }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, Extrusion recipe, IFocusGroup focuses) {
            builder.addInputSlot(7, 4).setBackground(slot, -1, -1).add(recipe.input());
            builder.addOutputSlot(61, 4).setBackground(slot, -1, -1).add(recipe.output());
        }

        @Override
        public void draw(Extrusion recipe, IRecipeSlotsView slots, GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
            arrow.draw(graphics, 30, 4);
        }
    }
}
