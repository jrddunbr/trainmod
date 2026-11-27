package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class TrainCargoSorter {
    private TrainCargoSorter() {}

    private enum Load {
        BALLAST, TRACK, TIMBER, COBBLESTONE, DIRT, OTHER;

        static Load of(ItemStack stack) {
            if (TrainVehicle.ballastSupply(stack)) return BALLAST;
            if (FreightLoad.railItem(stack) || stack.is(Trainmod.TIE_BUNDLE.get())) return TRACK;
            if (stack.is(ItemTags.LOGS)) return TIMBER;
            if (stack.is(Items.COBBLESTONE)) return COBBLESTONE;
            if (stack.is(Items.DIRT)) return DIRT;
            return OTHER;
        }

        int priority(TrainVehicle car) {
            return switch (car.kind) {
                case BALLAST_CAR -> this == BALLAST ? (car.ballastWorking() ? -1 : 0) : 6;
                case FLATCAR -> this == TRACK ? 1 : 2;
                case GONDOLA -> this == TRACK ? 2 : 1;
                case BOXCAR -> 3;
                default -> 4; // The bore's excavation buffers are overflow storage.
            };
        }
    }

    private record ItemKey(ItemStack template) {
        @Override
        public boolean equals(Object other) {
            return other instanceof ItemKey key && ItemStack.isSameItemSameComponents(template, key.template);
        }

        @Override
        public int hashCode() { return ItemStack.hashItemAndComponents(template); }
    }

    private record Stock(ItemStack stack, long count, Load load) {}

    private record Bin(TrainVehicle car, SimpleContainer cargo, List<ItemStack> planned) {
        Bin(TrainVehicle car, SimpleContainer cargo) {
            this(car, cargo, new ArrayList<>(cargo.getContainerSize()));
        }

        long store(ItemStack stack, long remaining) {
            int limit = cargo.getMaxStackSize(stack);
            for (int slot = planned.size() - 1; slot >= 0 && remaining > 0; slot--) {
                ItemStack partial = planned.get(slot);
                if (partial.getCount() == limit || !ItemStack.isSameItemSameComponents(partial, stack)) continue;
                int count = (int) Math.min(remaining, limit - partial.getCount());
                partial.grow(count);
                remaining -= count;
            }
            while (remaining > 0 && planned.size() < cargo.getContainerSize()) {
                int count = (int) Math.min(remaining, limit);
                planned.add(stack.copyWithCount(count));
                remaining -= count;
            }
            return remaining;
        }

        boolean writeChanges() {
            boolean changed = false;
            for (int slot = 0; slot < cargo.getContainerSize(); slot++) {
                ItemStack next = slot < planned.size() ? planned.get(slot) : ItemStack.EMPTY;
                if (!ItemStack.matches(cargo.getItem(slot), next)) {
                    cargo.getItems().set(slot, next);
                    changed = true;
                }
            }
            return changed;
        }
    }

    static void sort(List<TrainVehicle> train) {
        if (train.size() < 2) return;
        var bins = new ArrayList<Bin>();
        for (var car : train) {
            if (car.cargo.getContainerSize() > 0) bins.add(new Bin(car, car.cargo));
            if (car.overflow.getContainerSize() > 0) bins.add(new Bin(car, car.overflow));
        }
        if (bins.size() < 2) return;

        var quantities = new LinkedHashMap<ItemKey, Long>();
        for (var bin : bins) {
            for (var stack : bin.cargo().getItems()) {
                if (!stack.isEmpty()) quantities.merge(new ItemKey(stack), (long) stack.getCount(), Long::sum);
            }
        }
        var stock = quantities.entrySet().stream().map(entry -> {
            var stack = entry.getKey().template();
            return new Stock(stack, entry.getValue(), Load.of(stack));
        }).sorted(Comparator.comparing(Stock::load)
            .thenComparing(item -> BuiltInRegistries.ITEM.getKey(item.stack().getItem()))
            .thenComparingInt(item -> ItemStack.hashItemAndComponents(item.stack()))).toList();
        var regulators = bins.stream().filter(bin -> bin.car().kind == TrainVehicle.Kind.BALLAST_CAR)
            .sorted(Comparator.comparing(bin -> !bin.car().ballastWorking())).toList();
        if (!plan(stock, bins, regulators)) {
            // Splitting a stack can use an extra slot. Retry a tightly packed train without the split.
            if (regulators.size() < 2) return;
            bins.forEach(bin -> bin.planned().clear());
            if (!plan(stock, bins, List.of())) return;
        }
        var changed = new ArrayList<Bin>();
        for (var bin : bins) {
            if (bin.writeChanges()) changed.add(bin);
        }
        changed.forEach(bin -> bin.cargo().setChanged());
    }

    private static boolean plan(List<Stock> stock, List<Bin> bins, List<Bin> regulators) {
        var destinations = new EnumMap<Load, List<Bin>>(Load.class);
        for (var item : shareBallast(stock, regulators)) {
            long remaining = item.count();
            var preferred = destinations.computeIfAbsent(item.load(), load -> bins.stream()
                .sorted(Comparator.comparingInt(bin -> load.priority(bin.car()))).toList());
            for (var bin : preferred) {
                remaining = bin.store(item.stack(), remaining);
                if (remaining == 0) break;
            }
            // Legacy oversized stacks can occupy less space than their split copies.
            if (remaining != 0) return false;
        }
        return true;
    }

    private static List<Stock> shareBallast(List<Stock> stock, List<Bin> regulators) {
        if (regulators.size() < 2 || stock.isEmpty() || stock.getFirst().load() != Load.BALLAST) return stock;
        var ballast = stock.stream().takeWhile(item -> item.load() == Load.BALLAST).toList();
        var cargo = regulators.getFirst().cargo();
        int stackSize = cargo.getMaxStackSize(ballast.getFirst().stack());
        int slots = regulators.stream().mapToInt(bin -> bin.cargo().getContainerSize()).sum();
        // Small remainders from different items would otherwise each take up a whole slot.
        boolean fullStacks = ballast.stream().allMatch(item -> cargo.getMaxStackSize(item.stack()) == stackSize)
            && ballast.stream().mapToLong(item -> item.count() / stackSize).sum() >= slots;
        long total = fullStacks ? (long) slots * stackSize : ballast.stream().mapToLong(Stock::count).sum();
        long[] shares = new long[regulators.size()];
        for (int i = 0; i < shares.length; i++) {
            shares[i] = total / shares.length + (i < total % shares.length ? 1 : 0);
        }
        var leftovers = new ArrayList<Stock>();
        for (var item : ballast) {
            long available = fullStacks ? item.count() / stackSize * stackSize : item.count();
            long stored = 0;
            for (int i = 0; i < shares.length && stored < available; i++) {
                long offered = Math.min(shares[i], available - stored);
                long added = offered - regulators.get(i).store(item.stack(), offered);
                shares[i] -= added;
                stored += added;
            }
            if (stored < item.count()) leftovers.add(new Stock(item.stack(), item.count() - stored, item.load()));
        }
        leftovers.addAll(stock.subList(ballast.size(), stock.size()));
        return leftovers;
    }
}
