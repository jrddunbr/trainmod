package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
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
import org.jspecify.annotations.Nullable;

final class TrackWork {
    private static final int RAILS_PER_BLOCK = 2;
    private static final int BALLAST_DEPTH = 4;
    static final int BALLAST_PORTIONS = 8;

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
            double lookAhead = position.distance() + position.forward() * Math.min(remaining, distance);
            if (rail.blocks(position.distance(), lookAhead)) return new Issue("buffer_reached");
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
        if (TrackBlock.broken(level.getBlockState(next)) || level.getBlockState(next).is(Trainmod.BUFFER_STOP.get())) {
            return new Issue("buffer_reached");
        }
        if (RailNetwork.isRail(level.getBlockState(next))) return at("bore_misaligned", next);
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
            if (TrackBlock.broken(old) || old.is(Trainmod.BUFFER_STOP.get())) return new Issue("buffer_reached");
            if (RailNetwork.isRail(old)) return at("bore_track_in_cut", cell);
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

    static boolean mayBreak(ServerLevel level, ServerPlayer actor, BlockPos pos, BlockState state) {
        return level.mayInteract(actor, pos) && !actor.blockActionRestricted(level, pos, actor.gameMode())
            && !NeoForge.EVENT_BUS.post(new BreakBlockEvent(level, pos, state, actor)).isCanceled();
    }

    static boolean damage(ServerLevel level, @Nullable ServerPlayer driver, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!TrackBlock.unballasted(state)) return false;
        var actor = driver != null ? driver : net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        if (!mayBreak(level, actor, pos, state)) return false;
        if (!level.setBlockAndUpdate(pos, state.setValue(TrackBlock.CONDITION, TrackBlock.Condition.BROKEN))) return false;
        level.levelEvent(2001, pos, Block.getId(state));
        return true;
    }

    static int ballast(ServerLevel level, ServerPlayer driver, BlockPos below, int portions, TrainVehicle.BallastMode mode) {
        BlockPos above = below.above();
        BlockState state = level.getBlockState(above);
        if (portions == 0 || !(state.getBlock() instanceof TrackBlock track) || TrackBlock.broken(state)
            || track.kind == TrackGeometry.Kind.BRIDGE_STRAIGHT) return 0;
        RailNetwork.Rail rail = RailNetwork.railAt(level, above);
        if (rail == null) return 0;
        int used = 0;
        if (TrackBlock.unballasted(state)) {
            if (!level.mayInteract(driver, above)) return 0;
            BlockState finished = track.kind == TrackGeometry.Kind.UNBALLASTED_STRAIGHT
                ? Trainmod.TRACK.get(TrackGeometry.Kind.STRAIGHT).get().segment(
                    state.getValue(TrackBlock.ROTATION), state.getValue(TrackBlock.HAND) == ChestType.LEFT, 0)
                : state.setValue(TrackBlock.CONDITION, TrackBlock.Condition.BALLASTED);
            if (!apply(level, driver, Map.of(above, finished), level.getBlockState(below))) return 0;
            used++;
        }
        int blocks = (portions - used) / BALLAST_PORTIONS;
        if (mode == TrainVehicle.BallastMode.TRACK_ONLY || blocks == 0) return used;
        Vec3 tangent = rail.path().tangent(rail.project(TrackGeometry.origin(above)));
        int heading = RailwayBlocks.Ballast.heading((float) Math.toDegrees(Math.atan2(-tangent.x, tangent.z)));
        BlockState ballast = Trainmod.BALLAST.get().defaultBlockState().setValue(TrackBlock.ROTATION, heading);
        int crestWidth = mode == TrainVehicle.BallastMode.WIDE ? 1 : 0;
        return used + BALLAST_PORTIONS * ballastRoadbed(level, driver, below, ballast, tangent, crestWidth, blocks);
    }

    private static boolean replaceRoadbed(ServerLevel level, ServerPlayer driver, BlockPos pos, BlockState ballast) {
        if (!level.isLoaded(pos) || !level.mayInteract(driver, pos)) return false;
        BlockState old = level.getBlockState(pos);
        if (old.isAir() ? !TrackBlock.supported(level, pos) : !ballastSoil(old) || !mayBreak(level, driver, pos, old)) {
            return false;
        }
        return apply(level, driver, Map.of(pos, ballast), level.getBlockState(pos.below()));
    }

    private static boolean roadbedMaterial(BlockState state) {
        return state.is(Blocks.SAND) || state.is(Blocks.GRAVEL) || state.is(Blocks.STONE)
            || state.is(Blocks.COBBLESTONE) || state.is(Blocks.DIRT);
    }

    private static boolean ballastSoil(BlockState state) {
        if (state.hasBlockEntity()) return false;
        return state.is(BlockTags.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)
            || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL)
            || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
            || state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLED_DEEPSLATE);
    }

    private static int ballastRoadbed(ServerLevel level, ServerPlayer actor, BlockPos center, BlockState material,
                                      Vec3 tangent, int crestWidth, int limit) {
        int placed = replaceRoadbed(level, actor, center, material) ? 1 : 0;
        if (placed == limit || !level.getBlockState(center).is(Trainmod.BALLAST.get()) || !level.mayInteract(actor, center)) {
            return placed;
        }
        Vec3 heading = tangent.multiply(1, 0, 1).normalize();
        int dx = (int) Math.round(-heading.z);
        int dz = (int) Math.round(heading.x);
        boolean diagonal = dx != 0 && dz != 0;
        Set<BlockPos> reached = new HashSet<>();
        reached.add(center);
        var pendingCrest = new ArrayList<BlockPos>(2);
        placed += formCrest(level, actor, center, material, dx, dz, diagonal, crestWidth, reached, pendingCrest, limit - placed);
        if (placed == limit) return placed;
        var layers = new ArrayList<List<BlockPos>>();
        for (int depth = 1; depth <= BALLAST_DEPTH; depth++) {
            layers.add(traceBallastLayer(level, actor, center.below(depth), dx, dz, diagonal, depth, crestWidth, reached));
        }

        // Settle from the lowest reachable layer up so each block has something to rest on.
        for (var layer : layers.reversed()) {
            for (BlockPos pos : layer) {
                if (placed == limit) return placed;
                if (placeShoulder(level, actor, pos, material)) placed++;
            }
        }
        for (BlockPos shoulder : pendingCrest) {
            if (placed == limit) break;
            if (placeShoulder(level, actor, shoulder, material)) placed++;
        }
        return placed;
    }

    private static int formCrest(ServerLevel level, ServerPlayer actor, BlockPos center, BlockState material, int dx, int dz,
                                 boolean diagonal, int width, Set<BlockPos> reached, List<BlockPos> pending, int limit) {
        int stepsPerBlock = diagonal ? 2 : 1;
        int edge = width * stepsPerBlock;
        int placed = 0;
        for (int side : new int[]{-1, 1}) {
            for (int step = 1; step <= edge + stepsPerBlock; step++) {
                BlockPos pos = slopeCell(center, dx, dz, step * side, diagonal);
                if (step <= edge) {
                    if (!canWorkAt(level, actor, pos) || RailNetwork.isRail(level.getBlockState(pos.above()))) break;
                    BlockState state = level.getBlockState(pos);
                    BlockPos outward = slopeCell(center, dx, dz, (step + 1) * side, diagonal);
                    if (!state.isAir() && !state.is(Trainmod.BALLAST.get())
                        && (!ballastSoil(state) || !level.getBlockState(pos.above()).isAir()
                            || !level.isLoaded(outward) || retainingWall(level, pos, outward))) break;
                    if (step == edge && !state.is(Trainmod.BALLAST.get())) {
                        if (placed < limit && replaceRoadbed(level, actor, pos, material)) placed++;
                        else if (state.isAir()) pending.add(pos);
                        else break;
                    }
                } else if (!ballastCanReach(level, actor, pos)) break;
                reached.add(pos);
            }
        }
        return placed;
    }

    private static List<BlockPos> traceBallastLayer(ServerLevel level, ServerPlayer actor, BlockPos center, int dx, int dz,
                                                     boolean diagonal, int depth, int crestWidth, Set<BlockPos> reached) {
        var layer = new ArrayList<BlockPos>();
        if (reached.contains(center.above()) && ballastCanReach(level, actor, center)) {
            reached.add(center);
            layer.add(center);
        }
        int stepsPerBlock = diagonal ? 2 : 1;
        int settleSteps = (depth + crestWidth) * stepsPerBlock;
        // Gravel can spill over the edge before dropping, even when the ground below that edge is solid.
        int reachSteps = settleSteps + (depth < BALLAST_DEPTH ? stepsPerBlock : 0);
        // Material can come from above or spread outward through the neighboring cell on this layer.
        for (int step = 1; step <= reachSteps; step++) {
            for (int side : new int[]{-1, 1}) {
                BlockPos pos = slopeCell(center, dx, dz, step * side, diagonal);
                BlockPos inward = slopeCell(center, dx, dz, (step - 1) * side, diagonal);
                if ((reached.contains(pos.above()) || reached.contains(inward))
                    && ballastCanReach(level, actor, pos)) {
                    reached.add(pos);
                    if (step <= settleSteps) layer.add(pos);
                }
            }
        }
        return layer;
    }

    private static BlockPos slopeCell(BlockPos center, int dx, int dz, int step, boolean diagonal) {
        if (!diagonal) return center.offset(step * dx, 0, step * dz);
        // The intermediate cell gives a face-connected path and prevents jumping a blocked diagonal corner.
        int x = (step + Integer.signum(step)) / 2;
        return center.offset(x * dx, 0, (step / 2) * dz);
    }

    private static boolean canWorkAt(ServerLevel level, ServerPlayer actor, BlockPos pos) {
        return level.isInsideBuildHeight(pos) && level.isLoaded(pos) && level.mayInteract(actor, pos);
    }

    private static boolean ballastCanReach(ServerLevel level, ServerPlayer actor, BlockPos pos) {
        if (!canWorkAt(level, actor, pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.is(Trainmod.BALLAST.get());
    }

    private static boolean retainingWall(ServerLevel level, BlockPos shoulder, BlockPos outward) {
        // A single foundation block can support an exposed shoulder. Preserve a continuous exposed wall face.
        return level.getBlockState(outward).isAir() && level.getBlockState(outward.below()).isAir()
            && level.getBlockState(outward.below(2)).isAir()
            && TrackBlock.supported(level, shoulder) && TrackBlock.supported(level, shoulder.below());
    }

    private static boolean placeShoulder(ServerLevel level, ServerPlayer actor, BlockPos pos, BlockState material) {
        if (!canWorkAt(level, actor, pos)
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
        notifyChanges(level, snapshots);
        return true;
    }

    static boolean removeTrack(ServerLevel level, Map<BlockPos, BlockState> segments) {
        // Remove every segment before notifying neighbors, so callbacks can only claim the kit once.
        for (var entry : segments.entrySet()) {
            if (level.getBlockState(entry.getKey()) != entry.getValue()) return false;
        }
        List<BlockSnapshot> snapshots = new ArrayList<>();
        int quiet = Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
        for (BlockPos pos : segments.keySet()) {
            var snapshot = BlockSnapshot.create(level.dimension(), level, pos, quiet);
            if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), quiet)) {
                rollback(snapshots, quiet);
                return false;
            }
            snapshots.add(snapshot);
        }
        notifyChanges(level, snapshots);
        return true;
    }

    private static void notifyChanges(ServerLevel level, List<BlockSnapshot> snapshots) {
        for (BlockSnapshot snapshot : snapshots) {
            BlockPos pos = snapshot.getPos();
            BlockState state = level.getBlockState(pos);
            BlockState old = snapshot.getState();
            level.markAndNotifyBlock(pos, level.getChunkAt(pos), old, state, Block.UPDATE_ALL, Block.UPDATE_LIMIT);
            if (state.getBlock() instanceof TrackBlock.Turnout turnout && turnout.panel(state) != null) {
                level.scheduleTick(pos, state.getBlock(), 1);
                RailwayBlocks.Stand.pointsChanged(level, pos);
            } else if (old.getBlock() instanceof TrackBlock.Turnout turnout && turnout.panel(old) != null) {
                RailwayBlocks.Stand.pointsChanged(level, pos);
            }
        }
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
