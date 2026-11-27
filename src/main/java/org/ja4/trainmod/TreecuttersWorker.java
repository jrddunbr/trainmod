package org.ja4.trainmod;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

final class TreecuttersWorker extends Villager {
    TreecuttersWorker(EntityType<? extends TreecuttersWorker> type, Level level) {
        super(type, level);
        if (!level.isClientSide()) {
            setCanPickUpLoot(false);
            setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            setDropChance(EquipmentSlot.MAINHAND, 0);
        }
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        goalSelector.addGoal(0, new FloatGoal(this));
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // The crew car supplies navigation targets. Skip the villager's schedules and job sites.
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }
}
