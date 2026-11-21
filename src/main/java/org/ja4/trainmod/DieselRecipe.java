package org.ja4.trainmod;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.NormalCraftingRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.crafting.ShapelessRecipe;

final class DieselRecipe extends NormalCraftingRecipe {
    static final RecipeSerializer<DieselRecipe> SERIALIZER = new RecipeSerializer<>(
        RecordCodecBuilder.mapCodec(instance -> instance.group(
            CommonInfo.MAP_CODEC.forGetter(recipe -> recipe.commonInfo),
            CraftingBookInfo.MAP_CODEC.forGetter(recipe -> recipe.bookInfo)
        ).apply(instance, DieselRecipe::new)),
        StreamCodec.composite(CommonInfo.STREAM_CODEC, recipe -> recipe.commonInfo,
            CraftingBookInfo.STREAM_CODEC, recipe -> recipe.bookInfo, DieselRecipe::new));

    private final ShapelessRecipe recipe;

    private DieselRecipe(CommonInfo common, CraftingBookInfo book) {
        super(common, book);
        recipe = new ShapelessRecipe(common, book, new ItemStackTemplate(Trainmod.DIESEL_BUCKET.get()),
            List.of(Ingredient.of(Items.WATER_BUCKET), Ingredient.of(Items.COAL)));
    }

    @Override
    public boolean matches(CraftingInput input, Level level) { return recipe.matches(input, level); }

    @Override
    public ItemStack assemble(CraftingInput input) { return recipe.assemble(input); }

    @Override
    protected PlacementInfo createPlacementInfo() { return recipe.placementInfo(); }

    @Override
    public List<RecipeDisplay> display() { return recipe.display(); }

    @Override
    public RecipeSerializer<DieselRecipe> getSerializer() { return SERIALIZER; }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        // The water bucket itself becomes the diesel bucket.
        return NonNullList.withSize(input.size(), ItemStack.EMPTY);
    }
}
