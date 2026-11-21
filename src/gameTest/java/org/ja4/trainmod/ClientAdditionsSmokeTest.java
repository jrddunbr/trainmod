package org.ja4.trainmod;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = Trainmod.ID, value = Dist.CLIENT)
final class ClientAdditionsSmokeTest {
    private static final String MODE = System.getProperty("trainmod.clientAdditionsSmoke");
    private static final UUID BORE_ID = UUID.fromString("44420000-0c8f-4000-8000-000000000001");
    private static final BlockPos BORE_TRACK = new BlockPos(10248, 112, 10240);
    private static final Set<String> sounds = new HashSet<>();
    private static final SoundEventListener LISTENER = (sound, event, range) -> {
        if (sound.getIdentifier().getNamespace().equals(Trainmod.ID)) sounds.add(sound.getIdentifier().getPath());
    };

    private enum Boarding { SELECT_ENGINE, SELECT_TENDER, WAIT_FOR_LINK, SUPPLIES, WAIT_FOR_MENU, BOARD, HOLD, DONE }

    private static Boarding boarding = Boarding.SELECT_ENGINE;
    private static int boardingTicks;
    private static int heldSneak;
    private static boolean initialized;
    private static boolean done;
    private static volatile int steamId;
    private static volatile int tenderId;
    private static volatile int steamEnd = 1;
    private static volatile int tenderEnd;
    private static volatile int dieselId;
    private static volatile UUID boreCarId;
    private static volatile BlockPos yard;
    private static int ticks;
    private static float firstPhase;
    private static double firstZ;
    private static boolean hornHeld;
    private static boolean hornReleased;
    private static boolean bellLatched;

    @SubscribeEvent
    static void tick(ClientTickEvent.Pre event) {
        if (MODE == null || done) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getSingleplayerServer() == null) return;
        if (!initialized && client.gui.screen() != null) return;
        var server = client.getSingleplayerServer();
        UUID playerId = client.player.getUUID();
        if (!initialized) {
            initialized = true;
            if (!MODE.equals("reload")) {
                client.getSoundManager().addListener(LISTENER);
                server.execute(() -> {
                    var player = server.getPlayerList().getPlayer(playerId);
                    if (MODE.equals("existingTender")) prepareExistingTender(player, client);
                    else prepare(player);
                });
            }
        }
        if (MODE.equals("reload")) {
            if (++ticks == 40) {
                done = true;
                server.execute(() -> checkRestart(server.overworld(), client));
            }
            return;
        }
        if (yard == null || client.level.getEntity(steamId) == null) return;
        if (!(client.level.getEntity(steamId) instanceof TrainVehicle steam)
            || !(client.level.getEntity(tenderId) instanceof TrainVehicle tender)) return;
        if (boarding != Boarding.DONE) {
            checkTender(client, steam, tender);
            return;
        }
        if (++ticks == 1) {
            firstPhase = steam.wheelPhase(1);
            firstZ = steam.getZ();
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        client.options.keyUp.setDown(ticks <= 34);
        var keyboard = InputConstants.Type.KEYBOARD;
        KeyMapping.set(keyboard.getOrCreate(InputConstants.KEY_H), ticks >= 3 && ticks <= 10 || ticks >= 119 && ticks <= 126);
        KeyMapping.set(keyboard.getOrCreate(InputConstants.KEY_B), ticks == 7 || ticks == 21);
        if (ticks >= 5 && ticks <= 11) hornHeld |= steam.hornActive();
        if (ticks >= 14 && ticks <= 19) {
            hornReleased |= !steam.hornActive();
            bellLatched |= steam.bellActive();
        }
        if (ticks == 20) grab(client, "steam-in-motion.png");
        if (ticks == 40) {
            boolean passed = steam.getZ() > firstZ + 2 && Math.abs(steam.wheelPhase(1) - firstPhase) > 1
                && hornHeld && hornReleased && bellLatched && !steam.bellActive()
                && sounds.containsAll(Set.of("steam_whistle", "train_bell", "steam_chuff"));
            LogUtils.getLogger().info("TRAINMOD ADDITIONS CLIENT {} steam controls, animated running gear and sounds {}",
                passed ? "PASSED" : "FAILED", sounds);
            if (!passed) {
                LogUtils.getLogger().error("Steam movement {}, wheel phase {}, horn held {}, horn released {}, bell latched {}, bell active {}",
                    steam.getZ() - firstZ, steam.wheelPhase(1) - firstPhase, hornHeld, hornReleased, bellLatched, steam.bellActive());
            }
            client.options.setCameraType(CameraType.FIRST_PERSON);
            client.options.keyShift.setDown(true);
        }
        if (ticks == 46) {
            LogUtils.getLogger().info("TRAINMOD ADDITIONS CLIENT {} tender dismount after a second sneak press",
                client.player.getVehicle() != tender ? "PASSED" : "FAILED");
            client.options.keyShift.setDown(false);
        }
        // The normal post-dismount boarding cooldown lasts 60 ticks.
        if (ticks == 112) {
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(playerId);
                player.stopRiding();
                if (player.level().getEntity(dieselId) instanceof TrainVehicle diesel) {
                    player.teleportTo(player.level(), diesel.getX(), diesel.getY() + 1, diesel.getZ(), Set.of(), 0, 0, true);
                    player.startRiding(diesel);
                }
            });
        }
        if (ticks == 131) {
            LogUtils.getLogger().info("TRAINMOD ADDITIONS CLIENT {} RS3 horn",
                sounds.contains("rs3_horn") ? "PASSED" : "FAILED");
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(playerId);
                player.stopRiding();
                player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                player.teleportTo(player.level(), yard.getX() + 17, yard.getY() + 3.5, yard.getZ() + 1,
                    Set.of(), 42, 12, true);
            });
        }
        if (ticks == 58 || ticks == 76 || ticks == 94) {
            server.execute(() -> {
                var level = server.overworld();
                UUID car = boreCarId;
                if (car != null && level.getEntity(BORE_ID) instanceof TrainVehicle bore
                    && level.getEntity(car) instanceof TrainVehicle tail && bore.trailingConsist(level).isEmpty()) {
                    couple(server.getPlayerList().getPlayer(playerId), bore, tail);
                }
            });
        }
        if (ticks == 133) grab(client, "bridges-and-catenary.png");
        if (ticks == 138) {
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(playerId);
                player.teleportTo(player.level(), yard.getX() + 17.5, yard.getY() - 1.9, yard.getZ() + 6,
                    Set.of(), 78, 3, true);
            });
        }
        if (ticks == 157) grab(client, "bridge-decks.png");
        if (ticks == 162) {
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(playerId);
                player.teleportTo(player.level(), yard.getX() - 18, yard.getY() + 4.5, yard.getZ() + 1,
                    Set.of(), -45, 15, true);
            });
        }
        if (ticks == 184) {
            BlockPos low = yard.offset(-9, 0, 11);
            var rail = RailNetwork.locate(client.level, low, TrackGeometry.origin(low), new Vec3(-1, 0, -1));
            var state = client.level.getBlockState(low);
            boolean powered = rail != null && Catenary.powered(client.level, rail);
            LogUtils.getLogger().info("TRAINMOD ADDITIONS CLIENT {} diagonal uphill curve and catenary",
                state.hasProperty(TrackBlock.DIAGONAL_LOW) && state.getValue(TrackBlock.DIAGONAL_LOW) && powered ? "PASSED" : "FAILED");
            grab(client, "rising-curve-and-wire.png");
        }
        if (ticks == 200) {
            done = true;
            client.getSoundManager().removeListener(LISTENER);
            client.stop();
        }
    }

    private static void checkTender(Minecraft client, TrainVehicle steam, TrainVehicle tender) {
        if (++boardingTicks > 150) {
            LogUtils.getLogger().error("TRAINMOD ADDITIONS CLIENT FAILED tender interaction at {}. Player {}, held {}, locomotive {}, tender {}",
                boarding, client.player.position(), client.player.getMainHandItem(), steam.position(), tender.position());
            client.options.keyShift.setDown(false);
            done = true;
            client.stop();
            return;
        }
        var player = client.player;
        switch (boarding) {
            case SELECT_ENGINE -> {
                if (player.getMainHandItem().is(Trainmod.COUPLER.get())
                    && click(client, steam, new Vec3(0.3, 0.84, steamEnd == 0 ? 1.1 : -1.1))) {
                    boarding = Boarding.SELECT_TENDER;
                }
            }
            case SELECT_TENDER -> {
                if (linked(steam, tender) || click(client, tender, new Vec3(0.30, 0.73, tenderEnd == 0 ? 0.77 : -0.77))) {
                    boarding = Boarding.WAIT_FOR_LINK;
                }
            }
            case WAIT_FOR_LINK -> {
                if (!linked(steam, tender)) break;
                var server = client.getSingleplayerServer();
                UUID id = player.getUUID();
                server.execute(() -> server.getPlayerList().getPlayer(id).setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
                boarding = Boarding.SUPPLIES;
            }
            case SUPPLIES -> {
                if (player.getMainHandItem().isEmpty() && click(client, tender, new Vec3(0.32, 0.71, 0))) {
                    boarding = Boarding.WAIT_FOR_MENU;
                }
            }
            case WAIT_FOR_MENU -> {
                if (!(client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) break;
                player.closeContainer();
                boolean sneak = MODE.equals("existingTender");
                client.options.keyShift.setDown(sneak);
                boarding = Boarding.BOARD;
            }
            case BOARD -> {
                if (player.isShiftKeyDown() == MODE.equals("existingTender")
                    && click(client, tender, new Vec3(0.05, 1.04, -0.24))) boarding = Boarding.HOLD;
            }
            case HOLD -> {
                if (player.getVehicle() != tender || ++heldSneak < 8) break;
                client.options.keyShift.setDown(false);
                boarding = Boarding.DONE;
                LogUtils.getLogger().info("TRAINMOD ADDITIONS CLIENT PASSED tender couplers, supplies menu and {} boarding",
                    MODE.equals("existingTender") ? "held-sneak" : "normal right-click");
                if (MODE.equals("existingTender")) {
                    done = true;
                    client.stop();
                }
            }
            case DONE -> {}
        }
    }

    private static boolean linked(TrainVehicle steam, TrainVehicle tender) {
        return steam.coupledId(steamEnd) == tender.getId() && tender.coupledId(tenderEnd) == steam.getId();
    }

    private static void prepareExistingTender(ServerPlayer player, Minecraft client) {
        var level = player.level();
        var stock = level.getEntitiesOfClass(TrainVehicle.class, AABB.ofSize(player.position(), 100, 40, 100));
        for (var steam : stock) {
            if (steam.kind != TrainVehicle.Kind.STEAM_LOCOMOTIVE) continue;
            for (var tender : stock) {
                if (tender.kind != TrainVehicle.Kind.TENDER || steam.distanceToSqr(tender) > 25
                    || steam.coupledId(0) == tender.getId() || steam.coupledId(1) == tender.getId()) continue;
                Vec3 delta = tender.position().subtract(steam.position()).multiply(1, 0, 1).normalize();
                steamEnd = Vec3.directionFromRotation(0, steam.getYRot()).dot(delta) > 0 ? 0 : 1;
                tenderEnd = Vec3.directionFromRotation(0, tender.getYRot()).dot(delta) < 0 ? 0 : 1;
                steamId = steam.getId();
                tenderId = tender.getId();
                Vec3 side = new Vec3(delta.z, 0, -delta.x);
                Vec3 eye = steam.position().lerp(tender.position(), 0.5).add(side.scale(1.9));
                player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.COUPLER.get()));
                player.teleportTo(level, eye.x, Math.floor(eye.y), eye.z, Set.of(), 36, 15, true);
                yard = steam.blockPosition();
                return;
            }
        }
        LogUtils.getLogger().error("TRAINMOD ADDITIONS CLIENT FAILED to find an uncoupled tender in the copied save");
        client.execute(client::stop);
    }

    private static boolean click(Minecraft client, TrainVehicle vehicle, Vec3 localTarget) {
        var player = client.player;
        Vec3 eye = player.getEyePosition();
        Vec3 click = vehicle.renderFrame(1).world(localTarget);
        double range = player.entityInteractionRange();
        if (click.distanceToSqr(eye) > range * range) return false;
        Vec3 end = eye.add(click.subtract(eye).normalize().scale(range));
        var hit = TrainClient.pickVehicles(player, eye, end, new AABB(eye, end).inflate(0.5),
            entity -> entity.isPickable() && entity != player, range * range, 1);
        if (hit == null || hit.getEntity() != vehicle) return false;
        client.gameMode.interact(player, vehicle, hit, InteractionHand.MAIN_HAND);
        return true;
    }

    private static void grab(Minecraft client, String name) {
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1,
            message -> LogUtils.getLogger().info("{}", message.getString()));
    }

    private static void prepare(ServerPlayer driver) {
        var level = driver.level();
        BlockPos root = new BlockPos(driver.blockPosition().getX() + 30, 110, driver.blockPosition().getZ() + 25);
        for (int x = -2; x <= 17; x++) {
            for (int z = -3; z <= 24; z++) {
                level.setBlockAndUpdate(root.offset(x, -4, z), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        for (int z = 0; z <= 22; z++) {
            RailwayGameTests.straight(level, root.south(z), 0);
            RailwayGameTests.straight(level, root.east(4).south(z), 0);
            RailwayGameTests.straight(level, root.east(7).south(z), 0);
            for (int x : new int[]{9, 10}) {
                placeBridge(level, root.offset(x, -1, z), Trainmod.STEEL_GIRDER.get(), z % 7 == 0);
                setRail(level, root.offset(x, 0, z));
            }
            placeBridge(level, root.offset(12, -1, z), Trainmod.STONE_ARCH.get(), z % 5 == 0);
            setRail(level, root.offset(12, 0, z));
            if (z % 5 == 0) {
                for (int depth = 1; depth <= 4; depth++) {
                    level.setBlockAndUpdate(root.offset(15, -depth, z), Trainmod.WOOD_TRESTLE.get().defaultBlockState());
                }
                setRail(level, root.offset(15, 0, z));
            }
        }
        for (int z : new int[]{3, 13, 21}) {
            level.setBlockAndUpdate(root.offset(8, -1, z), Trainmod.STEEL_GIRDER.get().defaultBlockState()
                .setValue(BridgeBlock.AXIS, Direction.Axis.X));
            level.setBlockAndUpdate(root.offset(3, 0, z), Trainmod.CATENARY_DOUBLE.get().defaultBlockState()
                .setValue(CatenaryTower.FACING, Direction.EAST));
            level.setBlockAndUpdate(root.offset(1, 0, z), Trainmod.CATENARY_SINGLE.get().defaultBlockState()
                .setValue(CatenaryTower.FACING, Direction.WEST));
            for (int x = 11; x <= 13; x++) {
                var p = root.offset(x, CatenaryTower.Kind.GANTRY.height, z);
                level.setBlockAndUpdate(p, Trainmod.CATENARY_GANTRY.get().defaultBlockState());
                level.setBlockAndUpdate(p.above(), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        BlockPos stacked = root.offset(10, -1, 21);
        level.setBlockAndUpdate(stacked.below(), Trainmod.STEEL_GIRDER.get().defaultBlockState().setValue(BridgeBlock.AXIS, Direction.Axis.Z));
        BlockPos slope = root.offset(-9, 0, 11);
        RailwayGameTests.piece(level, slope, TrackGeometry.Kind.CURVE_MEDIUM_GRADE, 0, true, true);
        var pole = Trainmod.CATENARY_SINGLE.get().defaultBlockState().setValue(CatenaryTower.FACING, Direction.EAST);
        BlockPos highPole = slope.offset(-5, 1, -8);
        level.setBlockAndUpdate(highPole.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(highPole, pole);
        BlockPos lowPole = slope.west().south();
        level.setBlockAndUpdate(lowPole.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(lowPole, pole.setValue(CatenaryTower.FACING, Direction.NORTH).setValue(CatenaryTower.DIAGONAL, true));
        var steam = RailwayGameTests.vehicle(level, TrainVehicle.Kind.STEAM_LOCOMOTIVE, root.south(7));
        var tender = RailwayGameTests.vehicle(level, TrainVehicle.Kind.TENDER, root.south(3));
        var diesel = RailwayGameTests.vehicle(level, TrainVehicle.Kind.LOCOMOTIVE, root.offset(4, 0, 7));
        diesel.supplies.setItem(0, new ItemStack(Trainmod.DIESEL_BUCKET.get()));
        dieselId = diesel.getId();
        RailwayGameTests.vehicle(level, TrainVehicle.Kind.BALLAST_CAR, root.offset(9, 0, 9)).cargo.setItem(0,
            new ItemStack(Items.GRAVEL, 64));
        RailwayGameTests.vehicle(level, TrainVehicle.Kind.ELECTRIC_SHUNTER, root.offset(12, 0, 9));
        tender.supplies.setItem(0, new ItemStack(Items.COAL, 8));
        tender.supplies.setItem(1, new ItemStack(Items.WATER_BUCKET));
        for (int z = 2; z <= 8; z++) {
            level.setBlockAndUpdate(root.offset(2, -1, z), Blocks.SMOOTH_STONE.defaultBlockState());
        }
        driver.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.COUPLER.get()));
        driver.teleportTo(level, root.getX() + 2.4, root.getY(), root.getZ() + 5.5, Set.of(), 36, 15, true);
        level.dimensionType().defaultClock().ifPresent(clock -> level.clockManager().setTotalTicks(clock, 3500));
        steamId = steam.getId();
        tenderId = tender.getId();
        yard = root;

        for (int z = -7; z <= 5; z++) RailwayGameTests.straight(level, BORE_TRACK.south(z), 0);
        var bore = new TrainVehicle(Trainmod.VEHICLES.get(TrainVehicle.Kind.TUNNEL_BORE).get(), level, TrainVehicle.Kind.TUNNEL_BORE);
        bore.setUUID(BORE_ID);
        bore.moveToRail(RailwayGameTests.at(level, BORE_TRACK.south()));
        level.addFreshEntity(bore);
        var car = RailwayGameTests.vehicle(level, TrainVehicle.Kind.BOXCAR, BORE_TRACK.north(2));
        boreCarId = car.getUUID();
    }

    private static void checkRestart(ServerLevel level, Minecraft client) {
        var expected = ChunkPos.containing(BORE_TRACK);
        var ahead = new ChunkPos(expected.x(), expected.z() + 1);
        var behind = new ChunkPos(expected.x(), expected.z() - 1);
        boolean passed = level.getEntity(BORE_ID) instanceof TrainVehicle bore && bore.kind == TrainVehicle.Kind.TUNNEL_BORE
            && bore.trailingConsist(level).size() == 1
            && BoreTickets.chunks(level, BORE_ID).containsAll(Set.of(expected.pack(), ahead.pack(), behind.pack()));
        LogUtils.getLogger().info("TRAINMOD ADDITIONS CLIENT {} parked bore and owned chunk tickets after restart",
            passed ? "PASSED" : "FAILED");
        client.execute(client::stop);
    }

    private static void placeBridge(ServerLevel level, BlockPos pos, BridgeBlock bridge, boolean pier) {
        if (pier) {
            level.setBlockAndUpdate(pos.below(), Blocks.STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(pos.below(2), Blocks.STONE_BRICKS.defaultBlockState());
        }
        level.setBlockAndUpdate(pos, bridge.defaultBlockState().setValue(BridgeBlock.AXIS, Direction.Axis.Z));
    }

    private static void setRail(ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos, Trainmod.TRACK.get(TrackGeometry.Kind.BRIDGE_STRAIGHT).get().segment(0, true, 0));
    }

    private static void couple(ServerPlayer driver, TrainVehicle engine, TrainVehicle tender) {
        driver.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.COUPLER.get()));
        engine.interact(driver, InteractionHand.MAIN_HAND, new Vec3(0, 0, -engine.kind.length / 2));
        tender.interact(driver, InteractionHand.MAIN_HAND, new Vec3(0, 0, tender.kind.length / 2));
        driver.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }
}
