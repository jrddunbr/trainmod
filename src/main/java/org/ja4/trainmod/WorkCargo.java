package org.ja4.trainmod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/** Stages supplies and drops until the corresponding block changes succeed. */
final class WorkCargo {
    private final Map<SimpleContainer, SimpleContainer> staged = new LinkedHashMap<>();

    private SimpleContainer edit(SimpleContainer inventory) {
        return staged.computeIfAbsent(inventory, original -> {
            var copy = new SimpleContainer(original.getContainerSize());
            for (int slot = 0; slot < original.getContainerSize(); slot++) {
                copy.getItems().set(slot, original.getItem(slot).copy());
            }
            return copy;
        });
    }

    private int take(SimpleContainer inventory, int slot, Item item, int count) {
        ItemStack stack = staged.getOrDefault(inventory, inventory).getItem(slot);
        return stack.is(item) ? edit(inventory).removeItem(slot, count).getCount() : 0;
    }

    boolean supply(SimpleContainer supplies, int slot, List<SimpleContainer> gondolas, ItemLike ingredient, int count) {
        Item item = ingredient.asItem();
        count -= take(supplies, slot, item, count);
        for (var gondola : gondolas) {
            for (int i = 0; count > 0 && i < gondola.getContainerSize(); i++) {
                count -= take(gondola, i, item, count);
            }
            if (count == 0) break;
        }
        return count == 0;
    }

    boolean collect(List<SimpleContainer> cargo, ItemStack drop) {
        return insert(cargo, drop).isEmpty();
    }

    ItemStack insert(List<SimpleContainer> cargo, ItemStack drop) {
        for (var inventory : cargo) {
            if (staged.getOrDefault(inventory, inventory).canAddItem(drop)) {
                drop = edit(inventory).addItem(drop);
                if (drop.isEmpty()) break;
            }
        }
        return drop;
    }

    void commit() {
        staged.forEach((inventory, copy) -> {
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                inventory.getItems().set(slot, copy.getItem(slot));
            }
            inventory.setChanged();
        });
    }
}
