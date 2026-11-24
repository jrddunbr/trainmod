package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

final class TrackWork {
    private static final int RAILS_PER_BLOCK = 2;

    private TrackWork() {}

    record Issue(String key, Object... details) {
        Component message() { return Component.translatable("message.trainmod." + key, details); }
    }

    private static Issue at(String key, BlockPos pos, Object... reason) {
        Object[] details = new Object[reason.length + 1];
        details[0] = pos.toShortString();
        System.arraycopy(reason, 0, details, 1, reason.length);
        return new Issue(key, details);
    }

    static Issue extendIfNeeded(ServerLevel level, ServerPlayer driver, TrainVehicle bore,
                                 RailNetwork.Position position, double travel, List<TrainVehicle> trailing) {
        Vec3 start = position.point();
        Vec3 facing = position.tangent();
        boolean diagonal = Math.abs(facing.x * facing.z) > 0.1;
        double nose = bore.kind.length / 2.0 + (diagonal ? 0.70 : 0.24);
        if (!level.getEntitiesOfClass(BufferStop.Mounted.class, AABB.ofSize(start, 8, 3, 8), stop -> {
            Vec3 offset = stop.position().subtract(start);
            double ahead = offset.dot(facing);
            return ahead >= 0 && ahead <= nose + travel && offset.subtract(facing.scale(ahead)).lengthSqr() < 0.36;
        }).isEmpty()) {
            return new Issue("buffer_reached");
        }
        double remaining = nose + travel;
        RailNetwork.Rail rail;
        boolean end;
        while (true) {
            rail = position.rail();
            end = position.forward() > 0;
            double distance = end ? rail.path().length - position.distance() : position.distance();
            Vec3 exit = rail.path().tangent(end ? rail.path().length : 0).scale(position.forward());
            if (distance > remaining) return null;
            remaining -= distance;
            RailNetwork.Position connected = RailNetwork.next(level, rail, end);
            if (connected == null) {
                if (!rail.open(end) || !rail.levelStraight() || exit.dot(facing) < 0.999
                    || Math.abs(rail.end(end).y - start.y) > 0.001) return new Issue("bore_heading");
                break;
            }
            if (connected.tangent().dot(facing) < 0.999) return null;
            position = connected;
        }
        BlockPos next = BlockPos.containing(rail.end(end).add(facing.scale(0.15)));
        if (!level.isLoaded(next)) return at("bore_unloaded", next);
        if (RailNetwork.isRail(level.getBlockState(next))) return at("bore_misaligned", next);
        if (level.getBlockState(next).is(Trainmod.BUFFER_STOP.get())) {
            return new Issue("buffer_reached");
        }
        double spacing = diagonal ? Math.sqrt(2) : 1;
        int faces = 1 + (int) Math.floor(remaining / spacing);
        // Feed the rail at the middle of the machine, behind the cutting head.
        boolean lay = TrackGeometry.origin(next).subtract(start).dot(facing) <= travel + 0.32;
        return excavate(level, driver, bore, next, facing, faces, lay, trailing);
    }

    private static Issue excavate(ServerLevel level, ServerPlayer driver, TrainVehicle bore, BlockPos next,
                                  Vec3 heading, int faces, boolean lay, List<TrainVehicle> trailing) {
        int dx = (int) Math.round(heading.x), dz = (int) Math.round(heading.z);
        int rotation = RailwayBlocks.Ballast.heading((float) Math.toDegrees(Math.atan2(-heading.x, heading.z)));
        BlockState existingRoadbed = level.getBlockState(next.below());
        boolean needsStone = lay && !(existingRoadbed.is(Trainmod.BALLAST.get())
            || roadbedMaterial(existingRoadbed) && TrackBlock.supported(level, next));
        boolean usesSupplies = !driver.getAbilities().instabuild;
        if (lay && usesSupplies) {
            ItemStack rails = bore.supplies.getItem(TrainVehicle.RAIL_SUPPLY);
            if (!rails.is(Trainmod.RAIL.get()) || rails.getCount() < RAILS_PER_BLOCK) return new Issue("bore_need_rail");
            if (!bore.supplies.getItem(TrainVehicle.TIE_SUPPLY).is(Trainmod.TIE_BUNDLE.get())) return new Issue("bore_need_ties");
            if (needsStone && !bore.supplies.getItem(TrainVehicle.STONE_SUPPLY).is(Items.COBBLESTONE)) {
                return new Issue("bore_need_stone");
            }
        }
        Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
        if (needsStone) changes.put(next.below(), Blocks.COBBLESTONE.defaultBlockState());
        for (int face = 0; face < faces; face++) {
            BlockPos center = next.offset(face * dx, 0, face * dz);
            if (face > 0 && RailNetwork.isRail(level.getBlockState(center))) break;
            for (BlockPos base : slice(center, dx, dz)) {
                for (int height = 0; height < 4; height++) {
                    BlockPos cell = base.above(height);
                    changes.put(cell, Blocks.AIR.defaultBlockState());
                }
            }
        }
        if (lay) changes.put(next, Trainmod.TRACK.get(TrackGeometry.Kind.UNBALLASTED_STRAIGHT).get().segment(rotation, true, 0));
        List<ItemStack> drops = new ArrayList<>();
        for (var entry : changes.entrySet()) {
            BlockPos cell = entry.getKey();
            if (!level.isInWorldBounds(cell)) return at("bore_world_limit", cell);
            if (!level.isLoaded(cell)) return at("bore_unloaded", cell);
            if (!level.mayInteract(driver, cell)) return at("bore_protected", cell);
            if (cell.getY() >= next.getY()) {
                Issue obstruction = faceObstruction(level, driver, bore, cell);
                if (obstruction != null) return obstruction;
            }
            BlockState old = level.getBlockState(cell);
            if (old == entry.getValue() || old.isAir() || old.liquid()) {
                continue;
            }
            if (RailNetwork.isRail(old)) return at("bore_track_in_cut", cell);
            if (old.is(Trainmod.BUFFER_STOP.get())) return new Issue("buffer_reached");
            if (old.hasBlockEntity()) return at("bore_block_entity", cell, old.getBlock().getName());
            if (old.getDestroySpeed(level, cell) < 0) return at("bore_unbreakable", cell, old.getBlock().getName());
            if (NeoForge.EVENT_BUS.post(new BreakBlockEvent(level, cell, old, driver)).isCanceled()) {
                return at("bore_protected", cell);
            }
            drops.addAll(Block.getDrops(old, level, cell, null, driver, new ItemStack(Items.IRON_PICKAXE)));
        }
        List<SimpleContainer> cargo = new ArrayList<>();
        trailing.forEach(car -> cargo.add(car.cargo));
        cargo.add(bore.cargo);
        cargo.add(bore.overflow);
        List<SimpleContainer> stagedCargo = new ArrayList<>();
        for (SimpleContainer inventory : cargo) {
            SimpleContainer copy = new SimpleContainer(inventory.getContainerSize());
            copyInventory(inventory, copy);
            stagedCargo.add(copy);
        }
        for (ItemStack drop : drops) {
            ItemStack remaining = drop;
            for (SimpleContainer inventory : stagedCargo) {
                remaining = inventory.addItem(remaining);
                if (remaining.isEmpty()) {
                    break;
                }
            }
            if (!remaining.isEmpty()) {
                return at("bore_full", next, drop.getHoverName());
            }
        }
        if (!apply(level, driver, changes, existingRoadbed)) {
            return at("bore_place_denied", next);
        }
        for (int i = 0; i < cargo.size(); i++) {
            copyInventory(stagedCargo.get(i), cargo.get(i));
        }
        if (lay && usesSupplies) {
            bore.supplies.removeItem(TrainVehicle.RAIL_SUPPLY, RAILS_PER_BLOCK);
            bore.supplies.removeItem(TrainVehicle.TIE_SUPPLY, 1);
        }
        if (needsStone && usesSupplies) {
            bore.supplies.removeItem(TrainVehicle.STONE_SUPPLY, 1);
        }
        return null;
    }

    private static Issue faceObstruction(ServerLevel level, ServerPlayer driver, TrainVehicle bore, BlockPos cell) {
        AABB box = new AABB(cell);
        var living = level.getEntitiesOfClass(LivingEntity.class, box, entity -> entity != driver);
        if (!living.isEmpty()) return at("bore_entity", cell, living.getFirst().getDisplayName());
        var cars = level.getEntitiesOfClass(TrainVehicle.class, box, car -> car != bore);
        if (!cars.isEmpty()) return at("bore_entity", cell, cars.getFirst().getDisplayName());
        var carts = level.getEntitiesOfClass(AbstractMinecart.class, box);
        if (!carts.isEmpty()) return at("bore_entity", cell, carts.getFirst().getDisplayName());
        return level.getEntitiesOfClass(BufferStop.Mounted.class, box).isEmpty() ? null : new Issue("buffer_reached");
    }

    private static List<BlockPos> slice(BlockPos center, int dx, int dz) {
        BlockPos low = center.offset(-dz, 0, dx);
        BlockPos high = center.offset(dz, 0, -dx);
        if (dx == 0 || dz == 0) return List.of(low, center, high);
        // Clear the slivers at the diagonal corners, including the shoulders just behind the face.
        return List.of(center, low, high, center.offset(-dx, 0, 0), center.offset(dx, 0, 0),
            center.offset(0, 0, -dz), center.offset(0, 0, dz),
            center.offset(-2 * dx, 0, 0), center.offset(0, 0, -2 * dz));
    }

    static int ballast(ServerLevel level, ServerPlayer driver, BlockPos below, int available) {
        BlockPos above = below.above();
        BlockState state = level.getBlockState(above);
        if (available == 0 || !(state.getBlock() instanceof TrackBlock track)) return 0;
        boolean unballasted = track.kind == TrackGeometry.Kind.UNBALLASTED_STRAIGHT;
        if (!track.kind.hasBallast() && !unballasted) return 0;
        RailNetwork.Rail rail = RailNetwork.railAt(level, above);
        if (rail == null) return 0;
        Vec3 tangent = rail.path().tangent(rail.project(TrackGeometry.origin(above)));
        int heading = RailwayBlocks.Ballast.heading((float) Math.toDegrees(Math.atan2(-tangent.x, tangent.z)));
        BlockState ballast = Trainmod.BALLAST.get().defaultBlockState().setValue(TrackBlock.ROTATION, heading);
        if (!unballasted) return replaceRoadbed(level, driver, below, ballast) ? 1 : 0;

        boolean needsRoadbed = !level.getBlockState(below).is(Trainmod.BALLAST.get());
        int used = needsRoadbed ? 2 : 1;
        if (available < used || !level.mayInteract(driver, above) || !level.mayInteract(driver, below)
            || needsRoadbed && !canReplaceRoadbed(level, driver, below)) return 0;
        var changes = new LinkedHashMap<BlockPos, BlockState>();
        if (needsRoadbed) changes.put(below, ballast);
        changes.put(above, Trainmod.TRACK.get(TrackGeometry.Kind.STRAIGHT).get().segment(
            state.getValue(TrackBlock.ROTATION), state.getValue(TrackBlock.HAND) == ChestType.LEFT, 0));
        return apply(level, driver, changes, level.getBlockState(below.below())) ? used : 0;
    }

    private static int widenCrest(ServerLevel level, ServerPlayer driver, BlockPos below, BlockState material,
                                  int dx, int dz, int limit) {
        int used = 0;
        for (int sign : new int[]{-1, 1}) {
            BlockPos shoulder = below.offset(sign * dx, 0, sign * dz);
            if (used < limit && !RailNetwork.isRail(level.getBlockState(shoulder.above()))
                && replaceRoadbed(level, driver, shoulder, material)) used++;
        }
        return used;
    }

    private static boolean replaceRoadbed(ServerLevel level, ServerPlayer driver, BlockPos pos, BlockState ballast) {
        return canReplaceRoadbed(level, driver, pos) && apply(level, driver, Map.of(pos, ballast), level.getBlockState(pos.below()));
    }

    private static boolean canReplaceRoadbed(ServerLevel level, ServerPlayer driver, BlockPos pos) {
        if (!level.isLoaded(pos) || !level.mayInteract(driver, pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return TrackBlock.supported(level, pos);
        return roadbedMaterial(state) && !NeoForge.EVENT_BUS.post(new BreakBlockEvent(level, pos, state, driver)).isCanceled();
    }

    private static boolean roadbedMaterial(BlockState state) {
        return state.is(Blocks.SAND) || state.is(Blocks.GRAVEL) || state.is(Blocks.STONE)
            || state.is(Blocks.COBBLESTONE) || state.is(Blocks.DIRT);
    }

    private static RailNetwork.Rail ballastedRail(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof TrackBlock track && track.kind.hasBallast()
            ? RailNetwork.railAt(level, pos) : null;
    }

    static int cascadeBallast(ServerLevel level, ServerPlayer actor, BlockPos ballast, boolean wide, int limit) {
        if (limit == 0 || !level.isLoaded(ballast) || !level.mayInteract(actor, ballast)) return 0;
        BlockState material = level.getBlockState(ballast);
        if (!material.is(Trainmod.BALLAST.get())) return 0;
        BlockPos track = ballast.above();
        RailNetwork.Rail rail = ballastedRail(level, track);
        if (rail == null) return 0;
        Vec3 tangent = rail.path().tangent(rail.project(TrackGeometry.origin(track))).multiply(1, 0, 1).normalize();
        int dx = (int) Math.round(-tangent.z);
        int dz = (int) Math.round(tangent.x);
        int crestWidth = wide ? 1 : 0;
        int crest = wide ? widenCrest(level, actor, ballast, material, -dx, -dz, limit) : 0;
        int placed = crest;
        // Build up from solid ground so every layer rests on the one below.
        for (int depth = 4; depth >= 1 && placed < limit; depth--) {
            BlockPos center = ballast.below(depth);
            if (placeShoulder(level, actor, center, material)) placed++;
            for (int step = 1; step <= depth + crestWidth && placed < limit; step++) {
                for (int side : new int[]{-1, 1}) {
                    if (placed == limit) break;
                    int offset = step * side;
                    if (dx != 0 && dz != 0) {
                        BlockPos join = center.offset(dx * offset, 0, dz * (offset - side));
                        if (placeShoulder(level, actor, join, material)) placed++;
                    }
                    if (placed < limit && placeShoulder(level, actor, center.offset(dx * offset, 0, dz * offset), material)) placed++;
                }
            }
        }
        // The lower layers can support crest blocks that were over a gap on the first pass.
        if (wide && crest < 2 && placed > crest && placed < limit) {
            placed += widenCrest(level, actor, ballast, material, -dx, -dz, limit - placed);
        }
        return placed;
    }

    private static boolean placeShoulder(ServerLevel level, ServerPlayer actor, BlockPos pos, BlockState material) {
        if (!level.isInsideBuildHeight(pos) || !level.isLoaded(pos) || !level.mayInteract(actor, pos)
            || !level.getBlockState(pos).isAir() || RailNetwork.isRail(level.getBlockState(pos.above()))) return false;
        BlockState ground = level.getBlockState(pos.below());
        return ground.isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)
            && level.isUnobstructed(material, pos, net.minecraft.world.phys.shapes.CollisionContext.empty())
            && apply(level, actor, Map.of(pos, material), ground);
    }

    static boolean apply(ServerLevel level, ServerPlayer actor, Map<BlockPos, BlockState> changes, BlockState against) {
        List<BlockSnapshot> snapshots = new ArrayList<>();
        int quiet = Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
        for (var entry : changes.entrySet()) {
            BlockPos pos = entry.getKey();
            if (level.getBlockState(pos) == entry.getValue()) {
                continue;
            }
            BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos, quiet);
            if (!level.setBlock(pos, entry.getValue(), quiet)) {
                rollback(snapshots, quiet);
                return false;
            }
            snapshots.add(snapshot);
        }
        if (snapshots.isEmpty()) {
            return true;
        }
        BlockEvent.EntityPlaceEvent event = snapshots.size() == 1
            ? new BlockEvent.EntityPlaceEvent(snapshots.getFirst(), against, actor)
            : new BlockEvent.EntityMultiPlaceEvent(snapshots, against, actor);
        if (NeoForge.EVENT_BUS.post(event).isCanceled()) {
            rollback(snapshots, quiet);
            return false;
        }
        for (BlockSnapshot snapshot : snapshots) {
            BlockPos pos = snapshot.getPos();
            BlockState state = level.getBlockState(pos);
            level.markAndNotifyBlock(pos, level.getChunkAt(pos), snapshot.getState(), state,
                Block.UPDATE_ALL, Block.UPDATE_LIMIT);
            if (state.getBlock() instanceof TrackBlock.Turnout turnout && turnout.panel(state) != null) {
                level.scheduleTick(pos, state.getBlock(), 1);
                RailwayBlocks.Stand.pointsChanged(level, pos);
            }
        }
        return true;
    }

    private static void rollback(List<BlockSnapshot> snapshots, int flags) {
        for (BlockSnapshot snapshot : snapshots.reversed()) {
            snapshot.restore(flags);
        }
    }

    private static void copyInventory(SimpleContainer from, SimpleContainer to) {
        for (int slot = 0; slot < from.getContainerSize(); slot++) {
            to.getItems().set(slot, from.getItem(slot).copy());
        }
        to.setChanged();
    }
}
