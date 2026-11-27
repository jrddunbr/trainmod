package org.ja4.trainmod;

import com.mojang.logging.LogUtils;
import java.util.Set;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = Trainmod.ID, value = Dist.CLIENT)
final class ClientRailPowerSmokeTest {
    private static volatile BlockPos yard;
    private static volatile int shunterId;
    private static int phase;
    private static int ticks;
    private static int remaining = 600;
    private static boolean started;

    @SubscribeEvent
    static void tick(ClientTickEvent.Pre event) {
        if (!"railPower".equals(System.getProperty("trainmod.clientAdditionsSmoke"))) return;
        var client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        if (client.player == null || client.level == null || server == null || client.gui.screen() != null) return;
        var playerId = client.player.getUUID();
        if (!started) {
            started = true;
            client.options.pauseOnLostFocus = false;
            client.options.renderDistance().set(4);
            server.execute(() -> prepare(server.getPlayerList().getPlayer(playerId)));
        }
        if (--remaining == 0) {
            LogUtils.getLogger().error("TRAINMOD RAIL POWER CLIENT FAILED in phase {}", phase);
            client.stop();
        }
        if (yard == null || !(client.level.getEntity(shunterId) instanceof TrainVehicle train)
            || !(client.level.getBlockEntity(bankPos(yard)) instanceof TransformerBank.Bank bank)) return;
        var faces = CatenaryOverlayRenderer.visibleFaces(client);
        switch (phase) {
            case 0 -> {
                if (!bank.live() || Math.abs(bank.meterFraction() - 0.5) > 0.01 || !hasColor(faces, CatenaryOverlayRenderer.POWERED)
                    || !hasColor(faces, CatenaryOverlayRenderer.MISSING) || !hasColor(faces, CatenaryOverlayRenderer.CONNECTED)
                    || bank.transmission() == null || !showsOffscreenCable(client) || ++ticks < 30) return;
                capture(client, "rail-power-overlay-and-bank.png");
                LogUtils.getLogger().info("TRAINMOD RAIL POWER CLIENT PASSED off-screen transmission wire clipped to render range");
                LogUtils.getLogger().info("TRAINMOD RAIL POWER CLIENT PASSED circuit dial, lamp, transmission feeder and green/orange/red offhand overlay");
                ticks = 0;
                phase++;
                server.execute(() -> {
                    var player = server.getPlayerList().getPlayer(playerId);
                    var shunter = player.level().getEntity(shunterId);
                    player.getAbilities().flying = false;
                    player.onUpdateAbilities();
                    player.teleportTo(player.level(), shunter.getX(), shunter.getY() + 1, shunter.getZ(), Set.of(), 45, 10, true);
                    player.startRiding(shunter);
                });
            }
            case 1 -> {
                if (client.player.getVehicle() != train) return;
                if (++ticks == 1) client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                if (ticks < 14 || !touchesGantry(client, train)) return;
                capture(client, "rail-power-raised-pantographs.png");
                LogUtils.getLogger().info("TRAINMOD RAIL POWER CLIENT PASSED both animated shoes touch the sloping gantry wire");
                ticks = 0;
                phase++;
                server.execute(() -> {
                    var player = server.getPlayerList().getPlayer(playerId);
                    player.stopRiding();
                    ((TransformerBank.Bank) player.level().getBlockEntity(bankPos(yard))).energy.set(0);
                    viewYard(player, yard);
                });
            }
            case 2 -> {
                client.options.setCameraType(CameraType.FIRST_PERSON);
                if (bank.live() || bank.meterFraction() != 0 || !hasColor(faces, CatenaryOverlayRenderer.CONNECTED)
                    || !hasColor(faces, CatenaryOverlayRenderer.MISSING) || hasColor(faces, CatenaryOverlayRenderer.POWERED)
                    || Math.abs(train.pantographHeight(0, 1) - Pantographs.folded(0)) > 0.01
                    || Math.abs(train.pantographHeight(1, 1) - Pantographs.folded(1)) > 0.01 || ++ticks < 10) return;
                capture(client, "rail-power-depleted.png");
                LogUtils.getLogger().info("TRAINMOD RAIL POWER CLIENT PASSED depleted meter, dark lamp, orange wire and folded pantographs");
                ticks = 0;
                phase++;
                server.execute(() -> {
                    var player = server.getPlayerList().getPlayer(playerId);
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.TRANSFORMER_BANK.get().asItem()));
                    BlockPos target = previewPos(yard);
                    player.level().setBlockAndUpdate(target.below(), Blocks.STONE.defaultBlockState());
                    player.teleportTo(player.level(), target.getX() - 3, target.getY() + 0.4, target.getZ() + 0.5,
                        Set.of(), -90, 30, true);
                });
            }
            case 3 -> {
                var stack = client.player.getMainHandItem();
                if (!(stack.getItem() instanceof TransformerBank.Kit kit) || !(client.hitResult instanceof BlockHitResult hit)
                    || !hit.getBlockPos().equals(previewPos(yard).below())) return;
                var context = new BlockPlaceContext(client.player, InteractionHand.MAIN_HAND, stack, hit);
                if (!hasColor(PlacementGhosts.transformerPreview(kit, context), PlacementGhosts.TRANSFORMER) || ++ticks < 5) return;
                capture(client, "rail-power-transformer-preview.png");
                LogUtils.getLogger().info("TRAINMOD RAIL POWER CLIENT PASSED green transformer placement preview");
                phase++;
            }
            case 4 -> {
                if (++ticks >= 24) client.stop();
            }
        }
    }

    private static boolean showsOffscreenCable(Minecraft client) {
        BlockPos local = TransmissionExtension.tip(yard.offset(7, 0, 10));
        BlockPos remote = TransmissionExtension.tip(yard.offset(7, 0, 138));
        if (client.level.isLoaded(remote) || !(client.level.getBlockEntity(local) instanceof TransmissionExtension.Tip tip)
            || !tip.neighbors().contains(remote)) return false;
        Vec3 camera = client.player.position();
        Vec3 terminal = TransmissionExtension.terminal(local);
        double range = 16.0 * client.options.getEffectiveRenderDistance();
        return RailPowerRenderer.visibleTransmission(client.level, camera, range).stream().anyMatch(cable ->
            cable.from().distanceToSqr(terminal) < 1e-8 && cable.to().z > terminal.z + 20
                && cable.to().z < TransmissionExtension.terminal(remote).z
                && Math.abs(cable.to().subtract(camera).horizontalDistance() - range) < 1e-5);
    }

    private static boolean hasColor(java.util.List<PlacementGhosts.Quad> faces, int color) {
        return faces.stream().anyMatch(face -> face.color() == color);
    }

    private static boolean touchesGantry(Minecraft client, TrainVehicle train) {
        var mount = yard.north(5).above(3);
        var attachment = Catenary.attachments(client.level, mount, client.level.getBlockState(mount)).getFirst();
        var wire = Catenary.spansFrom(client.level, attachment, 1).getFirst().wire();
        for (int head = 0; head < 2; head++) {
            var shoe = train.renderFrame(1).world(new Vec3(0, train.pantographHeight(head, 1), Pantographs.longitudinal(head)));
            double best = Double.POSITIVE_INFINITY;
            for (int i = 1; i < wire.size(); i++) {
                Vec3 start = wire.get(i - 1), delta = wire.get(i).subtract(start);
                double along = Math.clamp(shoe.subtract(start).dot(delta) / delta.lengthSqr(), 0, 1);
                best = Math.min(best, start.add(delta.scale(along)).distanceToSqr(shoe));
            }
            if (best > 0.0036) return false;
        }
        return true;
    }

    private static BlockPos previewPos(BlockPos root) { return root.north().west(3); }

    private static BlockPos bankPos(BlockPos root) { return root.north(3).west(3); }

    private static void prepare(ServerPlayer player) {
        var level = player.level();
        var root = player.blockPosition().east(12).south(12).atY(144);
        for (int x = -7; x <= 9; x++) {
            for (int z = -7; z <= 14; z++) {
                level.setBlockAndUpdate(root.offset(x, -2, z), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        CatenaryPowerGameTest.straightLine(level, root, -5, -1);
        RailwayGameTests.piece(level, root, TrackGeometry.Kind.GRADE_6, 0, true);
        CatenaryPowerGameTest.straightLine(level, root.above(), 7, 13);
        CatenaryPowerGameTest.pole(level, root.north(5).above(3), Trainmod.CATENARY_GANTRY.get(), Direction.NORTH);
        CatenaryPowerGameTest.gantry(level, root.south(9).above());
        CatenaryPowerGameTest.straightLine(level, root.east(6), -5, 13);
        for (int z = 10; z <= 138; z += 16) {
            var pos = root.offset(7, 0, z);
            ChunkPos chunk = ChunkPos.containing(pos);
            level.setChunkForced(chunk.x(), chunk.z(), true);
            level.getChunkAt(pos);
        }
        for (int z : new int[]{-4, 10, 138}) {
            var pos = root.offset(7, 0, z);
            CatenaryPowerGameTest.pole(level, pos, Trainmod.CATENARY_SINGLE.get(), Direction.WEST);
            TransmissionExtension.parts(pos).forEach(level::setBlockAndUpdate);
        }
        var bank = CatenaryPowerGameTest.installBank(level, bankPos(root), Direction.EAST);
        bank.energy.set(32_000);
        CatenaryPowerGameTest.installGenerator(level, bankPos(root).west(2));
        var shunter = RailwayGameTests.vehicle(level, TrainVehicle.Kind.ELECTRIC_SHUNTER, root.south(2));
        shunterId = shunter.getId();
        yard = root;
        player.setGameMode(GameType.CREATIVE);
        player.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND, Trainmod.TRANSMISSION_KIT.toStack());
        viewYard(player, root);
    }

    private static void viewYard(ServerPlayer player, BlockPos root) {
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.teleportTo(player.level(), root.getX() - 6.5, root.getY() + 1.7, root.getZ() - 6.5,
            Set.of(), -58, 12, true);
    }

    private static void capture(Minecraft client, String name) {
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1,
            message -> LogUtils.getLogger().info("{}", message.getString()));
    }
}
