package org.ja4.trainmod;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

final class TrackBuilder extends Item {
    TrackBuilder(Properties properties) { super(properties); }

    @Override
    public void appendHoverText(net.minecraft.world.item.ItemStack stack, TooltipContext context,
                                net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<Component> tooltip,
                                net.minecraft.world.item.TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.trainmod.track_builder").withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) {
            ItemStack stack = player.getItemInHand(hand);
            TrackGeometry.Kind[] kinds = TrackGeometry.Kind.values();
            CompoundTag data = data(stack);
            int selected = Math.floorMod(data.getIntOr("piece", 0), kinds.length);
            boolean left = data.getBooleanOr("left", true);
            if (player.isShiftKeyDown()) {
                left = !left;
            } else {
                selected = (selected + 1) % kinds.length;
            }
            data.putInt("piece", selected);
            data.putBoolean("left", left);
            save(stack, data);
            player.sendOverlayMessage(Component.translatable("message.trainmod.selected", kinds[selected].title,
                Component.translatable(left ? "message.trainmod.left" : "message.trainmod.right")));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS;
        }
        CompoundTag data = data(context.getItemInHand());
        TrackGeometry.Kind[] kinds = TrackGeometry.Kind.values();
        TrackGeometry.Kind kind = kinds[Math.floorMod(data.getIntOr("piece", 0), kinds.length)];
        var placement = TrackItem.findPlacement(level, context, player, kind, data.getBooleanOr("left", true));
        if (placement == null) {
            player.sendOverlayMessage(Component.translatable("message.trainmod.track_site"));
            return InteractionResult.FAIL;
        }
        int count = placement.shape().cells().size();
        if (!player.getAbilities().instabuild && player.getInventory().countItem(Items.RAIL) < count) {
            player.sendOverlayMessage(Component.translatable("message.trainmod.need_rails", count));
            return InteractionResult.FAIL;
        }
        if (!TrackItem.place(level, player, kind, placement)) {
            return InteractionResult.FAIL;
        }
        if (!player.getAbilities().instabuild) {
            player.getInventory().clearOrCountMatchingItems(item -> item.is(Items.RAIL), false, count, player.inventoryMenu.getCraftSlots());
        }
        return InteractionResult.SUCCESS;
    }

    static CompoundTag data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    static void save(ItemStack stack, CompoundTag data) {
        CustomData.set(DataComponents.CUSTOM_DATA, stack, data);
    }
}
