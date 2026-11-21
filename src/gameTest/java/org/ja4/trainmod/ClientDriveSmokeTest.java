package org.ja4.trainmod;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = Trainmod.ID, value = Dist.CLIENT)
final class ClientDriveSmokeTest {
    private enum Stage { WAIT_FOR_WORLD, WAIT_FOR_VEHICLE, FORWARD, BACKWARD, DONE }

    private static Stage stage = Stage.WAIT_FOR_WORLD;
    private static volatile int engineId = -1;
    private static volatile double originZ;
    private static volatile BlockPos trackStart;
    private static double turnaround;
    private static int drivingTicks;

    @SubscribeEvent
    static void tick(ClientTickEvent.Pre event) {
        if (!Boolean.getBoolean("trainmod.clientSmoke") || stage == Stage.DONE) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gui.screen() != null || client.getSingleplayerServer() == null) {
            return;
        }
        var server = client.getSingleplayerServer();
        switch (stage) {
            case WAIT_FOR_WORLD -> {
                stage = Stage.WAIT_FOR_VEHICLE;
                var playerId = client.player.getUUID();
                server.execute(() -> prepare(server.getPlayerList().getPlayer(playerId)));
            }
            case WAIT_FOR_VEHICLE -> {
                if (client.player.getVehicle() instanceof TrainVehicle engine && engine.getId() == engineId
                    && client.level.getBlockState(bareBallast(trackStart).east(3)).is(Trainmod.BALLAST.get())) {
                    if (!checkBallast(client)) {
                        stage = Stage.DONE;
                        LogUtils.getLogger().error("TRAINMOD CLIENT SMOKE FAILED: ballast ends or three-block join");
                        return;
                    }
                    LogUtils.getLogger().info("TRAINMOD CLIENT SMOKE PASSED: square ballast ends and turnout joins");
                    stage = Stage.FORWARD;
                }
            }
            case FORWARD, BACKWARD -> drive(client);
            case DONE -> {}
        }
    }

    private static void prepare(ServerPlayer driver) {
        var level = driver.level();
        BlockPos start = new BlockPos(driver.blockPosition().getX(), 125, driver.blockPosition().getZ());
        for (int i = 0; i < 24; i++) {
            var cell = start.south(i);
            level.getChunkAt(cell);
            RailwayGameTests.straight(level, cell, 0);
        }
        for (var kind : new TrackGeometry.Kind[]{TrackGeometry.Kind.TURNOUT_45, TrackGeometry.Kind.CURVE_MEDIUM,
                TrackGeometry.Kind.TURNOUT_PARALLEL}) {
            BlockPos root = start.offset(5 + 9 * (kind.ordinal() % 3), 0, 3);
            for (BlockPos offset : kind.shape(0, true).cells()) {
                level.getChunkAt(root.offset(offset));
            }
            RailwayGameTests.piece(level, root, kind, 0, true);
            if (kind.turnout()) {
                level.setBlockAndUpdate(root.west(), Trainmod.STAND.get().defaultBlockState());
            }
        }
        BlockPos raised = start.offset(-5, 0, 5);
        for (BlockPos rail : new BlockPos[]{raised, raised.south(), raised.south(2)}) {
            RailwayGameTests.straight(level, rail, 0);
            level.setBlockAndUpdate(rail.below(), Trainmod.BALLAST.get().defaultBlockState());
        }
        RailwayGameTests.piece(level, raised.south(3), TrackGeometry.Kind.BRIDGE_STRAIGHT, 0, true);
        BlockPos transition = raisedTransition(start);
        RailwayGameTests.straight(level, transition, 0);
        level.setBlockAndUpdate(transition.below(), Trainmod.BALLAST.get().defaultBlockState());
        level.setBlockAndUpdate(transition.south().below(), Trainmod.BALLAST.get().defaultBlockState());
        BlockPos bare = bareBallast(start);
        level.setBlockAndUpdate(bare, Trainmod.BALLAST.get().defaultBlockState());
        level.setBlockAndUpdate(bare.east(3), Trainmod.BALLAST.get().defaultBlockState());
        BlockPos switchEntry = raisedSwitch(start);
        for (BlockPos turnout : new BlockPos[]{switchEntry, switchEntry.east(3)}) {
            RailwayGameTests.piece(level, turnout, TrackGeometry.Kind.TURNOUT_45, 0, true);
            level.setBlockAndUpdate(turnout.below(), Trainmod.BALLAST.get().defaultBlockState());
        }
        trackStart = start;
        TrainVehicle engine = RailwayGameTests.vehicle(level, TrainVehicle.Kind.LOCOMOTIVE, start.south(4));
        engine.supplies.setItem(0, new net.minecraft.world.item.ItemStack(Trainmod.DIESEL_BUCKET.get()));
        originZ = engine.getZ();
        engineId = engine.getId();
        Vec3 p = engine.position();
        driver.teleportTo(p.x, p.y + 0.1, p.z);
        if (!driver.startRiding(engine)) {
            throw new IllegalStateException("Client smoke test could not board the locomotive");
        }
    }

    private static BlockPos bareBallast(BlockPos start) { return start.offset(-12, -1, 8); }

    private static BlockPos raisedSwitch(BlockPos start) { return start.offset(-12, 0, 15); }

    private static BlockPos raisedTransition(BlockPos start) { return start.offset(-6, 0, -4); }

    private static boolean checkBallast(Minecraft client) {
        BlockPos raised = trackStart.offset(-5, 0, 5);
        return hasBallastVertex(client, trackStart, p -> p.z() < -0.99 && p.x() > 1.99)
            && !hasBallastVertex(client, trackStart.south(), p -> p.z() < -0.02 || p.z() > 1.02)
            && hasBallastVertex(client, raised, p -> p.z() < -0.99 && p.x() > 1.99 && p.y() < -0.9)
            && !hasBallastVertex(client, raised.south(), p -> p.z() < -0.02 || p.z() > 1.02)
            && hasBallastVertex(client, raised.south(2), p -> p.z() > 1.99 && p.y() < -0.99)
            && hasBallastVertex(client, bareBallast(trackStart), p -> p.x() < 0.001 && p.z() < 0.001 && p.y() > 0.99)
            && hasRailCap(client, trackStart, net.minecraft.core.Direction.NORTH)
            && !hasRailCap(client, raised.south(2), net.minecraft.core.Direction.SOUTH)
            && hasRailCap(client, raised.south(3), net.minecraft.core.Direction.SOUTH)
            && hasBallastJoin(client, bareBallast(trackStart))
            && hasBallastJoin(client, raisedSwitch(trackStart).below())
            && hasBallastTransition(client, raisedTransition(trackStart))
            && gravelQuads(client, raised).stream().allMatch(q -> q.materialInfo().shadeDirectionOverride() == net.minecraft.core.Direction.UP)
            && !blockParts(client, bareBallast(trackStart)).getFirst().getQuads(net.minecraft.core.Direction.UP).isEmpty();
    }

    private static boolean hasBallastTransition(Minecraft client, BlockPos pos) {
        return hasBallastVertex(client, pos, p -> Math.abs(p.z() - 1.5) < 0.001
                && Math.abs(p.x() - 0.5) < 0.001 && Math.abs(p.y() - BallastProfile.CREST / 2) < 0.001)
            && hasBallastVertex(client, pos, p -> Math.abs(p.z() - 2) < 0.001
                && Math.abs(p.x() - 0.5) < 0.001 && Math.abs(p.y()) < 0.001);
    }

    private static boolean hasBallastJoin(Minecraft client, BlockPos pos) {
        return hasBallastVertex(client, pos, p -> Math.abs(p.x() - 2) < 0.001
            && Math.abs(p.z() - 0.5) < 0.001 && p.y() > 0.8);
    }

    private static boolean hasBallastVertex(Minecraft client, BlockPos pos,
                                             java.util.function.Predicate<org.joml.Vector3fc> test) {
        return gravelQuads(client, pos).stream().anyMatch(quad -> {
            for (int i = 0; i < 4; i++) if (test.test(quad.position(i))) return true;
            return false;
        });
    }

    private static java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> gravelQuads(Minecraft client, BlockPos pos) {
        return blockParts(client, pos).stream().flatMap(part -> java.util.stream.Stream.concat(
            part.getQuads(null).stream(), part.getQuads(net.minecraft.core.Direction.UP).stream()))
            .filter(quad -> quad.materialInfo().sprite().contents().name().getPath().equals("block/gravel")).toList();
    }

    private static boolean hasRailCap(Minecraft client, BlockPos pos, net.minecraft.core.Direction face) {
        return blockParts(client, pos).stream().flatMap(part -> part.getQuads(null).stream())
            .anyMatch(quad -> quad.direction() == face
                && quad.materialInfo().sprite().contents().name().getPath().equals("block/steel_head")
                && Math.abs(quad.position0().z() - quad.position2().z()) < 1e-6);
    }

    private static ArrayList<BlockStateModelPart> blockParts(Minecraft client, BlockPos pos) {
        var state = client.level.getBlockState(pos);
        var model = client.getModelManager().getBlockStateModelSet().get(state);
        var parts = new ArrayList<BlockStateModelPart>();
        model.collectParts(client.level, pos, state, RandomSource.create(0), parts);
        return parts;
    }

    private static void drive(Minecraft client) {
        if (++drivingTicks > 220 || !(client.player.getVehicle() instanceof TrainVehicle engine)) {
            finish(client, false);
            return;
        }
        client.options.keyUp.setDown(stage == Stage.FORWARD);
        client.options.keyDown.setDown(stage == Stage.BACKWARD);
        if (stage == Stage.FORWARD && engine.getZ() > originZ + 2.0) {
            turnaround = engine.getZ();
            stage = Stage.BACKWARD;
        } else if (stage == Stage.BACKWARD && engine.getZ() < turnaround - 1.5) {
            finish(client, true);
        }
    }

    private static void finish(Minecraft client, boolean passed) {
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        stage = Stage.DONE;
        if (passed) {
            LogUtils.getLogger().info("TRAINMOD CLIENT SMOKE PASSED: forward and reverse via normal client key mappings");
        } else {
            LogUtils.getLogger().error("TRAINMOD CLIENT SMOKE FAILED: expected forward and reverse cab movement");
        }
    }
}
