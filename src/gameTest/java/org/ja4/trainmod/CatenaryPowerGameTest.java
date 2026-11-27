package org.ja4.trainmod;

import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.transaction.Transaction;

final class CatenaryPowerGameTest {
    static void generator(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = site(test, 50);
        var coal = installGenerator(level, root);
        coal.setItem(FuelGenerator.FUEL, new ItemStack(Items.COAL));
        coal.tick(level);
        test.assertTrue(coal.burnRemaining() == 1599 && coal.energy.getAmountAsInt() == 40,
            "A furnace coal burn lasts 1,600 ticks and each tick generates 40 FE");
        for (int i = 0; i < 450; i++) coal.tick(level);
        test.assertTrue(coal.energy.getAmountAsInt() == 16_000 && coal.burnRemaining() == 1200,
            "Backpressure pauses an active burn at the 16,000 FE limit");
        try (var simulation = Transaction.openRoot()) {
            test.assertTrue(coal.energy.extract(200, simulation) == 128, "The generator exposes up to 128 FE");
        }
        try (var output = Transaction.openRoot()) {
            test.assertTrue(coal.energy.extract(100, output) == 100 && coal.energy.extract(100, output) == 28
                && coal.energy.extract(1, output) == 0 && coal.energy.insert(1, output) == 0,
                "Simulations roll back, and all extraction calls share one output budget");
            output.commit();
        }
        coal.tick(level);
        test.assertTrue(coal.burnRemaining() == 1199 && coal.energy.getAmountAsInt() == 15_912,
            "Burning resumes as soon as the generator has room");
        var lava = installGenerator(level, root.east(3));
        lava.setItem(FuelGenerator.FUEL, new ItemStack(Items.LAVA_BUCKET));
        lava.setItem(FuelGenerator.RETURN, new ItemStack(Items.BUCKET, 16));
        lava.tick(level);
        test.assertTrue(lava.burnRemaining() == 0 && lava.getItem(FuelGenerator.FUEL).is(Items.LAVA_BUCKET),
            "A full return slot holds the fuel until its remainder fits");
        lava.removeItem(FuelGenerator.RETURN, 1);
        lava.tick(level);
        test.assertTrue(lava.burnRemaining() == 19_999 && lava.getItem(FuelGenerator.FUEL).isEmpty()
            && lava.getItem(FuelGenerator.RETURN).is(Items.BUCKET) && lava.getItem(FuelGenerator.RETURN).getCount() == 16,
            "Lava uses the furnace duration and returns its bucket to the output slot");
        var planks = installGenerator(level, root.east(6));
        planks.setItem(FuelGenerator.FUEL, new ItemStack(Items.OAK_PLANKS));
        planks.tick(level);
        test.assertTrue(planks.burnRemaining() == 299 && planks.getItem(FuelGenerator.RETURN).isEmpty(),
            "Tagged wooden furnace fuels work without producing a remainder");
        test.succeed();
    }

    static void bank(GameTestHelper test) {
        var level = test.getLevel();
        var root = site(test, 136);
        var facing = Direction.EAST;
        var player = test.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.setYRot(-90);
        player.setPos(Vec3.atBottomCenterOf(root.west(3)));
        var stack = new ItemStack(Trainmod.TRANSFORMER_BANK.get().asItem());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        level.setBlockAndUpdate(root.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(root.west().south().above(2), Blocks.STONE.defaultBlockState());
        var context = new UseOnContext(player, InteractionHand.MAIN_HAND, hit(root.below()));
        BlockPos rail = root.east(TransformerBank.TRACK_OFFSET).north();
        straightLine(level, rail, 0, 6);
        gantry(level, rail);
        gantry(level, rail.south(6));
        stack.useOn(context);
        test.assertTrue(level.getBlockState(root).isAir() && stack.getCount() == 1,
            "An obstructed top rear part prevents placement of the entire bank");
        level.removeBlock(root.west().south().above(2), false);
        test.assertTrue(stack.useOn(context).consumesAction() && stack.isEmpty(),
            "One survival kit places the bank from its front-left foot");
        var bank = (TransformerBank.Bank) level.getBlockEntity(root);
        var parts = TransformerBank.parts(root, bank.getBlockState());
        test.assertTrue(parts.size() == 12 && bank.complete()
            && bank.getBlockState().getValue(HorizontalDirectionalBlock.FACING) == facing,
            "The bank has a 2×2 footprint, three levels and one correctly oriented owner");
        var rear = level.getCapability(Capabilities.Energy.BLOCK, root.west(), Direction.WEST);
        var upperRear = level.getCapability(Capabilities.Energy.BLOCK, root.west().south().above(2), Direction.WEST);
        test.assertTrue(rear != null && rear == upperRear && rear.getCapacityAsInt() == 64_000
            && level.getCapability(Capabilities.Energy.BLOCK, root, Direction.EAST) == null
            && level.getCapability(Capabilities.Energy.BLOCK, root.west(), Direction.UP) == null
            && level.getCapability(Capabilities.Energy.BLOCK, root.west(), null) == null, "Only the rear face shares the input");
        try (var transaction = Transaction.openRoot()) {
            test.assertTrue(rear.insert(100, transaction) == 100 && upperRear.insert(100, transaction) == 28
                && rear.extract(20, transaction) == 0, "All six rear blocks share the 128 FE/t input budget");
            transaction.commit();
        }
        var generator = installGenerator(level, root.west(2));
        generator.energy.set(400);
        test.runAtTickTime(4, () -> {
            test.assertTrue(bank.energy.getAmountAsInt() > 128 && generator.energy.getAmountAsInt() < 400,
                "The furnace generator feeds a neighboring bank through its rear face");
            level.destroyBlock(root.south().above(), true);
            test.assertTrue(parts.keySet().stream().allMatch(pos -> level.getBlockState(pos).isAir()) && bank.isRemoved(),
                "Breaking an upper non-owner block removes all 12 parts");
            int kits = level.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(4)).stream()
                .map(ItemEntity::getItem).filter(item -> item.is(Trainmod.TRANSFORMER_BANK.get().asItem()))
                .mapToInt(ItemStack::getCount).sum();
            test.assertTrue(kits == 1, "The teardown drops exactly one kit");
            try (var transaction = Transaction.openRoot()) {
                test.assertTrue(rear.insert(20, transaction) == 0, "A stale input handle cannot feed a dismantled bank");
            }
            var replacement = new ItemStack(Trainmod.TRANSFORMER_BANK.get().asItem());
            player.setItemInHand(InteractionHand.MAIN_HAND, replacement);
            var transformer = (TransformerBank.Kit) replacement.getItem();
            var placement = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, replacement, hit(root.below()));
            test.assertTrue(transformer.connectedPreview(placement).size() == 12,
                "A free bank footprint opposite continuous unpowered wire qualifies for the green preview");
            var obstruction = root.west().south().above(2);
            level.setBlockAndUpdate(obstruction, Blocks.STONE.defaultBlockState());
            test.assertTrue(transformer.connectedPreview(placement).isEmpty(), "Obstructing any of the 12 cells suppresses the green preview");
            level.removeBlock(obstruction, false);
            level.removeBlock(rail.south(6).above(4), false);
            test.assertTrue(transformer.connectedPreview(placement).isEmpty(), "A broken span suppresses the green preview");
            test.succeed();
        });
    }

    static void transmission(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos first = site(test, 201).west(128).north(80), second = first.offset(6, 0, 6);
        var forced = new HashSet<ChunkPos>();
        force(level, forced, first);
        for (BlockPos root : List.of(first, second)) {
            straightLine(level, root, 0, 8);
            pole(level, root.west(), Trainmod.CATENARY_SINGLE.get(), Direction.EAST);
            pole(level, root.south(8).west(), Trainmod.CATENARY_SINGLE.get(), Direction.EAST);
        }
        var source = installBank(level, first.south(5).west(3), Direction.EAST);
        var sink = installBank(level, second.south(5).west(3), Direction.EAST);
        source.energy.set(40);
        BlockPos firstPole = first.west(), secondPole = second.west();
        BlockPos thirdPole = firstPole.south(4);
        pole(level, thirdPole, Trainmod.CATENARY_DOUBLE.get(), Direction.EAST);
        var player = test.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        var kit = Trainmod.TRANSMISSION_KIT.toStack(3);
        player.setItemInHand(InteractionHand.MAIN_HAND, kit);
        for (BlockPos pole : List.of(firstPole, secondPole, thirdPole)) {
            player.setPos(Vec3.atBottomCenterOf(pole.west(2)));
            var result = kit.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit(pole)));
            test.assertTrue(result.consumesAction(), "The transmission kit should mount on " + level.getBlockState(pole)
                + " at " + pole + ". Result: " + result);
            test.assertTrue(PowerStructure.isComplete(level, TransmissionExtension.parts(pole)),
                "The extension adds four blocks above its cantilever");
        }
        test.assertTrue(kit.isEmpty(), "Each complete extension consumes one kit");
        test.runAtTickTime(4, () -> {
            var firstTip = TransmissionExtension.tip(firstPole);
            var secondTip = TransmissionExtension.tip(secondPole);
            var thirdTip = TransmissionExtension.tip(thirdPole);
            test.assertTrue(CatenaryPower.transmissionNeighbors(level, firstTip).containsAll(List.of(secondTip, thirdTip)),
                "The nearest cardinal and diagonal tips connect automatically");
            var position = RailwayGameTests.at(level, second.south(6));
            var supply = circuit(level, position);
            test.assertTrue(supply != null && supply.available(level) == 40 && sink.live(),
                "Expected a 40 FE remote supply. Source tip: " + source.transmission() + ", sink tip: " + sink.transmission()
                    + ", source tap: " + source.tap() + ", sink tap: " + sink.tap() + ", circuit: " + supply
                    + ", available: " + (supply == null ? 0 : supply.available(level)) + ", meter live: " + sink.live());
            BlockPos obstruction = firstTip.offset(3, 0, 3);
            level.setBlockAndUpdate(obstruction, Blocks.STONE.defaultBlockState());
            test.assertTrue(!CatenaryPower.transmissionNeighbors(level, firstTip).contains(secondTip)
                && circuit(level, position).available(level) == 0, "An obstructed diagonal disconnects its remote supply immediately");
            level.removeBlock(obstruction, false);
            test.assertTrue(draw(level, circuit(level, position)) && source.energy.getAmountAsInt() == 20,
                "One remote draw consumes exactly 20 FE from the supplying bank");
            level.destroyBlock(secondPole.above(TransmissionExtension.START + 1), true);
            test.assertTrue(TransmissionExtension.parts(secondPole).keySet().stream().allMatch(pos -> level.getBlockState(pos).isAir())
                && !CatenaryPower.transmissionNeighbors(level, firstTip).contains(secondTip),
                "Breaking a shaft removes the whole extension and its high line");
            test.assertTrue(circuit(level, position).available(level) == 20,
                "The receiver reconnects to another clear tip when its nearest extension is removed");
            level.destroyBlock(thirdPole, true);
            test.assertTrue(TransmissionExtension.parts(thirdPole).keySet().stream().allMatch(pos -> level.getBlockState(pos).isAir()),
                "Removing a supporting catenary pole also removes its four-block extension");
            unforce(level, forced);
            test.succeed();
        });
    }

    static void transmissionDistance(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos first = site(test, 24), second = first.east(256);
        BlockPos firstPole = first.west(6).south(4), secondPole = firstPole.east(256);
        BlockPos outside = secondPole.south(257);
        var forced = new HashSet<ChunkPos>();
        for (int i = 0; i <= 256; i += 16) {
            force(level, forced, firstPole.east(i));
            force(level, forced, secondPole.south(i));
        }
        force(level, forced, outside);
        force(level, forced, second);
        try {
            for (BlockPos root : List.of(first, second)) {
                straightLine(level, root, 0, 8);
                gantry(level, root);
                gantry(level, root.south(8));
            }
            var source = installBank(level, first.west(3).south(3), Direction.EAST);
            var sink = installBank(level, second.west(3).south(3), Direction.EAST);
            source.energy.set(40);
            for (BlockPos pole : List.of(firstPole, secondPole, outside)) {
                pole(level, pole, Trainmod.CATENARY_SINGLE.get(), Direction.WEST);
                TransmissionExtension.parts(pole).forEach(level::setBlockAndUpdate);
            }
            BlockPos legacy = firstPole.north(4);
            pole(level, legacy, Trainmod.CATENARY_SINGLE.get(), Direction.WEST);
            TransmissionExtension.parts(legacy, 0, TransmissionExtension.LEGACY_TOP).forEach(level::setBlockAndUpdate);
            test.runAtTickTime(7, () -> {
                try {
                    BlockPos firstTip = TransmissionExtension.tip(firstPole);
                    BlockPos secondTip = TransmissionExtension.tip(secondPole);
                    test.assertTrue(CatenaryPower.transmissionNeighbors(level, firstTip).contains(secondTip)
                        && !CatenaryPower.transmissionNeighbors(level, secondTip).contains(TransmissionExtension.tip(outside)),
                        "The 256-block pole link works and a pole 257 blocks away stays disconnected");
                    test.assertTrue(level.getBlockEntity(secondTip) instanceof TransmissionExtension.Tip tip
                        && tip.neighbors().contains(firstTip), "The receiving tip publishes the far endpoint for rendering");
                    var position = RailwayGameTests.at(level, second.south(4));
                    var circuit = circuit(level, position);
                    test.assertTrue(secondTip.equals(sink.transmission()) && sink.live() && circuit != null,
                        "Expected the standalone tip to power the lower gantries. Source tip: " + source.transmission()
                            + ", sink tip: " + sink.transmission() + ", expected: " + secondTip + ", sink live: " + sink.live()
                            + ", sink tap: " + sink.tap() + ", circuit: " + circuit);
                    test.assertTrue(draw(level, circuit) && source.energy.getAmountAsInt() == 20,
                        "A draw across the high line debits the source once");
                    test.assertTrue(TransmissionExtension.parts(legacy).size() == 4 && TransmissionExtension.complete(level, legacy)
                        && TransmissionExtension.parts(legacy, TransmissionExtension.HEIGHT, TransmissionExtension.LEGACY_TOP).keySet()
                            .stream().allMatch(pos -> level.getBlockState(pos).isAir()),
                        "An existing eight-block extension shortens in place without losing the lower mast");
                    test.succeed();
                } finally {
                    unforce(level, forced);
                }
            });
        } catch (RuntimeException | Error failure) {
            unforce(level, forced);
            throw failure;
        }
    }

    private static void force(ServerLevel level, HashSet<ChunkPos> forced, BlockPos pos) {
        ChunkPos chunk = ChunkPos.containing(pos);
        if (level.setChunkForced(chunk.x(), chunk.z(), true)) forced.add(chunk);
        level.getChunkAt(pos);
    }

    private static void unforce(ServerLevel level, HashSet<ChunkPos> forced) {
        forced.forEach(chunk -> level.setChunkForced(chunk.x(), chunk.z(), false));
    }

    static void overheadReach(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = site(test, 36).west(96);
        var forced = new HashSet<ChunkPos>();
        for (int z = 0; z <= 408; z += 8) force(level, forced, root.south(z));
        try {
            straightLine(level, root, 0, 408);
            for (int z = 0; z <= 408; z += 12) gantry(level, root.south(z));
            var source = installBank(level, root.south(193).west(3), Direction.EAST);
            var receiver = installBank(level, root.south(400).west(3), Direction.EAST);
            source.energy.set(100);
            BlockPos sourcePole = root.south(193).west(6);
            BlockPos receiverPole = root.south(400).west(6);

            test.runAtTickTime(5, () -> {
                test.assertTrue(available(level, root.south(1)) == 100 && available(level, root.south(385)) == 100
                    && available(level, root) == 0 && available(level, root.south(386)) == 0,
                    "Contact wire can draw exactly 192 blocks in either direction from the tap");
                var route = RailwayGameTests.at(level, root.south(385)).rail();
                var ranges = CatenaryPower.wireRanges(level, route);
                test.assertTrue(ranges.size() == 2 && ranges.getFirst().powered()
                    && Math.abs(ranges.getFirst().to() - 0.5) < 1e-5 && !ranges.getLast().powered(),
                    "The server overlay splits a rail at the 192-block limit and leaves the rest connected");
                test.assertTrue(!receiver.live() && available(level, root.south(406)) == 0,
                    "Continuous contact wire cannot feed an unconnected remote transformer");
                for (BlockPos pole : List.of(sourcePole, receiverPole)) {
                    pole(level, pole, Trainmod.CATENARY_SINGLE.get(), Direction.WEST);
                    TransmissionExtension.parts(pole).forEach(level::setBlockAndUpdate);
                }
            });
            test.runAtTickTime(12, () -> {
                try {
                    var remote = RailwayGameTests.at(level, root.south(406));
                    var supply = circuit(level, remote);
                    test.assertTrue(receiver.live() && supply != null && supply.available(level) == 100,
                        "A transmission-connected transformer feeds a new 192-block radius from its own tap");
                    test.assertTrue(draw(level, supply) && source.energy.getAmountAsInt() == 80,
                        "A remote traction draw charges the transmission-connected source once");
                    level.removeBlock(receiverPole.above(TransmissionExtension.START), false);
                    test.assertTrue(available(level, root.south(406)) == 0,
                        "Breaking the high line removes remote power while the lower spans stay connected");
                    test.succeed();
                } finally {
                    unforce(level, forced);
                }
            });
        } catch (RuntimeException | Error failure) {
            unforce(level, forced);
            throw failure;
        }
    }

    private static long available(ServerLevel level, BlockPos pos) {
        var supply = circuit(level, RailwayGameTests.at(level, pos));
        return supply == null ? 0 : supply.available(level);
    }

    static void circuits(GameTestHelper test) {
        var level = test.getLevel();
        var root = site(test, 35);
        straightLine(level, root, 0, 12);
        straightLine(level, root.east(3), 0, 12);
        for (int z : new int[]{0, 12}) pole(level, root.south(z).west(), Trainmod.CATENARY_DOUBLE.get(), Direction.EAST);
        var feed = installBank(level, root.south(6).west(3), Direction.EAST);
        var near = RailwayGameTests.at(level, root.south(6));
        var far = RailwayGameTests.at(level, root.east(3).south(6));

        BlockPos crossing = root.east(8);
        RailwayGameTests.piece(level, crossing, TrackGeometry.Kind.CROSSOVER_90, 0, true);
        for (int step : new int[]{-4, -3, -2, 2, 3, 4}) {
            RailwayGameTests.straight(level, crossing.south(step), 0);
            RailwayGameTests.straight(level, crossing.east(step), 4);
        }
        for (int step : new int[]{-4, 4}) {
            gantry(level, crossing.south(step));
            gantry(level, crossing.east(step));
        }
        var crossingFeed = installBank(level, crossing.south(3).west(3), Direction.EAST);
        crossingFeed.energy.set(20);

        BlockPos turnout = root.east(18);
        RailwayGameTests.piece(level, turnout, TrackGeometry.Kind.TURNOUT_45_COMPACT, 0, true);
        straightLine(level, turnout, -5, -1);
        straightLine(level, turnout, 3, 7);
        gantry(level, turnout.north(5));
        gantry(level, turnout.south(7));
        var rails = RailNetwork.physicalRailsAt(level, turnout);
        BlockPos diagonal = turnout.offset(2, 0, 3);
        RailwayGameTests.piece(level, diagonal, TrackGeometry.Kind.DIAGONAL, 14, true);
        gantry(level, diagonal);
        var turnoutFeed = installBank(level, turnout.south(6).west(3), Direction.EAST);
        turnoutFeed.energy.set(200);
        BlockPos unwired = turnout.east(3).north(4);
        RailwayGameTests.straight(level, unwired, 0);

        test.runAtTickTime(4, () -> {
            test.assertTrue(!CatenaryPower.contacts(level, far, far.point()).isEmpty() && circuit(level, far) == null
                && circuit(level, near).available(level) == 0, "Legacy double-contact wire is physical and starts unpowered");
            feed.energy.set(20);
            test.assertTrue(circuit(level, near).available(level) == 20 && circuit(level, far) == null,
                "A bank tapping one double-track contact wire leaves the other independent");
            test.assertTrue(draw(level, circuit(level, near)) && !draw(level, circuit(level, near))
                && feed.energy.getAmountAsInt() == 0, "A depleted circuit cannot satisfy a second draw");
            var ninety = RailNetwork.physicalRailsAt(level, crossing);
            test.assertTrue(CatenaryPower.wireRanges(level, ninety.get(0)).stream().anyMatch(CatenaryPower.Range::powered)
                && CatenaryPower.wireRanges(level, ninety.get(1)).stream().noneMatch(CatenaryPower.Range::powered),
                "Crossing routes remain electrically independent");
            test.assertTrue(rails.stream().allMatch(rail -> CatenaryPower.wireRanges(level, rail).stream().anyMatch(CatenaryPower.Range::powered)),
                "Both turnout wires share the energized approach");
            var overlay = CatenaryOverlay.nearby(level, TrackGeometry.origin(root.east(13)));
            var turnoutOverlay = overlay.stream().filter(route -> route.root().equals(turnout)).toList();
            var crossingOverlay = overlay.stream().filter(route -> route.root().equals(crossing)).toList();
            test.assertTrue(turnoutOverlay.size() == 2 && crossingOverlay.size() == 2
                && crossingOverlay.stream().filter(route -> !route.wire().isEmpty()
                    && route.wire().stream().noneMatch(CatenaryPower.Range::powered)).count() == 1
                && overlay.stream().anyMatch(route -> route.root().equals(unwired) && route.wire().isEmpty()),
                "Expected powered, unpowered connected, and unwired overlay routes. Turnout: " + turnoutOverlay + ", crossing: " + crossingOverlay);
            feed.energy.set(40);
            BlockPos damage = root.south(8);
            level.setBlockAndUpdate(damage, level.getBlockState(damage).setValue(TrackBlock.CONDITION, TrackBlock.Condition.BROKEN));
            test.assertTrue(CatenaryPower.contacts(level, near, near.point()).isEmpty() && feed.energy.getAmountAsInt() == 40,
                "Broken running rail interrupts the overhead span without consuming the bank's stored energy");
            test.succeed();
        });
    }

    static void shunterDraws(GameTestHelper test) {
        var level = test.getLevel();
        var root = site(test, 237);
        straightLine(level, root, 0, 16);
        gantry(level, root);
        gantry(level, root.south(15));
        var bank = installBank(level, root.south(7).west(3), Direction.EAST);
        var first = RailwayGameTests.vehicle(level, TrainVehicle.Kind.ELECTRIC_SHUNTER, root.south(4));
        var second = RailwayGameTests.vehicle(level, TrainVehicle.Kind.ELECTRIC_SHUNTER, root.south(10));
        double startFirst = first.getZ(), startSecond = second.getZ();
        var firstDriver = test.makeMockServerPlayerInLevel();
        var secondDriver = test.makeMockServerPlayerInLevel();
        bank.energy.set(60);
        test.runAtTickTime(5, () -> {
            test.assertTrue(battery(first) == 0 && battery(second) == 0 && bank.energy.getAmountAsInt() == 60,
                "Parked empty shunters draw nothing under a live gantry");
            board(firstDriver, first);
            board(secondDriver, second);
            bank.energy.set(0);
        });
        test.runAtTickTime(17, () -> {
            bank.energy.set(20);
            first.receiveControls(firstDriver, TrainControls.FORWARD);
            second.receiveControls(secondDriver, TrainControls.FORWARD);
            first.tick();
            second.tick();
            double firstTravel = first.getZ() - startFirst, secondTravel = second.getZ() - startSecond;
            test.assertTrue(firstTravel > 0 && secondTravel == 0 && bank.energy.getAmountAsInt() == 0,
                "Only one shunter can use the last 20 FE. Travel: " + firstTravel + ", " + secondTravel
                    + ". Remaining FE: " + bank.energy.getAmountAsInt());
            test.assertTrue(battery(first) == 0 && battery(second) == 0, "A successful traction draw takes priority over reserve charging");
            first.receiveControls(firstDriver, (byte) 0);
            second.receiveControls(secondDriver, (byte) 0);
        });
        test.runAtTickTime(20, () -> bank.energy.set(80));
        test.runAtTickTime(27, () -> {
            test.assertTrue(battery(first) + battery(second) == 4 && bank.energy.getAmountAsInt() == 0,
                "Parked, occupied shunters spend 20 FE per reserve tick");
            firstDriver.stopRiding();
            secondDriver.stopRiding();
            first.discard();
            second.discard();
            test.succeed();
        });
    }

    static void compactTurnouts(GameTestHelper test) {
        var level = test.getLevel();
        var kind = TrackGeometry.Kind.TURNOUT_45_COMPACT;
        var builder = test.makeMockServerPlayerInLevel();
        builder.setGameMode(GameType.CREATIVE);
        var forced = new HashSet<ChunkPos>();
        for (int z = -8; z <= 16; z += 4) force(level, forced, compactSite(test, 293).south(z));
        for (boolean left : new boolean[]{true, false}) {
            BlockPos first = compactSite(test, left ? 293 : 296);
            BlockPos second = first.offset(left ? 3 : -3, 0, 5);
            placeCompact(test, builder, first, 0, left);
            placeCompact(test, builder, second, 8, left);
            var routes = kind.shape(0, left).routes();
            test.assertTrue(routes.getFirst().length == 3 && routes.get(1).endpoint(true).z == 2.5,
                "Both legs end one block sooner while the branch retains its diagonal exit");
            var block = Trainmod.TRACK.get(kind).get();
            test.assertTrue(block.shape(block.defaultBlockState()).routes().getFirst().length == 4,
                "The original footprint remains available for compact turnouts already placed in existing saves");
            var firstCells = new HashSet<>(kind.shape(0, left).cells().stream().map(first::offset).toList());
            test.assertTrue(kind.shape(8, left).cells().stream().map(second::offset).noneMatch(firstCells::contains),
                "Opposing compact kits on three-block centers meet without overlapping cells");
            straightLine(level, first, -8, -1);
            straightLine(level, first, 3, 10);
            straightLine(level, second, 1, 8);
            straightLine(level, second, -10, -3);
            var start = RailwayGameTests.at(level, first.north(2));
            var normal = RailNetwork.advance(level, start, 9);
            test.assertTrue(normal != null && Math.abs(normal.point().x - (first.getX() + 0.5)) < 0.01,
                "An unpowered compact turnout selects the ordinary straight line");
            for (BlockPos root : List.of(first, second)) {
                level.setBlockAndUpdate(root.below(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                var state = level.getBlockState(root);
                ((TrackBlock.Turnout) state.getBlock()).refreshPower(level, root);
                test.assertTrue(level.getBlockState(root).getValue(BlockStateProperties.POWERED),
                    "The compact turnout throws with ordinary redstone");
            }
            var exit = RailNetwork.advance(level, start, 13);
            test.assertTrue(exit != null && Math.abs(exit.point().x - (second.getX() + 0.5)) < 0.01,
                "The thrown branch traverses both kits and exits onto the other line");
            var back = RailNetwork.advance(level, exit, -13);
            test.assertTrue(back != null && back.point().distanceToSqr(start.point()) < 1e-6,
                "The same crossover traverses from the opposite direction");
            var shunter = RailwayGameTests.vehicle(level, TrainVehicle.Kind.ELECTRIC_SHUNTER, first.north(2));
            var data = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            data.putInt("BatteryTicks", 200);
            shunter.readAdditionalSaveData(net.minecraft.world.level.storage.TagValueInput.create(
                ProblemReporter.DISCARDING, level.registryAccess(), data.buildResult()));
            shunter.moveToRail(start);
            for (BlockPos root : List.of(first, second)) {
                for (BlockPos part : kind.shape(root.equals(first) ? 0 : 8, left).cells()) {
                    BlockPos cell = root.offset(part);
                    level.setBlock(cell, level.getBlockState(cell).setValue(TrackBlock.CONDITION, TrackBlock.Condition.BALLASTED), Block.UPDATE_CLIENTS);
                }
            }
            var rider = test.makeMockServerPlayerInLevel();
            board(rider, shunter);
            for (int tick = 0; tick < 68; tick++) {
                shunter.receiveControls(rider, TrainControls.FORWARD);
                shunter.tick();
            }
            test.assertTrue(Math.abs(shunter.getX() - (second.getX() + 0.5)) < 0.01, "An actual shunter drives across the compact pair");
            for (int tick = 0; tick < 80; tick++) {
                shunter.receiveControls(rider, TrainControls.REVERSE);
                shunter.tick();
            }
            test.assertTrue(Math.abs(shunter.getX() - (first.getX() + 0.5)) < 0.01, "The shunter drives back across the compact pair");
            rider.stopRiding();
            shunter.discard();
        }
        BlockPos diagonalSite = compactSite(test, 310);
        placeCompact(test, builder, diagonalSite, 0, true);
        straightLine(level, diagonalSite, -3, -1);
        BlockPos diagonal = diagonalSite.offset(2, 0, 3);
        RailwayGameTests.piece(level, diagonal, TrackGeometry.Kind.DIAGONAL, 14, true);
        level.setBlock(diagonalSite, level.getBlockState(diagonalSite).setValue(BlockStateProperties.POWERED, true), Block.UPDATE_CLIENTS);
        var diagonalConnection = RailNetwork.advance(level, RailwayGameTests.at(level, diagonalSite.north()), 4.4);
        test.assertTrue(diagonalConnection != null && diagonalConnection.rail().root().equals(diagonal),
            "An ordinary diagonal connects directly to the compact branch corner");
        unforce(level, forced);
        test.succeed();
    }

    private static void placeCompact(GameTestHelper test, ServerPlayer builder, BlockPos root, int rotation, boolean left) {
        var level = test.getLevel();
        var kind = TrackGeometry.Kind.TURNOUT_45_COMPACT;
        for (BlockPos cell : kind.shape(rotation, left).cells()) {
            level.setBlockAndUpdate(root.offset(cell).below(), Blocks.STONE.defaultBlockState());
        }
        var kit = Trainmod.TRACK_ITEMS.get(kind).get();
        var stack = new ItemStack(kit);
        double side = (left ? 1 : -1) * (rotation == 0 ? 1 : -1);
        builder.setYRot(rotation == 0 ? 0 : 180);
        builder.setPos(Vec3.atBottomCenterOf(root.north(rotation == 0 ? 3 : -3)));
        builder.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var contact = new BlockHitResult(Vec3.atCenterOf(root.below()).add(side * 0.24, 0.5, 0), Direction.UP, root.below(), false);
        var context = new UseOnContext(builder, InteractionHand.MAIN_HAND, contact);
        var preview = kit.preview(level, context, builder);
        test.assertTrue(preview != null && preview.supported() && preview.root().equals(root)
            && preview.left() == left && preview.rotation() == rotation,
            "Expected compact preview at " + root + ", rotation " + rotation + ", left " + left + ". Actual: " + preview
                + ", yaw: " + builder.getYRot() + ", cells: " + kind.shape(rotation, left).cells().stream().map(root::offset)
                    .map(cell -> cell + "=" + level.getBlockState(cell) + " loaded=" + level.isLoaded(cell)
                        + " supported=" + TrackBlock.supported(level, cell) + " permitted=" + level.mayInteract(builder, cell)).toList());
        test.assertTrue(stack.useOn(context).consumesAction() && level.getBlockState(root).getValue(TrackBlock.HAND)
            == (left ? ChestType.LEFT : ChestType.RIGHT), "The compact turnout kit places the previewed route");
    }

    private static BlockPos compactSite(GameTestHelper test, int height) {
        BlockPos pos = site(test, height);
        var chunk = ChunkPos.containing(pos);
        return new BlockPos(chunk.getMinBlockX() - 121, pos.getY(), chunk.getMinBlockZ() + 68);
    }

    static TransformerBank.Bank installBank(ServerLevel level, BlockPos root, Direction facing) {
        var state = Trainmod.TRANSFORMER_BANK.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
        TransformerBank.parts(root, state).forEach(level::setBlockAndUpdate);
        return (TransformerBank.Bank) level.getBlockEntity(root);
    }

    static FuelGenerator.Machine installGenerator(ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos, Trainmod.FUEL_GENERATOR.get().defaultBlockState());
        return (FuelGenerator.Machine) level.getBlockEntity(pos);
    }

    static void pole(ServerLevel level, BlockPos pos, CatenaryTower block, Direction facing) {
        level.setBlockAndUpdate(pos, block.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing));
    }

    static void gantry(ServerLevel level, BlockPos track) {
        pole(level, track.above(4), Trainmod.CATENARY_GANTRY.get(), Direction.NORTH);
    }

    static void straightLine(ServerLevel level, BlockPos root, int first, int last) {
        for (int i = first; i <= last; i++) RailwayGameTests.straight(level, root.south(i), 0);
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    private static BlockPos site(GameTestHelper test, int height) {
        BlockPos pos = test.absolutePos(new BlockPos(0, height, 0));
        var chunk = ChunkPos.containing(pos);
        return new BlockPos(chunk.getMinBlockX() + 8, pos.getY(), chunk.getMinBlockZ() + 1);
    }

    private static CatenaryPower.Circuit circuit(ServerLevel level, RailNetwork.Position position) {
        return CatenaryPower.contacts(level, position, position.point()).stream().map(CatenaryPower.Contact::circuit)
            .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private static boolean draw(ServerLevel level, CatenaryPower.Circuit circuit) {
        try (var transaction = Transaction.openRoot()) {
            if (!circuit.draw(level, transaction)) return false;
            transaction.commit();
            return true;
        }
    }

    private static void board(ServerPlayer rider, TrainVehicle vehicle) {
        rider.setPos(vehicle.position());
        rider.startRiding(vehicle);
    }

    private static int battery(TrainVehicle vehicle) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, vehicle.level().registryAccess());
        vehicle.addAdditionalSaveData(output);
        return output.buildResult().getIntOr("BatteryTicks", 0);
    }
}
