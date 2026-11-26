package org.ja4.trainmod;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.NormalCraftingRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.level.Level;

final class TrackRecyclingRecipe extends NormalCraftingRecipe {
    static final RecipeSerializer<TrackRecyclingRecipe> SERIALIZER = new RecipeSerializer<>(
        RecordCodecBuilder.mapCodec(instance -> instance.group(
            CommonInfo.MAP_CODEC.forGetter(recipe -> recipe.commonInfo),
            CraftingBookInfo.MAP_CODEC.forGetter(recipe -> recipe.bookInfo)
        ).apply(instance, TrackRecyclingRecipe::new)),
        StreamCodec.composite(CommonInfo.STREAM_CODEC, recipe -> recipe.commonInfo,
            CraftingBookInfo.STREAM_CODEC, recipe -> recipe.bookInfo, TrackRecyclingRecipe::new));

    private final ShapelessRecipe recipe;

    private TrackRecyclingRecipe(CommonInfo common, CraftingBookInfo book) {
        super(common, book);
        recipe = new ShapelessRecipe(common, book, new ItemStackTemplate(Trainmod.RAIL.get(), 2),
            Collections.nCopies(TrackMaterials.STRAIGHT_YIELD, Ingredient.of(Trainmod.TRACK_SCRAP.get())));
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
    public RecipeSerializer<TrackRecyclingRecipe> getSerializer() { return SERIALIZER; }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        var remaining = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        for (int i = 0; i < input.size(); i++) {
            if (input.getItem(i).is(Trainmod.TRACK_SCRAP.get())) {
                remaining.set(i, new ItemStack(Trainmod.TIE_BUNDLE.get()));
                break;
            }
        }
        return remaining;
    }
}
