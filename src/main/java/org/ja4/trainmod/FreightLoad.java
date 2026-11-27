package org.ja4.trainmod;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseRailBlock;

/** The visible cargo derived from an inventory. The inventory remains the source of truth. */
record FreightLoad(int presents, boolean tree, boolean sleigh, int wreathCrates, Bulk bulk, int fillPercent,
                   Track track, List<Logs> timber, int crateCount, List<Identifier> crateItems) {
    static final int MAX_CRATES = 3;
    private static final int MAX_LOGS = 15;
    private static final int MAX_TRACK_PIECES = 12;
    static final FreightLoad EMPTY = new FreightLoad(0, false, false, 0, Bulk.NONE, 0, Track.EMPTY, List.of(), 0, List.of());

    enum Bulk {
        NONE, GRAVEL, COBBLESTONE, DIRT;

        private static final Bulk[] VALUES = values();
        private static final StreamCodec<ByteBuf, Bulk> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(
            id -> VALUES[id], Bulk::ordinal);
    }

    record Logs(int bark, int endGrain, int count) {
        private static final StreamCodec<ByteBuf, Logs> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Logs::bark,
            ByteBufCodecs.VAR_INT, Logs::endGrain,
            ByteBufCodecs.VAR_INT, Logs::count,
            Logs::new);
    }

    record Track(int rails, int ties) {
        private static final Track EMPTY = new Track(0, 0);
        private static final StreamCodec<ByteBuf, Track> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Track::rails,
            ByteBufCodecs.VAR_INT, Track::ties,
            Track::new);

        boolean isEmpty() { return rails == 0 && ties == 0; }
    }

    private record Wood(int bark, int endGrain) {}

    static final StreamCodec<ByteBuf, FreightLoad> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, FreightLoad::presents,
        ByteBufCodecs.BOOL, FreightLoad::tree,
        ByteBufCodecs.BOOL, FreightLoad::sleigh,
        ByteBufCodecs.VAR_INT, FreightLoad::wreathCrates,
        Bulk.STREAM_CODEC, FreightLoad::bulk,
        ByteBufCodecs.VAR_INT, FreightLoad::fillPercent,
        Track.STREAM_CODEC, FreightLoad::track,
        Logs.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CRATES)), FreightLoad::timber,
        ByteBufCodecs.VAR_INT, FreightLoad::crateCount,
        Identifier.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CRATES)), FreightLoad::crateItems,
        FreightLoad::new);

    FreightLoad {
        timber = List.copyOf(timber);
        crateItems = List.copyOf(crateItems);
    }

    boolean hasModel() {
        return hasHoliday() || !track.isEmpty() || !timber.isEmpty() || crateCount > 0;
    }

    boolean hasHoliday() {
        return presents > 0 || tree || sleigh || wreathCrates > 0;
    }

    static boolean railItem(ItemStack stack) {
        return stack.is(Trainmod.RAIL.get()) || stack.getItem() instanceof TrackItem
            || stack.getItem() instanceof BlockItem item && item.getBlock() instanceof BaseRailBlock;
    }

    static FreightLoad from(Container cargo, boolean gondola) {
        double total = 0, loose = 0, gravel = 0, cobble = 0, dirt = 0, logLoad = 0, rails = 0, ties = 0;
        int presents = 0, wreaths = 0, wreathCrates = 0;
        boolean tree = false, sleigh = false;
        var lumber = new HashMap<Identifier, Double>();
        var quantities = new HashMap<Identifier, Long>();
        for (int slot = 0; slot < cargo.getContainerSize(); slot++) {
            ItemStack stack = cargo.getItem(slot);
            if (stack.isEmpty()) continue;
            double units = stack.getCount() / (double) stack.getMaxStackSize();
            total += units;
            Identifier item = BuiltInRegistries.ITEM.getKey(stack.getItem());
            quantities.merge(item, (long) stack.getCount(), Long::sum);
            if (stack.is(Trainmod.PRESENT.get().asItem())) {
                presents = capped(presents, stack.getCount(), FestiveModels.MAX_VISIBLE_PRESENTS);
            } else if (stack.is(Trainmod.CHRISTMAS_TREE.get().asItem())) {
                tree = true;
            } else if (stack.is(Trainmod.SLEIGH.get().asItem())) {
                sleigh = true;
            } else if (stack.is(Trainmod.WREATH_CRATE.get().asItem())) {
                wreathCrates = capped(wreathCrates, stack.getCount(), MAX_CRATES);
            } else if (stack.is(Trainmod.WREATH.get().asItem())) {
                wreaths = capped(wreaths, stack.getCount(), MAX_CRATES * 4);
            } else if (gondola && TrainVehicle.ballastSupply(stack)) {
                gravel += units;
            } else {
                loose += units;
                if (gondola && stack.is(Items.COBBLESTONE)) cobble += units;
                if (gondola && stack.is(Items.DIRT)) dirt += units;
                if (railItem(stack)) rails += units;
                if (stack.is(Trainmod.TIE_BUNDLE.get())) ties += units;
                if (stack.is(ItemTags.LOGS)) {
                    lumber.merge(woodFamily(item), (double) stack.getCount(), Double::sum);
                    logLoad += units;
                }
            }
        }
        wreathCrates = capped(wreathCrates, (wreaths + 3) / 4, MAX_CRATES);
        int capacity = cargo.getContainerSize();
        double majority = total / 2;
        Bulk bulk = Bulk.NONE;
        int fill = 0;
        Track track = Track.EMPTY;
        List<Logs> timber = List.of();
        int crateCount = 0;
        List<Identifier> crateItems = List.of();
        if (cobble > majority) {
            bulk = Bulk.COBBLESTONE;
            fill = fillPercent(cobble, capacity);
        } else if (dirt > majority) {
            bulk = Bulk.DIRT;
            fill = fillPercent(dirt, capacity);
        } else if (rails + ties > majority) {
            track = trackBundles(rails, ties, capacity);
        } else if (logLoad > majority && lumber.size() <= MAX_CRATES) {
            timber = logBundles(lumber, logLoad, capacity);
        } else {
            if (gravel > 0) {
                bulk = Bulk.GRAVEL;
                fill = fillPercent(gravel, capacity);
            }
            boolean festive = presents > 0 || tree || sleigh || wreathCrates > 0;
            if (loose > 0 && (!festive || loose > majority)) {
                crateItems = quantities.entrySet().stream().sorted(Comparator
                    .<Map.Entry<Identifier, Long>>comparingLong(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey))
                    .limit(MAX_CRATES).map(Map.Entry::getKey).toList();
                crateCount = Math.clamp((int) Math.ceil(MAX_CRATES * loose / capacity), crateItems.size(), MAX_CRATES);
            }
        }
        return new FreightLoad(presents, tree, sleigh, wreathCrates, bulk, fill, track, timber, crateCount, crateItems);
    }

    private static int capped(int current, int added, int max) {
        return current + Math.min(max - current, added);
    }

    private static int fillPercent(double amount, int capacity) {
        return Math.min(100, (int) Math.ceil(amount * 100 / capacity));
    }

    private static Identifier woodFamily(Identifier item) {
        String path = item.getPath();
        if (path.startsWith("stripped_")) path = path.substring("stripped_".length());
        for (String suffix : List.of("_log", "_wood", "_stem", "_hyphae")) {
            if (path.endsWith(suffix)) return item.withPath(path.substring(0, path.length() - suffix.length()));
        }
        return item.withPath(path);
    }

    private static Wood wood(Identifier family) {
        if (family.getNamespace().equals("minecraft")) {
            Wood vanilla = switch (family.getPath()) {
                case "oak" -> new Wood(0x665039, 0xc49a62);
                case "spruce" -> new Wood(0x45301d, 0xa97a47);
                case "birch" -> new Wood(0xd8d5c4, 0xe8cf99);
                case "jungle" -> new Wood(0x67543b, 0xd29d77);
                case "acacia" -> new Wood(0x6b6460, 0xcf834d);
                case "dark_oak" -> new Wood(0x39291c, 0x906438);
                case "mangrove" -> new Wood(0x5b3328, 0xbc654f);
                case "cherry" -> new Wood(0x5a363f, 0xe5a59b);
                case "pale_oak" -> new Wood(0x797776, 0xe1ddd6);
                case "crimson" -> new Wood(0x65334a, 0xb96171);
                case "warped" -> new Wood(0x3a625d, 0x6ca99c);
                default -> null;
            };
            if (vanilla != null) return vanilla;
        }
        int shade = Math.floorMod(family.hashCode(), 36);
        return new Wood(0x584236 + shade * 0x010100, 0xb28a65 + shade * 0x010101);
    }

    private static List<Logs> logBundles(Map<Identifier, Double> lumber, double volume, int capacity) {
        var types = lumber.entrySet().stream().sorted(Comparator
            .<Map.Entry<Identifier, Double>>comparingDouble(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey)).toList();
        double total = types.stream().mapToDouble(Map.Entry::getValue).sum();
        int pieces = Math.clamp((int) Math.ceil(MAX_LOGS * volume / capacity), types.size(), MAX_LOGS);
        int[] shares = new int[types.size()];
        java.util.Arrays.fill(shares, 1);
        for (int assigned = types.size(); assigned < pieces; assigned++) {
            int largestRemainder = 0;
            double best = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < shares.length; i++) {
                double remainder = pieces * types.get(i).getValue() / total - shares[i];
                if (remainder > best) {
                    best = remainder;
                    largestRemainder = i;
                }
            }
            shares[largestRemainder]++;
        }
        var result = new ArrayList<Logs>(types.size());
        for (int i = 0; i < types.size(); i++) {
            Wood color = wood(types.get(i).getKey());
            result.add(new Logs(color.bark(), color.endGrain(), shares[i]));
        }
        return result;
    }

    private static Track trackBundles(double rails, double ties, int capacity) {
        double total = rails + ties;
        int pieces = Math.clamp((int) Math.ceil(MAX_TRACK_PIECES * total / capacity), 1, MAX_TRACK_PIECES);
        if (rails == 0) return new Track(0, pieces);
        if (ties == 0) return new Track(pieces, 0);
        pieces = Math.max(2, pieces);
        int railPieces = Math.clamp((int) Math.round(pieces * rails / total), 1, pieces - 1);
        return new Track(railPieces, pieces - railPieces);
    }
}
