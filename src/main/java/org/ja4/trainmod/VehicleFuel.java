package org.ja4.trainmod;

import java.util.function.Predicate;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

final class VehicleFuel {
    static final int STEAM_TICKS = 400;
    static final int DIESEL_TICKS = 1200;
    static final int BATTERY_TICKS = 200;
    static final TagKey<Item> EXTERNAL_DIESEL = TagKey.create(Registries.ITEM,
        Identifier.fromNamespaceAndPath("c", "buckets/diesel"));
    static final TagKey<Item> DIESEL = TagKey.create(Registries.ITEM,
        Identifier.fromNamespaceAndPath(Trainmod.ID, "diesel_buckets"));

    private int diesel;
    private int coal;
    private int water;
    private int battery;

    SteamTraction.Output steam(double throttle, SimpleContainer supplies) {
        return SteamTraction.TIMED.evaluate(new SteamTraction.Input(throttle),
            coal > 0 || supplies.getItem(0).is(Items.COAL),
            water > 0 || bucketSlot(supplies, 1, stack -> stack.is(Items.WATER_BUCKET)) >= 0);
    }

    void burnSteam(SimpleContainer supplies, SteamTraction.Output output) {
        if (output.coalTicks() > 0 && coal == 0) {
            supplies.removeItem(0, 1);
            coal = STEAM_TICKS;
        }
        if (output.waterTicks() > 0 && water == 0) {
            returnBucket(supplies, bucketSlot(supplies, 1, stack -> stack.is(Items.WATER_BUCKET)));
            water = STEAM_TICKS;
        }
        coal -= output.coalTicks();
        water -= output.waterTicks();
    }

    boolean hasDiesel(SimpleContainer supplies) {
        return diesel > 0 || bucketSlot(supplies, 0, stack -> stack.is(DIESEL)) >= 0;
    }

    void burnDiesel(SimpleContainer supplies) {
        if (diesel == 0) {
            returnBucket(supplies, bucketSlot(supplies, 0, stack -> stack.is(DIESEL)));
            diesel = DIESEL_TICKS;
        }
        diesel--;
    }

    boolean hasCharge() { return battery > 0; }
    void drawCharge() { battery--; }
    void charge() { battery = Math.min(battery + 1, BATTERY_TICKS); }

    private static int bucketSlot(SimpleContainer supplies, int first, Predicate<ItemStack> accepts) {
        for (int i = first; i < supplies.getContainerSize(); i++) {
            if (accepts.test(supplies.getItem(i))) return i;
        }
        return -1;
    }

    private static void returnBucket(SimpleContainer supplies, int slot) {
        supplies.setItem(slot, new ItemStack(Items.BUCKET));
    }

    void load(ValueInput input) {
        diesel = Math.clamp(input.getIntOr("DieselTicks", 0), 0, DIESEL_TICKS);
        coal = Math.clamp(input.getIntOr("CoalTicks", 0), 0, STEAM_TICKS);
        water = Math.clamp(input.getIntOr("WaterTicks", 0), 0, STEAM_TICKS);
        battery = Math.clamp(input.getIntOr("BatteryTicks", 0), 0, BATTERY_TICKS);
    }

    void save(ValueOutput output) {
        if (diesel > 0) output.putInt("DieselTicks", diesel);
        if (coal > 0) output.putInt("CoalTicks", coal);
        if (water > 0) output.putInt("WaterTicks", water);
        if (battery > 0) output.putInt("BatteryTicks", battery);
    }
}
