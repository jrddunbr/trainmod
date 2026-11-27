package org.ja4.trainmod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

final class VehicleItem extends Item {
    private final TrainVehicle.Kind kind;

    VehicleItem(Properties properties, TrainVehicle.Kind kind) {
        super(properties);
        this.kind = kind;
    }

    @Override
    public void appendHoverText(net.minecraft.world.item.ItemStack stack, TooltipContext context,
                                net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<Component> tooltip,
                                net.minecraft.world.item.TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.trainmod." + kind.id()).withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level) || context.getPlayer() == null) {
            return InteractionResult.SUCCESS;
        }
        BlockPos cell = context.getClickedPos();
        if (!RailNetwork.isRail(level.getBlockState(cell))) {
            cell = cell.above();
        }
        Vec3 forward = context.getPlayer().getViewVector(1).multiply(1, 0, 1);
        RailNetwork.Position position = RailNetwork.locate(level, cell, TrackGeometry.origin(cell), forward);
        if (TrackBlock.broken(level.getBlockState(cell)) || position == null
            || position.point().distanceToSqr(TrackGeometry.origin(cell)) > 0.8) {
            context.getPlayer().sendOverlayMessage(Component.translatable("message.trainmod.place_vehicle"));
            return InteractionResult.FAIL;
        }
        TrainVehicle vehicle = new TrainVehicle(Trainmod.VEHICLES.get(kind).get(), level, kind);
        if (kind == TrainVehicle.Kind.TUNNEL_BORE) {
            position = BoreDrive.placement(level, position);
            if (position == null) {
                context.getPlayer().sendOverlayMessage(Component.translatable("message.trainmod.bore_support"));
                return InteractionResult.FAIL;
            }
        }
        if (!level.mayInteract(context.getPlayer(), cell) || !vehicle.canMoveTo(level, position, java.util.Set.of())) {
            return InteractionResult.FAIL;
        }
        vehicle.moveToRail(position);
        vehicle.setOldPosAndRot();
        if (!level.addFreshEntity(vehicle)) {
            return InteractionResult.FAIL;
        }
        context.getItemInHand().consume(1, context.getPlayer());
        return InteractionResult.SUCCESS;
    }
}
