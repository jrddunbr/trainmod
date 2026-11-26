package org.ja4.trainmod;

import com.mojang.logging.LogUtils;
import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

@JeiPlugin
public final class ClientJeiSmokeTest implements IModPlugin {
    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath(Trainmod.ID, "jei_smoke_test");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        String mode = System.getProperty("trainmod.clientAdditionsSmoke");
        boolean checkTrack = "jeiAndBentTrack".equals(mode);
        if (!checkTrack && !"jeiExtruder".equals(mode)) return;
        var client = Minecraft.getInstance();
        // JEI finishes publishing its runtime after this callback returns.
        client.schedule(() -> {
            try {
                var manager = runtime.getRecipeManager();
                var type = TrainJeiPlugin.EXTRUSION;
                var category = manager.getRecipeCategory(type);
                var recipe = manager.createRecipeLookup(type).get().findFirst().orElseThrow();
                var focuses = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
                var slots = manager.createRecipeLayoutDrawable(category, recipe, focuses).orElseThrow().getRecipeSlotsView();
                var input = slots.getSlotViews(RecipeIngredientRole.INPUT).getFirst().getItemStacks().findFirst().orElseThrow();
                var output = slots.getSlotViews(RecipeIngredientRole.OUTPUT).getFirst().getItemStacks().findFirst().orElseThrow();
                var station = manager.createCraftingStationLookup(type).getItemStack().findFirst().orElse(ItemStack.EMPTY);
                if (!input.is(Items.IRON_INGOT) || input.getCount() != 1
                    || !output.is(Trainmod.RAIL.get()) || output.getCount() != 16
                    || !station.is(Trainmod.RAIL_EXTRUDER.get().asItem())) {
                    throw new AssertionError("The JEI layout shows the wrong input, output or machine");
                }
                runtime.getRecipesGui().showTypes(List.of(type));
                client.schedule(() -> {
                    if (client.gui.screen() == null || !client.gui.screen().getClass().getName().startsWith("mezz.jei.")) {
                        LogUtils.getLogger().error("TRAINMOD JEI EXTRUDER CLIENT FAILED to open its recipe screen");
                        client.stop();
                        return;
                    }
                    LogUtils.getLogger().info("TRAINMOD JEI EXTRUDER CLIENT PASSED");
                    if (checkTrack) {
                        client.gui.setScreen(null);
                        ClientAdditionsSmokeTest.jeiVerified = true;
                    } else client.stop();
                });
            } catch (RuntimeException | AssertionError error) {
                LogUtils.getLogger().error("TRAINMOD JEI EXTRUDER CLIENT FAILED", error);
                client.stop();
            }
        });
    }
}
