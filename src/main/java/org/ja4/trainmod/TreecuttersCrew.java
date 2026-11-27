package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

final class TreecuttersCrew {
    private static final int CUT_RADIUS = 6;
    private static final int LEAF_RADIUS = 2;
    private static final int WORKER_RANGE = 24;
    private static final int WALK_TICKS = 60;
    private static final double AXE_REACH_SQR = 2.5 * 2.5;
    private static final int LONG_REACH = 6;
    private static final int TEAM_RANGE = 16;

    private record Job(BlockPos pos, TrainVehicle anchor, boolean ballastLeaf) {}

    private final Map<BlockPos, Long> retryAfter = new HashMap<>();
    private final Map<BlockPos, Job> pending = new LinkedHashMap<>();
    private final Map<UUID, Long> pickupRetryAfter = new HashMap<>();
    private List<TrainVehicle> homeTrain = List.of();
    private Set<TrainVehicle> workTrain = Set.of();
    private Set<BlockPos> claimedLogs = Set.of();
    private TreecuttersWorker worker;
    private Job job;
    private ItemEntity pickup;
    private long nextSearch;
    private long walkStarted;

    void tick(ServerLevel level, TrainVehicle car, ServerPlayer actor) {
        long now = level.getGameTime();
        boolean search = now >= nextSearch;
        if (search) {
            nextSearch = now + 5;
            refreshWorkTrain(level, car);
            retryAfter.values().removeIf(time -> time <= now);
            pickupRetryAfter.values().removeIf(time -> time <= now);
            pending.values().removeIf(candidate -> !valid(level, candidate));
            findJobs(level);
        }
        if (job != null && !valid(level, job)) finishJob(now);
        if (worker == null || !worker.isAlive() || outOfRange(car)) {
            discardWorker();
            worker = new TreecuttersWorker(Trainmod.TREECUTTERS_WORKER.get(), level);
            worker.setPos(car.position().add(0, 0.4, 0));
            worker.setYRot(car.getYRot());
            if (!level.addFreshEntity(worker)) {
                worker = null;
                return;
            }
            if (worker.startRiding(car, true, true) && (job != null || pickup != null)) worker.stopRiding();
            walkStarted = now;
        }

        if (job == null && pickup == null) {
            takeJob(level, now);
            if (job == null && search) pickup = findPickup(level, car);
            if (job != null || pickup != null) {
                worker.stopRiding();
                worker.getNavigation().stop();
                walkStarted = now;
            }
        }
        if (job != null) workAtTree(level, car, actor, now);
        else if (pickup != null) pickUp(level, car, now);
        else returnToCar(car, now);
    }

    private boolean outOfRange(TrainVehicle car) {
        if (worker.distanceToSqr(car) <= WORKER_RANGE * WORKER_RANGE) return false;
        var range = job != null ? workTrain : pickup != null ? homeTrain : List.<TrainVehicle>of();
        return range.stream().noneMatch(stock -> worker.distanceToSqr(stock) <= WORKER_RANGE * WORKER_RANGE);
    }

    private void refreshWorkTrain(ServerLevel level, TrainVehicle car) {
        homeTrain = car.loadedTrain(level);
        var working = new LinkedHashSet<>(homeTrain);
        var checked = new HashSet<>(homeTrain);
        for (TrainVehicle stock : homeTrain) {
            for (TrainVehicle neighbor : level.getEntitiesOfClass(TrainVehicle.class, stock.getBoundingBox().inflate(TEAM_RANGE),
                candidate -> !checked.contains(candidate) && stock.distanceToSqr(candidate) <= TEAM_RANGE * TEAM_RANGE)) {
                if (checked.contains(neighbor)) continue;
                var other = neighbor.loadedTrain(level);
                checked.addAll(other);
                if (other.stream().anyMatch(TrainVehicle::treecuttersActive)) working.addAll(other);
            }
        }
        workTrain = working;
    }

    private ItemEntity findPickup(ServerLevel level, TrainVehicle car) {
        var cargo = car.connectedGondolaCargo(level);
        if (cargo.isEmpty()) return null;
        return homeTrain.stream().flatMap(stock -> level.getEntitiesOfClass(ItemEntity.class, stock.getBoundingBox().inflate(CUT_RADIUS),
            item -> item.onGround() && !item.hasPickUpDelay() && !pickupRetryAfter.containsKey(item.getUUID())
                && stock.distanceToSqr(item) <= CUT_RADIUS * CUT_RADIUS && canCollect(cargo, item.getItem())).stream())
            .min(Comparator.comparingDouble(worker::distanceToSqr)).orElse(null);
    }

    private static boolean canCollect(List<SimpleContainer> cargo, ItemStack stack) {
        return (stack.is(ItemTags.LOGS) || stack.is(ItemTags.SAPLINGS) || stack.is(Items.STICK) || stack.is(Items.APPLE))
            && cargo.stream().anyMatch(inventory -> inventory.canAddItem(stack));
    }

    private void pickUp(ServerLevel level, TrainVehicle car, long now) {
        var cargo = car.connectedGondolaCargo(level);
        if (pickup.isRemoved() || !canCollect(cargo, pickup.getItem())
            || homeTrain.stream().noneMatch(stock -> stock.distanceToSqr(pickup) <= CUT_RADIUS * CUT_RADIUS)) {
            finishPickup(now);
            return;
        }
        Vec3 itemPos = pickup.position().add(0, pickup.getBbHeight() / 2, 0);
        if (pickup.onGround() && !pickup.hasPickUpDelay() && worker.distanceToSqr(pickup) <= 2 * 2
            && level.clip(new ClipContext(worker.getEyePosition(), itemPos, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, worker)).getType() == HitResult.Type.MISS) {
            var inventory = new WorkCargo();
            ItemStack remaining = inventory.insert(cargo, pickup.getItem());
            int count = pickup.getItem().getCount() - remaining.getCount();
            inventory.commit();
            worker.take(pickup, count);
            if (remaining.isEmpty()) pickup.discard();
            else pickup.setItem(remaining);
            finishPickup(now);
        } else if (now - walkStarted >= WALK_TICKS) {
            pickupRetryAfter.put(pickup.getUUID(), now + 100);
            finishPickup(now);
        } else if ((now - walkStarted) % 10 == 0) {
            worker.getNavigation().moveTo(pickup, 0.85);
        }
    }

    private void finishPickup(long now) {
        worker.getNavigation().stop();
        pickup = null;
        walkStarted = now;
    }

    private boolean valid(ServerLevel level, Job candidate) {
        TrainVehicle anchor = candidate.anchor;
        BlockPos pos = candidate.pos;
        if (!anchor.isAlive() || !workTrain.contains(anchor) || !level.isLoaded(pos)) return false;
        if (candidate.ballastLeaf) return anchor.needsBallastLeaf(pos) && level.getBlockState(pos).is(BlockTags.LEAVES);
        Vec3 offset = Vec3.atCenterOf(pos).subtract(anchor.position());
        return ConnectedLogs.isLog(level, pos) && offset.horizontalDistanceSqr() <= WORKER_RANGE * WORKER_RANGE
            && !underRoof(level, anchor.blockPosition(), anchor.getBlockY() + CUT_RADIUS)
            && !coveredTrack(level, pos, anchor.getBlockY());
    }

    private static boolean underRoof(ServerLevel level, BlockPos base, int lastY) {
        for (int y = base.getY() + 1; y <= lastY && level.isInsideBuildHeight(y); y++) {
            BlockPos pos = base.atY(y);
            if (isRoof(level, pos, level.getBlockState(pos))) return true;
        }
        return false;
    }

    private static boolean isRoof(ServerLevel level, BlockPos pos, BlockState state) {
        return !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES) && !state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean coveredTrack(ServerLevel level, BlockPos log, int railY) {
        for (int y = Math.min(railY + 1, log.getY() - 1); y >= railY - 1; y--) {
            BlockPos pos = log.atY(y);
            if (RailNetwork.isRail(level.getBlockState(pos))) return underRoof(level, pos, log.getY() - 1);
        }
        return false;
    }

    private void findJobs(ServerLevel level) {
        var scanned = new HashSet<BlockPos>();
        var overhead = new HashSet<BlockPos>();
        for (TrainVehicle stock : workTrain) {
            for (BlockPos leaf : stock.ballastLeaves(level)) {
                if (!retryAfter.containsKey(leaf)) pending.putIfAbsent(leaf, new Job(leaf, stock, true));
            }
            BlockPos center = stock.blockPosition();
            if (!underRoof(level, center, center.getY() + CUT_RADIUS)) {
                findTrees(level, stock, center, scanned, overhead);
            }
        }
    }

    private void findTrees(ServerLevel level, TrainVehicle stock, BlockPos center, Set<BlockPos> scanned, Set<BlockPos> overhead) {
        for (int dx = -CUT_RADIUS; dx <= CUT_RADIUS; dx++) {
            for (int dz = -CUT_RADIUS; dz <= CUT_RADIUS; dz++) {
                if (dx * dx + dz * dz > CUT_RADIUS * CUT_RADIUS) continue;
                BlockPos column = center.offset(dx, 0, dz);
                if (!level.isLoaded(column)) continue;
                int height = (int) Math.sqrt(CUT_RADIUS * CUT_RADIUS - dx * dx - dz * dz);
                for (int dy = -height; dy <= height; dy++) {
                    BlockPos pos = column.above(dy);
                    if (scanned.add(pos) && ConnectedLogs.isLog(level, pos)) queueLog(level, pos, stock);
                }
                if (!overhead.add(column)) continue;
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
                for (int y = column.getY() + 1; y < surface && level.isInsideBuildHeight(y); y++) {
                    BlockPos pos = column.atY(y);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(BlockTags.LOGS)) {
                        queueLog(level, pos, stock);
                        break;
                    }
                    if (isRoof(level, pos, state)) break;
                }
            }
        }
    }

    private void queueLog(ServerLevel level, BlockPos pos, TrainVehicle stock) {
        if (!claimedLogs.contains(pos) && !retryAfter.containsKey(pos) && !pending.containsKey(pos)
            && !ConnectedLogs.supportsTrack(level, pos) && !coveredTrack(level, pos, stock.getBlockY())) {
            pending.put(pos, new Job(pos, stock, false));
        }
    }

    private void takeJob(ServerLevel level, long now) {
        var others = workTrain.stream().map(TrainVehicle::activeTreecuttersCrew)
            .filter(crew -> crew != null && crew != this && crew.job != null).toList();
        var candidates = pending.values().stream()
            .filter(candidate -> !retryAfter.containsKey(candidate.pos))
            .filter(candidate -> others.stream().noneMatch(crew -> crew.job.pos.equals(candidate.pos)
                || crew.claimedLogs.contains(candidate.pos)))
            .sorted(Comparator.<Job, Boolean>comparing(candidate -> !candidate.ballastLeaf)
                .thenComparingDouble(candidate -> worker.distanceToSqr(Vec3.atCenterOf(candidate.pos)))).toList();
        for (Job candidate : candidates) {
            pending.remove(candidate.pos);
            if (!valid(level, candidate)) continue;
            var logs = new LinkedHashSet<BlockPos>();
            if (!candidate.ballastLeaf && !ConnectedLogs.follow(level, List.of(candidate.pos),
                log -> !coveredTrack(level, log, candidate.anchor.getBlockY()), logs)) {
                retryAfter.put(candidate.pos, now + 100);
                continue;
            }
            if (others.stream().anyMatch(crew -> logs.stream().anyMatch(crew.claimedLogs::contains))) continue;
            job = candidate;
            claimedLogs = logs;
            return;
        }
    }

    private void workAtTree(ServerLevel level, TrainVehicle car, ServerPlayer actor, long now) {
        BlockPos pos = job.pos;
        boolean vertical = verticalReach(level, pos);
        boolean longReach = job.ballastLeaf || now - walkStarted >= 20;
        if (!vertical && !canReach(level, worker.getEyePosition(), pos, longReach)) {
            if (now - walkStarted < WALK_TICKS) {
                if ((now - walkStarted) % 10 == 0) {
                    walkToTree(level, pos, job.anchor.getBlockY());
                }
                return;
            }
            if (!teleportBeside(level, pos)) {
                retryAfter.put(pos, now + 100);
                finishJob(now);
                return;
            }
        }
        if (!vertical) worker.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
        worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
        boolean done = job.ballastLeaf ? clearBallastLeaf(level, car, actor, pos, worker.getMainHandItem())
            : fell(level, car, actor, pos, job.anchor.getBlockY(), worker.getMainHandItem());
        if (!done) retryAfter.put(pos, now + 100);
        finishJob(now);
    }

    private void finishJob(long now) {
        if (worker != null) worker.getNavigation().stop();
        job = null;
        claimedLogs = Set.of();
        walkStarted = now;
    }

    private boolean verticalReach(ServerLevel level, BlockPos pos) {
        if (!worker.onGround() || worker.getBlockX() != pos.getX() || worker.getBlockZ() != pos.getZ()) return false;
        if (pos.getY() > worker.getEyeY()) return !underRoof(level, worker.blockPosition(), pos.getY() - 1);
        return pos.getY() < worker.getBlockY() && !underRoof(level, pos, worker.getBlockY() - 1);
    }

    private void walkToTree(ServerLevel level, BlockPos log, int railY) {
        var navigation = worker.getNavigation();
        if (job.ballastLeaf && Math.abs(worker.getEyeY() - log.getY()) <= 2.5
            && navigation.moveTo(navigation.createPath(log, 4), 0.85)) return;
        for (int y = Math.min(log.getY() - 2, railY + CUT_RADIUS); y >= railY - CUT_RADIUS; y--) {
            BlockPos belowLog = log.atY(y);
            if (!canStand(level, belowLog) || underRoof(level, belowLog, log.getY() - 1)) continue;
            var path = navigation.createPath(belowLog, 0);
            if (path != null && path.canReach()) {
                navigation.moveTo(path, 0.85);
                return;
            }
            break;
        }
        Set<BlockPos> destinations = new LinkedHashSet<>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos next = log.relative(side);
            for (int dy = 0; dy >= -CUT_RADIUS; dy--) {
                BlockPos pos = next.offset(0, dy, 0);
                if (canStand(level, pos)) {
                    destinations.add(pos);
                    break;
                }
            }
        }
        var path = destinations.isEmpty() ? null : navigation.createPath(destinations, 0);
        navigation.moveTo(path != null ? path : navigation.createPath(log, 1), 0.85);
    }

    private static boolean canStand(ServerLevel level, BlockPos pos) {
        return level.isInsideBuildHeight(pos) && level.isLoaded(pos) && TrackBlock.supported(level, pos)
            && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
            && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
    }

    private boolean canReach(ServerLevel level, Vec3 eyes, BlockPos log, boolean longReach) {
        Vec3 target = Vec3.atCenterOf(log);
        Vec3 offset = target.subtract(eyes);
        if (offset.lengthSqr() > AXE_REACH_SQR
            && (!longReach || Math.abs(offset.y) > 2.5 || offset.horizontalDistanceSqr() > LONG_REACH * LONG_REACH)) return false;
        var hit = level.clip(new ClipContext(eyes, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, worker) {
            @Override
            public net.minecraft.world.phys.shapes.VoxelShape getBlockShape(BlockState state, BlockGetter getter, BlockPos pos) {
                return !pos.equals(log) && naturalLeaves(state) ? Shapes.empty() : super.getBlockShape(state, getter, pos);
            }
        });
        return hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(log);
    }

    private boolean teleportBeside(ServerLevel level, BlockPos log) {
        for (int dy : new int[]{-1, 0, 1}) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos pos = log.relative(side).offset(0, dy, 0);
                Vec3 at = Vec3.atBottomCenterOf(pos);
                if (level.isInWorldBounds(pos) && level.isLoaded(pos) && level.getFluidState(pos).isEmpty()
                    && canReach(level, worker.getEyePosition().add(at.subtract(worker.position())), log, false)
                    && level.noCollision(worker, worker.getBoundingBox().move(at.subtract(worker.position())))) {
                    worker.teleportTo(at.x, at.y, at.z);
                    worker.setDeltaMovement(Vec3.ZERO);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean fell(ServerLevel level, TrainVehicle car, ServerPlayer actor, BlockPos root, int railY, ItemStack axe) {
        Set<BlockPos> logs = new LinkedHashSet<>();
        if (!ConnectedLogs.follow(level, List.of(root), log -> !coveredTrack(level, log, railY), logs)) return false;
        for (BlockPos log : logs) {
            if (!canCut(level, actor, log, level.getBlockState(log))) return false;
        }
        Set<BlockPos> leaves = new HashSet<>();
        for (BlockPos log : logs) {
            for (BlockPos pos : leafNeighborhood(log)) {
                if (level.isInWorldBounds(pos) && level.isLoaded(pos) && naturalLeaves(level.getBlockState(pos))) {
                    leaves.add(pos.immutable());
                }
            }
        }
        leaves.removeIf(pos -> nearStandingLog(level, pos, logs) || coveredTrack(level, pos, railY)
            || !canCut(level, actor, pos, level.getBlockState(pos)));
        logs.addAll(leaves);
        return harvest(level, car, actor, root,
            logs.stream().sorted(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed()).toList(), axe);
    }

    private static Iterable<BlockPos> leafNeighborhood(BlockPos pos) {
        return BlockPos.betweenClosed(pos.offset(-LEAF_RADIUS, -LEAF_RADIUS, -LEAF_RADIUS),
            pos.offset(LEAF_RADIUS, LEAF_RADIUS, LEAF_RADIUS));
    }

    private static boolean nearStandingLog(ServerLevel level, BlockPos leaf, Set<BlockPos> cutLogs) {
        for (BlockPos pos : leafNeighborhood(leaf)) {
            if (!cutLogs.contains(pos) && ConnectedLogs.isLog(level, pos)) return true;
        }
        return false;
    }

    private static boolean naturalLeaves(BlockState state) {
        return state.is(BlockTags.LEAVES) && !state.getOptionalValue(BlockStateProperties.PERSISTENT).orElse(false);
    }

    private static boolean canCut(ServerLevel level, ServerPlayer actor, BlockPos pos, BlockState state) {
        return !ConnectedLogs.supportsTrack(level, pos) && !state.hasBlockEntity() && state.getDestroySpeed(level, pos) >= 0
            && TrackWork.mayBreak(level, actor, pos, state);
    }

    private static boolean clearBallastLeaf(ServerLevel level, TrainVehicle car, ServerPlayer actor, BlockPos pos, ItemStack axe) {
        BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.LEAVES) && canCut(level, actor, pos, state) && harvest(level, car, actor, pos, List.of(pos), axe);
    }

    private static boolean harvest(ServerLevel level, TrainVehicle car, ServerPlayer actor, BlockPos root,
                                   List<BlockPos> blocks, ItemStack axe) {
        Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
        var cargo = car.connectedGondolaCargo(level);
        var uncollected = new ArrayList<ItemStack>();
        var inventory = new WorkCargo();
        for (BlockPos pos : blocks) {
            BlockState state = level.getBlockState(pos);
            for (ItemStack drop : Block.getDrops(state, level, pos, null, actor, axe)) {
                ItemStack remaining = inventory.insert(cargo, drop);
                if (!remaining.isEmpty()) uncollected.add(remaining);
            }
            changes.put(pos, Blocks.AIR.defaultBlockState());
        }
        BlockState trunk = level.getBlockState(root);
        if (!TrackWork.apply(level, actor, changes, trunk)) return false;
        inventory.commit();
        uncollected.forEach(drop -> Block.popResource(level, root, drop));
        level.levelEvent(2001, root, Block.getId(trunk));
        return true;
    }

    private void returnToCar(TrainVehicle car, long now) {
        if (worker.getVehicle() == car) return;
        if (worker.distanceToSqr(car) <= 2 * 2 || now - walkStarted >= WALK_TICKS) {
            worker.getNavigation().stop();
            worker.startRiding(car, true, true);
        } else if ((now - walkStarted) % 10 == 0) {
            worker.getNavigation().moveTo(car, 0.85);
        }
    }

    void discardWorker() {
        if (worker != null && !worker.isRemoved()) worker.discard();
        worker = null;
    }

    void reset() {
        discardWorker();
        job = null;
        claimedLogs = Set.of();
        homeTrain = List.of();
        workTrain = Set.of();
        pickup = null;
        pending.clear();
        retryAfter.clear();
        pickupRetryAfter.clear();
        nextSearch = 0;
    }
}
