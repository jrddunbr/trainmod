package org.ja4.trainmod;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

final class RailwayAdditionsGameTest {
    static void fuelAndLegacyStock(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        var dieselInput = CraftingInput.of(2, 1, List.of(new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.COAL)));
        var recipes = level.getServer().getRecipeManager();
        var dieselRecipe = recipes.getRecipeFor(RecipeType.CRAFTING, dieselInput, level).orElseThrow().value();
        var bucket = dieselRecipe.assemble(dieselInput);
        test.assertTrue(bucket.is(VehicleFuel.DIESEL) && dieselRecipe.getRemainingItems(dieselInput).stream().allMatch(ItemStack::isEmpty),
            "The fallback recipe turns its water bucket into a tagged diesel bucket");
        var gravel = new ItemStack(Items.GRAVEL);
        var crafted = CraftingInput.of(1, 1, List.of(gravel));
        var craftedBallast = recipes.getRecipeFor(RecipeType.CRAFTING, crafted, level).orElseThrow().value().assemble(crafted);
        test.assertTrue(craftedBallast.is(Trainmod.BALLAST.get().asItem()) && craftedBallast.getCount() == 2,
            "One gravel crafts two ballast");
        var cut = new SingleRecipeInput(gravel);
        var cutBallast = recipes.getRecipeFor(RecipeType.STONECUTTING, cut, level).orElseThrow().value().assemble(cut);
        test.assertTrue(cutBallast.is(Trainmod.BALLAST.get().asItem()) && cutBallast.getCount() == 2,
            "The stonecutter also yields two ballast");

        var fuel = new VehicleFuel();
        var diesel = new SimpleContainer(9);
        test.assertTrue(!fuel.hasDiesel(diesel) && !fuel.hasCharge(), "New engines have empty fuel and batteries");
        diesel.setItem(0, bucket);
        for (int tick = 0; tick < 1200; tick++) {
            test.assertTrue(fuel.hasDiesel(diesel), "One diesel bucket supplies 1,200 powered ticks");
            fuel.burnDiesel(diesel);
            if (tick == 599) fuel = reloadFuel(level, fuel);
        }
        test.assertTrue(diesel.getItem(0).is(Items.BUCKET) && !fuel.hasDiesel(diesel),
            "The engine returns one bucket and stops drawing power when it runs dry");

        var supplies = new SimpleContainer(9);
        supplies.setItem(0, new ItemStack(Items.COAL, 2));
        supplies.setItem(1, new ItemStack(Items.WATER_BUCKET));
        test.assertTrue(fuel.steam(0, supplies).power() == 0, "A closed throttle consumes no steam supplies");
        for (int tick = 0; tick < 400; tick++) {
            var output = fuel.steam(1, supplies);
            test.assertTrue(output.power() == 1, "Each coal and water bucket supplies 400 powered ticks");
            fuel.burnSteam(supplies, output);
            if (tick == 199) fuel = reloadFuel(level, fuel);
        }
        test.assertTrue(fuel.steam(1, supplies).power() == 0 && supplies.getItem(0).getCount() == 1
            && supplies.getItem(1).is(Items.BUCKET), "Missing water halts traction without consuming any more coal");
        supplies.removeItemNoUpdate(0);
        supplies.setItem(1, new ItemStack(Items.WATER_BUCKET));
        test.assertTrue(fuel.steam(-1, supplies).power() == 0, "Missing coal also stops steam traction");
        for (int tick = 0; tick < 220; tick++) fuel.charge();
        fuel = reloadFuel(level, fuel);
        for (int tick = 0; tick < 200; tick++) {
            test.assertTrue(fuel.hasCharge(), "The reserve survives reload and supplies 200 powered ticks");
            fuel.drawCharge();
        }
        test.assertTrue(!fuel.hasCharge(), "The reserve is capped at 200 ticks");

        BlockPos cell = test.absolutePos(new BlockPos(0, 41, 0));
        RailwayGameTests.straight(level, cell, 0);
        var legacyEngine = RailwayGameTests.vehicle(level, TrainVehicle.Kind.LOCOMOTIVE, cell);
        var legacy = output(level);
        legacy.store("Rail", BlockPos.CODEC, cell);
        legacy.putInt("RailRoute", 0);
        legacyEngine.readAdditionalSaveData(input(level, legacy));
        test.assertTrue(legacyEngine.cursor() != null && legacyEngine.supplies.isEmpty(), "An old RS3 loads with its rail cursor and an empty tank");
        legacyEngine.discard();
        var retired = Trainmod.SIGNAL.get().defaultBlockState().setValue(BlockStateProperties.POWERED, true)
            .setValue(BlockStateProperties.INVERTED, true).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        level.setBlockAndUpdate(cell.west(), retired);
        test.assertTrue(level.getBlockState(cell.west()) == retired && !retired.isSignalSource()
            && level.getSignal(cell.west(), Direction.EAST) == 0, "The saved signal state remains inert");
        test.succeed();
    }

    static void trackManufacturing(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        BlockPos pos = test.absolutePos(new BlockPos(0, 42, 0));
        var state = Trainmod.RAIL_EXTRUDER.get().defaultBlockState();
        level.setBlockAndUpdate(pos, state);
        var machine = (RailExtruder.Machine) level.getBlockEntity(pos);
        var energy = level.getCapability(Capabilities.Energy.BLOCK, pos, Direction.NORTH);
        var feed = level.getCapability(Capabilities.Item.BLOCK, pos, Direction.UP);
        var collect = level.getCapability(Capabilities.Item.BLOCK, pos, Direction.DOWN);
        test.assertTrue(machine != null && energy != null && feed != null && collect != null,
            "The placed extruder exposes energy, input and output capabilities");

        try (var transaction = Transaction.openRoot()) {
            test.assertTrue(feed.insert(0, ItemResource.of(Items.COPPER_INGOT), 1, transaction) == 0
                && collect.insert(0, ItemResource.of(Items.IRON_INGOT), 1, transaction) == 0
                && feed.insert(0, ItemResource.of(Items.IRON_INGOT), 2, transaction) == 2,
                "Automation can insert iron ingots into the input slot only");
            transaction.commit();
        }
        machine.process(true);
        test.assertTrue(machine.getItem(RailExtruder.INPUT).getCount() == 2
            && machine.getItem(RailExtruder.OUTPUT).isEmpty(), "An empty energy buffer stalls the powered extruder");
        try (var transaction = Transaction.openRoot()) {
            test.assertTrue(energy.insert(200, transaction) == 200 && energy.insert(200, transaction) == 200,
                "External Forge Energy can fill the buffer");
            transaction.commit();
        }

        machine.setItem(RailExtruder.OUTPUT, new ItemStack(Trainmod.RAIL.get(), 49));
        machine.process(true);
        test.assertTrue(energy.getAmountAsLong() == 400, "A blocked output uses no energy");
        machine.setItem(RailExtruder.OUTPUT, ItemStack.EMPTY);
        for (int tick = 0; tick < 20; tick++) machine.process(true);
        test.assertTrue(energy.getAmountAsLong() == 200 && machine.getItem(RailExtruder.OUTPUT).isEmpty(),
            "Half of the powered cycle uses 200 FE without producing rails");
        var saved = output(level);
        machine.saveAdditional(saved);
        var restored = new RailExtruder.Machine(pos, state);
        restored.loadAdditional(input(level, saved));
        for (int tick = 0; tick < 20; tick++) restored.process(true);
        var rails = restored.getItem(RailExtruder.OUTPUT);
        test.assertTrue(rails.is(Trainmod.RAIL.get()) && rails.getCount() == 16
            && restored.getItem(RailExtruder.INPUT).getCount() == 1, "The saved cycle completes at sixteen rails per ingot");
        restored.process(true);
        for (int tick = 0; tick < 39; tick++) restored.process(false);
        test.assertTrue(rails.getCount() == 16, "The unpowered cycle also takes 40 ticks");
        restored.process(false);
        test.assertTrue(rails.getCount() == 32 && restored.getItem(RailExtruder.INPUT).isEmpty(),
            "Disabling the power requirement lets an empty buffer complete another cycle");

        var recipes = level.getServer().getRecipeManager();
        var empty = ItemStack.EMPTY;
        var plank = new ItemStack(Items.BIRCH_PLANKS);
        var cut = new SingleRecipeInput(plank);
        var ties = recipes.getRecipeFor(RecipeType.STONECUTTING, cut, level).orElseThrow().value().assemble(cut);
        test.assertTrue(ties.is(Trainmod.TIE_BUNDLE.get()) && ties.getCount() == 1, "One plank cuts into one four-tie bundle");
        var diagonal = CraftingInput.of(2, 2, List.of(plank, empty, empty, new ItemStack(Items.SPRUCE_PLANKS)));
        var craftedTies = recipes.getRecipeFor(RecipeType.CRAFTING, diagonal, level).orElseThrow().value().assemble(diagonal);
        test.assertTrue(craftedTies.is(Trainmod.TIE_BUNDLE.get()) && craftedTies.getCount() == 2,
            "Two diagonal planks of any types craft two bundles");
        var rail = new ItemStack(Trainmod.RAIL.get());
        var straightInput = CraftingInput.of(3, 2, List.of(rail, empty, rail, empty, ties, empty));
        var straight = recipes.getRecipeFor(RecipeType.CRAFTING, straightInput, level).orElseThrow().value().assemble(straightInput);
        test.assertTrue(straight.is(Trainmod.TRACK_ITEMS.get(TrackGeometry.Kind.STRAIGHT).get()) && straight.getCount() == 4,
            "Two rails and a tie bundle make four single-block straights");
        var scrap = new ItemStack(Trainmod.TRACK_SCRAP.get());
        var incompleteScrap = CraftingInput.of(2, 2, List.of(scrap, empty, scrap, scrap));
        test.assertTrue(recipes.getRecipeFor(RecipeType.CRAFTING, incompleteScrap, level).isEmpty(),
            "Three scraps cannot refund a four-track batch");
        var recycleInput = CraftingInput.of(2, 2, List.of(scrap, scrap, scrap, scrap));
        var recycling = recipes.getRecipeFor(RecipeType.CRAFTING, recycleInput, level).orElseThrow().value();
        var reclaimedRails = recycling.assemble(recycleInput);
        var remainders = recycling.getRemainingItems(recycleInput);
        test.assertTrue(reclaimedRails.is(Trainmod.RAIL.get()) && reclaimedRails.getCount() == 2
            && remainders.stream().filter(stack -> stack.is(Trainmod.TIE_BUNDLE.get())).mapToInt(ItemStack::getCount).sum() == 1,
            "Four scraps return precisely the original pair of rails and tie bundle");
        var obsolete = CraftingInput.of(3, 3, List.of(rail, empty, rail, empty, ties, empty,
            empty, new ItemStack(Trainmod.BALLAST.get()), empty));
        test.assertTrue(recipes.getRecipeFor(RecipeType.CRAFTING, obsolete, level).isEmpty(),
            "The old recipe with ballast no longer matches");
        var bridge = CraftingInput.of(3, 3, List.of(rail, empty, rail, empty, ties, empty, rail, empty, rail));
        test.assertTrue(recipes.getRecipeFor(RecipeType.CRAFTING, bridge, level).orElseThrow().value().assemble(bridge)
            .is(Trainmod.TRACK_ITEMS.get(TrackGeometry.Kind.BRIDGE_STRAIGHT).get()), "Four rails reinforce a bridge track");
        var longer = CraftingInput.of(2, 2, List.of(straight, empty, straight, straight));
        test.assertTrue(recipes.getRecipeFor(RecipeType.CRAFTING, longer, level).orElseThrow().value().assemble(longer)
            .is(Trainmod.TRACK_ITEMS.get(TrackGeometry.Kind.STRAIGHT_3).get()), "Three individual tracks combine in any shape");
        test.startSequence()
            .thenWaitUntil(() -> test.assertTrue(machine.getItem(RailExtruder.OUTPUT).getCount() == 16,
                "The placed machine completes its cycle through the server ticker"))
            .thenExecute(() -> {
                try (var transaction = Transaction.openRoot()) {
                    var component = ItemResource.of(Trainmod.RAIL.get());
                    test.assertTrue(collect.extract(0, component, 16, transaction) == 16,
                        "Automation can collect the finished rails from underneath");
                    transaction.commit();
                }
            })
            .thenSucceed();
    }

    static void steamTenderControls(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        BlockPos start = isolatedTrack(test, 242);
        for (int z = 0; z <= 14; z++) RailwayGameTests.straight(level, start.south(z), 0);
        var engine = RailwayGameTests.vehicle(level, TrainVehicle.Kind.STEAM_LOCOMOTIVE, start.south(8));
        var tender = RailwayGameTests.vehicle(level, TrainVehicle.Kind.TENDER, start.south(4));
        var cursor = tender.cursor();
        tender.moveToRail(new RailNetwork.Position(cursor.rail(), cursor.distance(), -cursor.forward()));
        var driver = test.makeMockServerPlayerInLevel();
        var bystander = test.makeMockServerPlayerInLevel();
        couple(driver, engine, -1, tender, -1);
        tender.supplies.setItem(0, new ItemStack(Items.COAL));
        tender.supplies.setItem(1, new ItemStack(Items.WATER_BUCKET));
        driver.setPos(tender.position().add(0, 1, 0));
        driver.setShiftKeyDown(true);
        tender.interact(driver, InteractionHand.MAIN_HAND, new Vec3(0, 1.05, 0.24));
        test.assertTrue(tender.getControllingPassenger() == driver, "Sneak-using the reversed tender's top seat boards the driver");

        double original = engine.getZ();
        byte[] input = {0};
        test.onEachTick(() -> {
            if (input[0] == 0) tender.receiveControls(bystander, TrainControls.FORWARD);
            else tender.receiveControls(driver, input[0]);
        });
        test.startSequence()
            .thenExecuteAfter(8, () -> {
                test.assertTrue(engine.getZ() == original, "A player outside the seat cannot move the locomotive");
                input[0] = TrainControls.FORWARD;
            })
            .thenWaitUntil(() -> test.assertTrue(engine.getZ() < original - 1,
                "Forward input on a reversed tender moves in the tender's facing. Engine ticks " + engine.tickCount))
            .thenExecute(() -> {
                test.assertTrue(tender.supplies.getItem(1).is(Items.BUCKET) && tender.supplies.getItem(0).isEmpty(),
                    "Steam draws coal and water directly from the tender and leaves an empty bucket");
                input[0] = TrainControls.REVERSE;
            })
            .thenWaitUntil(() -> test.assertTrue(engine.getZ() > original - 0.2,
                "The same tender also drives in the opposite direction"))
            .thenExecute(() -> {
                driver.stopRiding();
                engine.discard();
                tender.discard();
            })
            .thenSucceed();
    }

    static void touchingTender(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos start = isolatedTrack(test, 63);
        for (int i = 0; i < 14; i++) RailwayGameTests.straight(level, start.south(i), 0);
        var engine = RailwayGameTests.vehicle(level, TrainVehicle.Kind.STEAM_LOCOMOTIVE, start.south(8));
        var tender = RailwayGameTests.vehicle(level, TrainVehicle.Kind.TENDER, start.south(5));
        var actor = test.makeMockServerPlayerInLevel();
        actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.COUPLER.get()));
        actor.setPos(engine.position().add(1, 0, -1));
        engine.interact(actor, InteractionHand.MAIN_HAND, new Vec3(0.23, 0.9, -1.05));
        test.assertTrue(engine.coupledId(1) == tender.getId() && tender.coupledId(0) == engine.getId(),
            "One near-end click links facing cars whose couplers are already close");
        engine.interact(actor, InteractionHand.MAIN_HAND, new Vec3(0.23, 0.9, -1.05));
        test.assertTrue(engine.coupledId(1) == tender.getId(), "Repeated body clicks leave a coupled joint in place");
        actor.setPos(tender.position().add(1, 0, -0.24));
        actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        tender.interact(actor, InteractionHand.MAIN_HAND, new Vec3(0, 1.03, -0.24));
        test.assertTrue(actor.getVehicle() == tender && !actor.isShiftKeyDown(), "A regular click on the tender seat boards");
        actor.stopRiding();
        tender.discard();
        engine.discard();
        test.succeed();
    }

    static void poweredImpactAndCrew(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos start = isolatedTrack(test, 306);
        for (int z = 0; z <= 14; z++) RailwayGameTests.straight(level, start.south(z), 0);
        var moving = RailwayGameTests.vehicle(level, TrainVehicle.Kind.LOCOMOTIVE, start.south(3));
        var freight = RailwayGameTests.vehicle(level, TrainVehicle.Kind.FLATCAR, start.south(7));
        var parked = RailwayGameTests.vehicle(level, TrainVehicle.Kind.LOCOMOTIVE, start.south(11));
        var position = parked.cursor();
        parked.moveToRail(new RailNetwork.Position(position.rail(), position.distance(), -position.forward()));
        moving.supplies.setItem(0, new ItemStack(Trainmod.DIESEL_BUCKET.get()));
        parked.supplies.setItem(0, new ItemStack(Trainmod.DIESEL_BUCKET.get()));
        var firstDriver = test.makeMockServerPlayerInLevel();
        var olderDriver = test.makeMockServerPlayerInLevel();
        olderDriver.setPos(parked.position());
        olderDriver.startRiding(parked);
        byte[] commands = {0, 0};
        test.onEachTick(() -> {
            moving.receiveControls(firstDriver, commands[0]);
            parked.receiveControls(olderDriver, commands[1]);
        });
        double[] joint = {0};
        test.startSequence()
            .thenExecuteAfter(4, () -> {
                firstDriver.setPos(moving.position());
                firstDriver.startRiding(moving);
                commands[0] = TrainControls.FORWARD;
            })
            .thenWaitUntil(() -> test.assertTrue(moving.coupledId(0) == freight.getId(),
                "A driven locomotive couples to standing stock on impact. Engine ticks " + moving.tickCount
                    + ", mounted " + (firstDriver.getVehicle() == moving) + ", engine " + moving.position()
                    + ", freight " + freight.position()))
            .thenWaitUntil(() -> test.assertTrue(parked.coupledId(0) == freight.getId(),
                "A driven consist also couples to a standing locomotive"))
            .thenExecute(() -> {
                joint[0] = moving.getZ();
                commands[0] |= TrainControls.HORN | TrainControls.BELL;
                commands[1] = TrainControls.FORWARD;
            })
            .thenExecuteAfter(3, () -> {
                test.assertTrue(moving.getZ() > joint[0] && moving.headlight() == 1 && parked.headlight() == -1,
                    "The moving engineer keeps control and the facing of each attached engine is respected");
                test.assertTrue(moving.hornActive() && parked.hornActive() && moving.bellActive() && parked.bellActive(),
                    "The selected engineer's horn and bell controls reach both locomotives");
                test.assertTrue(parked.supplies.getItem(0).is(Items.BUCKET),
                    "The attached locomotive uses its own fuel under the selected engineer's throttle");
                commands[0] = 0;
                joint[0] = moving.getZ();
            })
            .thenWaitUntil(() -> test.assertTrue(moving.getZ() < joint[0] - 0.15,
                "Once stopped, the crew member seated longest takes control"))
            .thenExecute(() -> {
                test.assertTrue(!moving.hornActive() && !parked.hornActive() && moving.bellActive() && parked.bellActive(),
                    "Releasing the horn stops it and the bell stays latched");
                commands[1] |= TrainControls.BELL;
            })
            .thenExecuteAfter(3, () -> {
                test.assertTrue(!moving.bellActive() && !parked.bellActive(), "A second bell press stops both bells");
                firstDriver.stopRiding();
                olderDriver.stopRiding();
                moving.discard();
                parked.discard();
                freight.discard();
            })
            .thenSucceed();
    }

    static void equalLocomotiveSpeeds(GameTestHelper test) {
        var level = test.getLevel();
        for (var kind : List.of(TrainVehicle.Kind.LOCOMOTIVE, TrainVehicle.Kind.ELECTRIC_SHUNTER)) {
            BlockPos start = isolatedTrack(test, kind == TrainVehicle.Kind.LOCOMOTIVE ? 55 : 58);
            for (int i = 0; i <= 14; i++) RailwayGameTests.straight(level, start.south(i), 0);
            var engine = RailwayGameTests.vehicle(level, kind, start.south(6));
            if (kind == TrainVehicle.Kind.LOCOMOTIVE) {
                engine.supplies.setItem(0, new ItemStack(Trainmod.DIESEL_BUCKET.get()));
            } else {
                var save = output(level);
                engine.addAdditionalSaveData(save);
                save.putInt("BatteryTicks", 200);
                engine.readAdditionalSaveData(input(level, save));
            }
            var driver = test.makeMockServerPlayerInLevel();
            driver.setPos(engine.position());
            driver.startRiding(engine);
            double forward = topSpeed(engine, driver, TrainControls.FORWARD);
            for (int i = 0; i < 8; i++) {
                engine.receiveControls(driver, (byte) 0);
                engine.tick();
            }
            double reverse = topSpeed(engine, driver, TrainControls.REVERSE);
            test.assertTrue(Math.abs(forward - 0.24) < 0.0001 && Math.abs(reverse + forward) < 0.0001,
                kind + " reaches the same speed in both directions");
            driver.stopRiding();
            engine.discard();
            level.getServer().getPlayerList().remove(driver);
        }
        test.succeed();
    }

    private static double topSpeed(TrainVehicle engine, ServerPlayer driver, byte direction) {
        double travel = 0;
        for (int i = 0; i < 12; i++) {
            double before = engine.getZ();
            engine.receiveControls(driver, direction);
            engine.tick();
            travel = engine.getZ() - before;
        }
        return travel;
    }

    static void electricReserve(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos start = isolatedTrack(test, 82);
        for (int z = 0; z <= 14; z++) RailwayGameTests.straight(level, start.south(z), 0);
        var shunter = RailwayGameTests.vehicle(level, TrainVehicle.Kind.ELECTRIC_SHUNTER, start.south(6));
        var driver = test.makeMockServerPlayerInLevel();
        driver.setPos(shunter.position());
        driver.startRiding(shunter);
        double startZ = shunter.getZ();
        test.onEachTick(() -> shunter.receiveControls(driver, test.getTick() < 8 || test.getTick() > 23 ? TrainControls.FORWARD : 0));
        test.runAtTickTime(8, () -> {
            test.assertTrue(shunter.getZ() == startZ && vehicleData(level, shunter).getIntOr("BatteryTicks", 0) == 0,
                "An empty new shunter stays parked off the wire");
            gantry(level, start.south(2));
            gantry(level, start.south(9));
        });
        test.runAtTickTime(23, () -> {
            test.assertTrue(vehicleData(level, shunter).getIntOr("BatteryTicks", 0) > 0,
                "The shunter charges while parked under a working span. Entity ticks: " + shunter.tickCount
                    + ", span: " + Catenary.powered(level, shunter.cursor()));
            level.removeBlock(start.south(9).above(CatenaryTower.Kind.GANTRY.height), false);
        });
        test.runAtTickTime(46, () -> {
            test.assertTrue(shunter.getZ() > startZ + 1 && vehicleData(level, shunter).getIntOr("BatteryTicks", 0) == 0,
                "The shunter spends its reserve on unwired track after the span breaks");
            driver.stopRiding();
            shunter.discard();
            test.succeed();
        });
    }

    static void catenaryRoutes(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = test.absolutePos(new BlockPos(0, 105, 0));
        for (int z = 0; z <= 18; z++) {
            RailwayGameTests.straight(level, root.south(z), 0);
            level.setBlockAndUpdate(root.south(z).above(CatenaryTower.Kind.GANTRY.height + 1), Blocks.STONE.defaultBlockState());
        }
        gantry(level, root);
        var middle = RailwayGameTests.at(level, root.south(8));
        test.assertTrue(!Catenary.powered(level, middle), "An isolated tower provides no power");
        setTower(level, root.south(16).west(), Trainmod.CATENARY_SINGLE.get(), Direction.EAST);
        test.assertTrue(!Catenary.powered(level, middle), "Different wire heights do not connect");
        gantry(level, root.south(17));
        test.assertTrue(!Catenary.powered(level, middle), "A span longer than 16 blocks provides no power");
        gantry(level, root.south(16));
        test.assertTrue(Catenary.powered(level, middle), "A continuous sixteen-block gantry span powers the line");
        var first = Catenary.attachments(level, root.above(CatenaryTower.Kind.GANTRY.height), level.getBlockState(root.above(CatenaryTower.Kind.GANTRY.height))).getFirst();
        var span = Catenary.spansFrom(level, first, 1).getFirst();
        test.assertTrue(span.wire().stream().allMatch(p -> p.y == middle.point().y + 4
            && p.y < root.getY() + 5), "The shared span puts the wire exactly four blocks above the rail and below the roof");
        var bore = new TrainVehicle(Trainmod.VEHICLES.get(TrainVehicle.Kind.TUNNEL_BORE).get(), level, TrainVehicle.Kind.TUNNEL_BORE);
        test.assertTrue(bore.canMoveTo(level, RailwayGameTests.at(level, root.south(15)), java.util.Set.of()),
            "The bore clears gantry blocks in a five-block tunnel");
        BlockPos earlierMount = root.south(16).above(CatenaryTower.Kind.GANTRY.height - 1);
        level.removeBlock(root.south(16).above(CatenaryTower.Kind.GANTRY.height), false);
        setTower(level, earlierMount, Trainmod.CATENARY_GANTRY.get(), Direction.NORTH);
        var lower = Catenary.attachments(level, earlierMount, level.getBlockState(earlierMount)).getFirst();
        var mixedSpan = Catenary.spansFrom(level, first, 1).getFirst();
        test.assertTrue(Catenary.powered(level, middle)
            && lower.height() == middle.point().y + 3
            && mixedSpan.wire().getFirst().distanceToSqr(first.contact()) < 1e-10
            && mixedSpan.wire().getLast().distanceToSqr(lower.contact()) < 1e-10,
            "A mixed-height span stays powered and seats its wire under both gantry mounts");
        level.removeBlock(root.south(5), false);
        test.assertTrue(!Catenary.powered(level, middle), "Missing track breaks the span");

        BlockPos turnout = root.above(8);
        RailwayGameTests.piece(level, turnout, TrackGeometry.Kind.TURNOUT_45, 0, true);
        RailwayGameTests.straight(level, turnout.north(), 0);
        var straight = RailNetwork.railAt(level, turnout);
        BlockPos exit = BlockPos.containing(straight.end(true).add(0, 0, 0.1));
        RailwayGameTests.straight(level, exit, 0);
        gantry(level, turnout.north());
        gantry(level, exit);
        test.assertTrue(Catenary.powered(level, RailwayGameTests.at(level, turnout)),
            "The wired straight route through the turnout has power");
        level.setBlock(turnout, level.getBlockState(turnout).setValue(BlockStateProperties.POWERED, true),
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        test.assertTrue(!Catenary.powered(level, RailwayGameTests.at(level, turnout)),
            "The unwired branch lacks power when the turnout is thrown");

        var branch = RailNetwork.physicalRailsAt(level, turnout).get(1);
        Vec3 tangent = branch.path().tangent(branch.path().length);
        BlockPos branchExit = BlockPos.containing(branch.end(true).add(tangent.scale(0.1)));
        RailwayGameTests.piece(level, branchExit, TrackGeometry.Kind.DIAGONAL,
            tangent.x * tangent.z > 0 ? 14 : 2, true);
        gantry(level, branchExit);
        var entry = turnout.north().above(CatenaryTower.Kind.GANTRY.height);
        var attachment = Catenary.attachments(level, entry, level.getBlockState(entry)).getFirst();
        var ends = Catenary.spansFrom(level, attachment, 1).stream().map(spanAtSwitch -> spanAtSwitch.end().tower()).toList();
        test.assertTrue(ends.containsAll(List.of(exit.above(CatenaryTower.Kind.GANTRY.height), branchExit.above(CatenaryTower.Kind.GANTRY.height))),
            "The shared wire lookup reaches both turnout branches regardless of the selected route");
        test.assertTrue(Catenary.powered(level, RailwayGameTests.at(level, turnout)),
            "The newly wired diverging branch has power");
        BlockPos doubleTrack = root.above(16);
        for (int z = 0; z < 8; z++) {
            RailwayGameTests.straight(level, doubleTrack.south(z), 0);
            RailwayGameTests.straight(level, doubleTrack.east(3).south(z), 0);
        }
        for (int z : new int[]{0, 7}) {
            setTower(level, doubleTrack.south(z).west(), Trainmod.CATENARY_DOUBLE.get(), Direction.EAST);
        }
        for (int x : new int[]{0, 3}) {
            var under = RailwayGameTests.at(level, doubleTrack.offset(x, 0, 3));
            test.assertTrue(Catenary.powered(level, under), "A matching double-track tower pair powers both tracks");
        }
        var base = doubleTrack.west();
        var anchor = Catenary.attachments(level, base, level.getBlockState(base)).getFirst();
        test.assertTrue(anchor.height() == TrackGeometry.origin(doubleTrack).y + 3,
            "The cantilever wire is exactly three blocks above the railhead");
        BlockPos diagonal = root.above(25);
        for (int i = 0; i <= 8; i++) {
            RailwayGameTests.piece(level, diagonal.offset(i, 0, i), TrackGeometry.Kind.DIAGONAL, 14, true);
        }
        setTower(level, diagonal.west(), Trainmod.CATENARY_SINGLE.get(), Direction.EAST);
        test.assertTrue(Catenary.attachments(level, diagonal.west(), level.getBlockState(diagonal.west())).isEmpty(),
            "A pole too close to a diagonal remains outside the wire route");
        level.removeBlock(diagonal.west(), false);
        BlockPos remotePole = diagonal.west(2);
        setTower(level, remotePole, Trainmod.CATENARY_SINGLE.get(), Direction.EAST);
        setTower(level, diagonal.offset(6, 0, 8), Trainmod.CATENARY_SINGLE.get(), Direction.EAST);
        var diagonalAnchor = Catenary.attachments(level, remotePole, level.getBlockState(remotePole)).getFirst();
        test.assertTrue(diagonalAnchor.contact().subtract(TrackGeometry.origin(diagonal)).horizontalDistance() < 0.01
            && Catenary.powered(level, RailwayGameTests.at(level, diagonal.offset(4, 0, 4))),
            "Extended arms connect diagonal wires at the rail center with room around both poles");
        level.removeBlock(remotePole, false);
        level.removeBlock(diagonal.offset(6, 0, 8), false);
        var builder = test.makeMockServerPlayerInLevel();
        builder.setYRot(-135);
        var pole = Trainmod.CATENARY_SINGLE.get();
        BlockPos angledPole = diagonal.offset(-1, 0, 1);
        var context = new net.minecraft.world.item.context.BlockPlaceContext(builder, InteractionHand.MAIN_HAND,
            new ItemStack(pole), new BlockHitResult(Vec3.atCenterOf(angledPole), Direction.UP, angledPole, false));
        var angledState = pole.getStateForPlacement(context);
        test.assertTrue(angledState.getValue(CatenaryTower.DIAGONAL)
            && CatenaryTower.armStep(angledState).equals(new BlockPos(1, 0, -1)),
            "Diagonal player facing gives the pole a 45-degree arm");
        test.assertTrue(CatenaryTower.armStep(angledState.rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_90))
            .equals(new BlockPos(1, 0, 1)), "Rotating the tower preserves its diagonal orientation");
        level.setBlockAndUpdate(angledPole, angledState);
        level.setBlockAndUpdate(diagonal.offset(7, 0, 9), angledState);
        test.assertTrue(Catenary.powered(level, RailwayGameTests.at(level, diagonal.offset(4, 0, 4))),
            "Two diagonal poles connect directly across diagonal rails");
        BlockPos curve = root.above(32);
        RailwayGameTests.piece(level, curve, TrackGeometry.Kind.CURVE_SMALL, 0, true);
        setTower(level, curve.west(2), Trainmod.CATENARY_SINGLE.get(), Direction.EAST);
        BlockPos farSide = curve.offset(0, 0, 4);
        setTower(level, farSide, Trainmod.CATENARY_SINGLE.get(), Direction.EAST);
        var curvedRail = RailNetwork.railAt(level, curve);
        var curvedPosition = new RailNetwork.Position(curvedRail, curvedRail.path().length / 2, 1);
        test.assertTrue(Catenary.powered(level, curvedPosition), "The cantilever reaches and follows a curved rail centerline");
        BlockPos grade = root.above(40);
        RailwayGameTests.piece(level, grade, TrackGeometry.Kind.GRADE_8, 0, true);
        RailNetwork.Rail incline = RailNetwork.railAt(level, grade);
        BlockPos low = grade.west();
        BlockPos high = BlockPos.containing(incline.end(true).add(0, 0, -0.2)).west();
        setTower(level, low, Trainmod.CATENARY_SINGLE.get(), Direction.EAST);
        setTower(level, high, Trainmod.CATENARY_SINGLE.get(), Direction.EAST);
        var slopeAnchor = Catenary.attachments(level, low, level.getBlockState(low)).getFirst();
        var slopeWire = Catenary.spansFrom(level, slopeAnchor, 1).getFirst().wire();
        test.assertTrue(slopeWire.getLast().y - slopeWire.getFirst().y > 0.99
            && Catenary.powered(level, new RailNetwork.Position(incline, incline.path().length / 2, 1)),
            "A wire follows the grade between towers at different elevations and powers the train");
        BlockPos gradeMount = grade.south(5).above(4);
        gantry(level, grade.south(5));
        gantry(level, grade.south(8).above());
        var gradeAnchor = Catenary.attachments(level, gradeMount, level.getBlockState(gradeMount)).getFirst();
        var gantryWire = Catenary.spansFrom(level, gradeAnchor, 1).getFirst();
        test.assertTrue(gradeAnchor.height() == gradeMount.getY() + CatenaryTower.GANTRY_CONTACT
            && gantryWire.wire().getFirst().distanceToSqr(gradeAnchor.contact()) < 1e-10
            && gantryWire.wire().getLast().distanceToSqr(gantryWire.end().contact()) < 1e-10,
            "Grade wire meets the insulator below the beam at each gantry");
        test.succeed();
    }

    static void regulatorCargo(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos start = test.absolutePos(new BlockPos(0, 169, 0));
        var substrates = List.of(Blocks.SAND, Blocks.GRAVEL, Blocks.STONE, Blocks.COBBLESTONE, Blocks.DIRT,
            Blocks.GRASS_BLOCK, Blocks.DEEPSLATE);
        for (int i = 0; i < substrates.size(); i++) {
            BlockPos rail = start.south(i);
            RailwayGameTests.straight(level, rail, 0);
            level.setBlockAndUpdate(rail.below(2), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(rail.below(), substrates.get(i).defaultBlockState());
        }
        var regulator = RailwayGameTests.vehicle(level, TrainVehicle.Kind.BALLAST_CAR, start.south(2));
        var driver = test.makeMockServerPlayerInLevel();
        regulator.regulate(level, driver, start.below());
        test.assertTrue(level.getBlockState(start.below()).is(Blocks.SAND), "An empty regulator leaves the rail bed untouched");
        regulator.cargo.setItem(0, new ItemStack(Items.GRAVEL, 2));
        regulator.cargo.setItem(1, new ItemStack(Trainmod.BALLAST.get(), substrates.size() - 2));
        var denied = start.south(2).below();
        java.util.function.Consumer<BreakBlockEvent> deniedBreak = event -> {
            if (event.getPos().equals(denied) && event.getPlayer() == driver) event.setCanceled(true);
        };
        var sounds = new java.util.ArrayList<BlockPos>();
        java.util.function.Consumer<PlayLevelSoundEvent.AtPosition> recordSound = event -> {
            if (event.getLevel() == level && event.getSound() != null && event.getSound().value() == SoundEvents.GRAVEL_PLACE) {
                sounds.add(BlockPos.containing(event.getPosition()));
            }
        };
        NeoForge.EVENT_BUS.addListener(recordSound);
        try {
            NeoForge.EVENT_BUS.addListener(deniedBreak);
            try {
                regulator.regulate(level, driver, denied);
            } finally {
                NeoForge.EVENT_BUS.unregister(deniedBreak);
            }
            test.assertTrue(level.getBlockState(denied).is(Blocks.STONE) && regulator.cargo.getItem(0).getCount() == 2 && sounds.isEmpty(),
                "A denied replacement leaves the original block and gravel and plays no sound");
            for (int i = 0; i < substrates.size(); i++) {
                BlockPos below = start.south(i).below();
                regulator.regulate(level, driver, below);
                test.assertTrue(level.getBlockState(below).is(Trainmod.BALLAST.get()), "Eligible subgrade is replaced with one ballast");
            }
            test.assertTrue(sounds.size() == substrates.size() && sounds.getLast().equals(start.south(substrates.size() - 1).below()),
                "Successful work plays the ballast placement sound at the bed");
        } finally {
            NeoForge.EVENT_BUS.unregister(recordSound);
        }
        test.assertTrue(regulator.cargo.countItem(Items.SAND) == 1 && regulator.cargo.countItem(Items.COBBLESTONE) == 2
            && regulator.cargo.countItem(Items.DIRT) == 2 && regulator.cargo.countItem(Items.COBBLED_DEEPSLATE) == 1,
            "Successful replacements recover the material from the old foundation");
        regulator.cargo.clearContent();
        BlockPos slope = start.offset(5, 0, 9);
        BlockPos bridge = slope.south(4);
        RailwayGameTests.straight(level, slope, 0);
        RailwayGameTests.piece(level, bridge, TrackGeometry.Kind.BRIDGE_STRAIGHT, 0, true);
        level.setBlockAndUpdate(bridge.below(), Trainmod.BALLAST.get().defaultBlockState());
        for (BlockPos rail : List.of(slope, bridge)) {
            for (int x = -5; x <= 5; x++) {
                level.setBlockAndUpdate(rail.offset(x, -6, 0), Blocks.STONE.defaultBlockState());
            }
        }
        regulator.cargo.setItem(0, new ItemStack(Items.GRAVEL, 25));
        regulator.regulate(level, driver, bridge.below());
        test.assertTrue(regulator.cargo.getItem(0).getCount() == 25 && level.getBlockState(bridge.below(2)).isAir(),
            "Bridge track skips cascading even if it sits on ballast");
        regulator.regulate(level, driver, slope.below());
        test.assertTrue(regulator.cargo.countItem(Items.GRAVEL) == 0 && regulator.cargo.countItem(Items.COBBLESTONE) == 1,
            "The layer and 24 shoulder blocks each consume one gravel, and the regulator recovers the foundation");
        regulator.cargo.clearContent();
        for (int depth = 1; depth <= 4; depth++) {
            for (int x = -5; x <= 5; x++) {
                var state = level.getBlockState(slope.offset(x, -1 - depth, 0));
                test.assertTrue(state.is(Trainmod.BALLAST.get()) == (Math.abs(x) <= depth) && !state.is(Blocks.GRAVEL),
                    "Gravel cargo produces railway ballast that widens by one block per side");
            }
        }
        test.assertTrue(level.getBlockState(slope.below(6)).is(Blocks.STONE), "The ballast cascade ends four layers below the track foundation");
        BlockPos wide = start.offset(10, 0, 0);
        RailwayGameTests.straight(level, wide, 0);
        for (int side = -1; side <= 1; side++) {
            level.setBlockAndUpdate(wide.below().east(side), Blocks.GRASS_BLOCK.defaultBlockState());
            if (side != 0) level.setBlockAndUpdate(wide.below(2).east(side), Blocks.STONE.defaultBlockState());
        }
        driver.setPos(regulator.position());
        var menu = new BallastRegulatorMenu(1, driver.getInventory(), regulator.cargo, regulator);
        test.assertTrue(!menu.working() && menu.clickMenuButton(driver, BallastRegulatorMenu.TOGGLE_WORK) && menu.working(),
            "The cargo screen can activate the regulator");
        test.assertTrue(menu.mode() == TrainVehicle.BallastMode.NARROW && menu.clickMenuButton(driver, BallastRegulatorMenu.CYCLE_MODE)
            && menu.mode() == TrainVehicle.BallastMode.WIDE,
            "The cargo screen toggles the regulator from narrow to wide");
        level.setBlockAndUpdate(wide.east(), Blocks.WILDFLOWERS.defaultBlockState());
        level.setBlockAndUpdate(wide.west(), Blocks.LEAF_LITTER.defaultBlockState());
        regulator.cargo.setItem(0, new ItemStack(Items.GRAVEL, 3));
        regulator.regulate(level, driver, wide.below());
        test.assertTrue(regulator.cargo.countItem(Items.GRAVEL) == 0 && regulator.cargo.countItem(Items.DIRT) == 3
            && level.getBlockState(wide.below()).is(Trainmod.BALLAST.get())
            && level.getBlockState(wide.below().east()).is(Trainmod.BALLAST.get())
            && level.getBlockState(wide.below().west()).is(Trainmod.BALLAST.get())
            && level.getBlockState(wide.east()).isAir() && level.getBlockState(wide.west()).isAir()
            && regulator.cargo.countItem(Items.WILDFLOWERS) == 1 && regulator.cargo.countItem(Items.LEAF_LITTER) == 1,
            "Wide clears and recovers ground cover from both shoulders while paying only for the three crest blocks");
        regulator.cargo.clearContent();
        BlockPos wideSlope = wide.south(18);
        RailwayGameTests.straight(level, wideSlope, 0);
        for (int side = -6; side <= 6; side++) {
            level.setBlockAndUpdate(wideSlope.below(6).east(side), Blocks.STONE.defaultBlockState());
        }
        BlockPos litter = wideSlope.below(5).east(5);
        level.setBlockAndUpdate(litter, Blocks.LEAF_LITTER.defaultBlockState());
        BlockPos grassBase = wideSlope.below(5).west(2);
        level.setBlockAndUpdate(grassBase.below(), Blocks.DIRT.defaultBlockState());
        level.setBlock(grassBase, Blocks.TALL_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(grassBase.above(), Blocks.TALL_GRASS.defaultBlockState()
            .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
        regulator.cargo.setItem(0, new ItemStack(Items.GRAVEL, 35));
        regulator.regulate(level, driver, wideSlope.below());
        test.assertTrue(regulator.cargo.countItem(Items.GRAVEL) == 0 && regulator.cargo.countItem(Items.COBBLESTONE) == 1
            && regulator.cargo.countItem(Items.LEAF_LITTER) == 1,
            "Wide mode fills the slope through leaf litter and tall grass, paying only for placed ballast");
        regulator.cargo.clearContent();
        for (int depth = 0; depth <= 4; depth++) {
            for (int side = -6; side <= 6; side++) {
                var state = level.getBlockState(wideSlope.below(1 + depth).east(side));
                test.assertTrue(state.is(Trainmod.BALLAST.get()) == (Math.abs(side) <= depth + 1),
                    "The wide bed keeps a 45-degree slope from its three-block crest through all four lower layers");
            }
        }
        test.assertTrue(level.getBlockState(wideSlope.below(6)).is(Blocks.STONE), "The wide bed ends at the same depth as narrow");
        var restored = new TrainVehicle(Trainmod.VEHICLES.get(regulator.kind).get(), level, regulator.kind);
        restored.readAdditionalSaveData(vehicleData(level, regulator));
        test.assertTrue(restored.ballastMode() == TrainVehicle.BallastMode.WIDE, "The selected ballast width survives a reload");
        test.assertTrue(menu.clickMenuButton(driver, BallastRegulatorMenu.CYCLE_MODE)
            && menu.mode() == TrainVehicle.BallastMode.TRACK_ONLY, "Wide cycles to Track Only");
        restored.readAdditionalSaveData(vehicleData(level, regulator));
        test.assertTrue(restored.ballastMode() == TrainVehicle.BallastMode.TRACK_ONLY, "Track Only survives a reload");
        test.assertTrue(restored.ballastWorking(), "The operating state survives a reload");

        BlockPos bare = wide.south(5);
        RailwayGameTests.piece(level, bare, TrackGeometry.Kind.UNBALLASTED_STRAIGHT, 14, true);
        level.setBlockAndUpdate(bare.below(), Blocks.COBBLESTONE.defaultBlockState());
        regulator.cargo.setItem(0, new ItemStack(Items.GRAVEL));
        regulator.regulate(level, driver, bare.below());
        BlockState finished = level.getBlockState(bare);
        test.assertTrue(regulator.cargo.isEmpty() && menu.reserve() == 7
            && finished.is(Trainmod.TRACK.get(TrackGeometry.Kind.STRAIGHT).get()) && TrackBlock.ballasted(finished)
            && finished.getValue(TrackBlock.ROTATION) == 14 && level.getBlockState(bare.below()).is(Blocks.COBBLESTONE),
            "Track Only finishes a bore-laid section with one eighth of a gravel and leaves its foundation intact");
        restored.readAdditionalSaveData(vehicleData(level, regulator));
        test.assertTrue(restored.ballastRemainder() == 7, "Partially used ballast survives a reload");
        var kind = TrackGeometry.Kind.STRAIGHT_8;
        BlockPos strip = wide.east(3);
        var shape = kind.shape(0, true);
        for (BlockPos offset : shape.cells()) {
            level.setBlockAndUpdate(strip.offset(offset).below(), Blocks.COBBLESTONE.defaultBlockState());
        }
        test.assertTrue(TrackItem.place(level, driver, kind, new TrackItem.Placement(strip, 0, true, false, shape, 0, List.of())),
            "The eight-block kit can be laid on cobblestone");
        for (int i = 0; i < shape.cells().size(); i++) {
            BlockPos cell = strip.offset(shape.cells().get(i));
            var track = Trainmod.TRACK.get(kind).get();
            test.assertTrue(TrackBlock.unballasted(level.getBlockState(cell)), "Placed straight sections start bare");
            restored.regulate(level, driver, cell.below());
            test.assertTrue(level.getBlockState(cell).is(track) && track.index(level.getBlockState(cell)) == i
                && TrackBlock.ballasted(level.getBlockState(cell)) == (i < 7)
                && level.getBlockState(cell.below()).is(Blocks.COBBLESTONE),
                "Only seven more sections can be filled and each keeps the kit geometry and foundation");
        }
        test.assertTrue(restored.ballastRemainder() == 0 && restored.cargo.isEmpty(),
            "The bore section and seven kit sections use exactly one item");

        BlockPos grass = wide.south(11);
        RailwayGameTests.straight(level, grass, 0);
        level.setBlockAndUpdate(grass, level.getBlockState(grass).setValue(TrackBlock.CONDITION, TrackBlock.Condition.UNBALLASTED));
        for (int side = -1; side <= 1; side++) {
            level.setBlockAndUpdate(grass.below().east(side), Blocks.GRASS_BLOCK.defaultBlockState());
        }
        regulator.cargo.setItem(0, new ItemStack(Items.GRAVEL, 64));
        regulator.regulate(level, driver, grass.below());
        test.assertTrue(TrackBlock.ballasted(level.getBlockState(grass))
            && level.getBlockState(grass.below()).is(Blocks.GRASS_BLOCK)
            && level.getBlockState(grass.below().east()).is(Blocks.GRASS_BLOCK)
            && level.getBlockState(grass.below().west()).is(Blocks.GRASS_BLOCK)
            && regulator.cargo.getItem(0).getCount() == 64 && regulator.ballastRemainder() == 6,
            "Track Only fills the ties from the reserve and leaves all three replaceable blocks below untouched");

        BlockPos ready = wide.south(9);
        RailwayGameTests.straight(level, ready, 0);
        level.setBlockAndUpdate(ready.below(), Trainmod.BALLAST.get().defaultBlockState());
        level.setBlockAndUpdate(ready.below(3), Blocks.STONE.defaultBlockState());
        regulator.cargo.setItem(0, new ItemStack(Items.GRAVEL));
        regulator.regulate(level, driver, ready.below());
        test.assertTrue(regulator.cargo.getItem(0).getCount() == 1 && level.getBlockState(ready.below(2)).isAir(),
            "Track Only skips finished track and leaves supported spaces below it empty");
        test.assertTrue(menu.clickMenuButton(driver, BallastRegulatorMenu.CYCLE_MODE)
            && menu.mode() == TrainVehicle.BallastMode.NARROW, "Track Only cycles to Narrow");
        menu.removed(driver);
        RailwayGameTests.straight(level, start.north(), 0);
        var gondola = RailwayGameTests.vehicle(level, TrainVehicle.Kind.GONDOLA, start.north());
        couple(driver, regulator, -1, gondola, 1);
        level.setBlockAndUpdate(start.north().below(2), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(start.north().below(), Blocks.SAND.defaultBlockState());
        regulator.cargo.setItem(0, new ItemStack(Items.GRAVEL, 2));
        for (int slot = 1; slot < regulator.cargo.getContainerSize(); slot++) {
            regulator.cargo.setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        regulator.regulate(level, driver, start.north().below());
        test.assertTrue(regulator.cargo.getItem(0).getCount() == 1 && gondola.cargo.countItem(Items.SAND) == 1
            && level.getBlockState(start.north().below()).is(Trainmod.BALLAST.get()),
            "The regulator sends roadbed drops to a coupled gondola when its own cargo is full");
        regulator.discard();
        gondola.discard();

        BlockPos pushTrack = start.east(6);
        for (int z = 0; z <= 8; z++) {
            BlockPos cell = pushTrack.south(z);
            RailwayGameTests.straight(level, cell, 0);
            level.setBlockAndUpdate(cell, level.getBlockState(cell).setValue(TrackBlock.CONDITION, TrackBlock.Condition.UNBALLASTED));
        }
        var pushed = RailwayGameTests.vehicle(level, TrainVehicle.Kind.BALLAST_CAR, pushTrack.south(2));
        pushed.cycleBallastMode();
        pushed.cycleBallastMode();
        pushed.toggleBallastWork(driver);
        pushed.cargo.setItem(0, new ItemStack(Items.GRAVEL));
        for (int tick = 0; tick < 12; tick++) {
            pushed.push(0, 0, 0.08);
            pushed.tick();
        }
        test.assertTrue(TrackBlock.ballasted(level.getBlockState(pushTrack.south(4))),
            "An active regulator also fills the track when pushed without a locomotive");
        pushed.discard();
        test.succeed();
    }

    static void regulatorRechecksSlopes(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos start = isolatedTrack(test, 211).south(2);
        for (int z = 0; z <= 10; z++) RailwayGameTests.straight(level, start.south(z), 0);
        BlockPos worked = start.south(4);
        BlockPos crest = worked.below().east();
        BlockPos slope = worked.below(2).east(2);
        level.setBlockAndUpdate(crest, Blocks.OAK_LOG.defaultBlockState());
        level.setBlockAndUpdate(crest.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(slope.below(), Blocks.STONE.defaultBlockState());

        var player = test.makeMockServerPlayerInLevel();
        var regulator = RailwayGameTests.vehicle(level, TrainVehicle.Kind.BALLAST_CAR, start.south(2));
        regulator.cycleBallastMode();
        regulator.toggleBallastWork(player);
        regulator.cargo.setItem(0, new ItemStack(Items.GRAVEL, 64));
        for (int tick = 0; tick < 24; tick++) {
            regulator.push(0, 0, 0.1);
            regulator.tick();
        }
        var brake = VehicleHitboxes.of(regulator.kind).controls().stream()
            .filter(control -> control.component() == VehicleHitboxes.Component.HANDBRAKE).findFirst().orElseThrow();
        var frame = new VehicleHitboxes.Frame(regulator.position(), regulator.getYRot(), regulator.getXRot(), 0);
        player.setPos(regulator.position().add(1, 0, 0));
        regulator.interact(player, InteractionHand.MAIN_HAND, frame.world(brake.box().getCenter()).subtract(regulator.position()));
        var ballast = Trainmod.BALLAST.get();
        test.assertTrue(regulator.getZ() > worked.getZ() + 2 && regulator.handbrakeApplied()
            && level.getBlockState(worked.below()).is(ballast)
            && level.getBlockState(crest).is(Blocks.OAK_LOG) && level.getBlockState(slope).isAir(),
            "The regulator passes the log, fills the center, and leaves a gap in the blocked slope");
        int gravelAfterPass = regulator.cargo.countItem(Items.GRAVEL);
        test.runAtTickTime(65, () -> {
            regulator.toggleBallastWork(player);
            level.removeBlock(crest, false);
        });
        test.runAtTickTime(82, () -> {
            test.assertTrue(level.getBlockState(crest).isAir() && level.getBlockState(slope).isAir(),
                "The paused regulator leaves the cleared gap alone");
            regulator.toggleBallastWork(player);
        });
        test.runAtTickTime(110, () -> {
            test.assertTrue(level.getBlockState(crest).is(ballast) && level.getBlockState(slope).is(ballast),
                "The parked regulator fills the previously blocked crest and the slope beyond it");
            test.assertTrue(regulator.cargo.countItem(Items.GRAVEL) == gravelAfterPass - 2,
                "The regulator pays once for each newly filled block");
            regulator.discard();
            level.getServer().getPlayerList().remove(player);
            test.succeed();
        });
    }

    static void regulatorWalls(GameTestHelper test) {
        var level = test.getLevel();
        var driver = test.makeMockServerPlayerInLevel();
        var ballast = Trainmod.BALLAST.get();
        try {
            for (boolean diagonal : new boolean[]{false, true}) {
                int rotation = diagonal ? 14 : 0;
                BlockPos rail = isolatedTrack(test, diagonal ? 207 : 198);
                RailwayGameTests.straight(level, rail, rotation);
                BlockPos crest = rail.below();
                level.setBlockAndUpdate(crest, ballast.defaultBlockState().setValue(TrackBlock.ROTATION, rotation));
                for (BlockPos floor : BlockPos.betweenClosed(crest.offset(-5, -5, -5), crest.offset(5, -5, 5))) {
                    level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                }
                for (int depth = 0; depth <= 4; depth++) {
                    level.setBlockAndUpdate(crest.west().below(depth), Blocks.STONE.defaultBlockState());
                }
                level.setBlockAndUpdate(crest.below(), Blocks.STONE.defaultBlockState());
                BlockPos unsupported = crest.offset(4, -4, diagonal ? -4 : 0);
                level.setBlockAndUpdate(unsupported.below(), Blocks.AIR.defaultBlockState());

                for (boolean wide : new boolean[]{false, true}) {
                    TrackWork.ballast(level, driver, crest, 64 * TrackWork.BALLAST_PORTIONS,
                        wide ? TrainVehicle.BallastMode.WIDE : TrainVehicle.BallastMode.NARROW,
                        List.of(new SimpleContainer(27)));
                    test.assertTrue(level.getBlockState(crest.west()).is(Blocks.STONE), "The regulator preserves the wall at the crest");
                    for (int depth = 2; depth <= 4; depth++) {
                        BlockPos behindWall = crest.offset(-2, -depth, diagonal ? 2 : 0);
                        test.assertTrue(level.getBlockState(behindWall).isAir(),
                            "The regulator leaves the far side empty at depth " + depth + ", diagonal=" + diagonal + ", wide=" + wide);
                    }
                    BlockPos besideCore = crest.offset(1, -1, diagonal ? -1 : 0);
                    test.assertTrue(level.getBlockState(besideCore).is(ballast), "Gravel settles beside the solid core on the open side");
                    test.assertTrue(level.getBlockState(unsupported).isAir(), "A reachable gap without a floor stays empty");
                }
            }
        } finally {
            level.getServer().getPlayerList().remove(driver);
        }
        test.succeed();
    }

    static void trackWear(GameTestHelper test) {
        var level = test.getLevel();
        var driver = test.makeMockServerPlayerInLevel();
        var protectedDriver = test.makeMockServerPlayerInLevel();
        BlockPos start = isolatedTrack(test, 216).south(7);
        BlockPos protectedStart = start.east(5);
        var kind = TrackGeometry.Kind.STRAIGHT_3;
        var shape = kind.shape(0, true);
        for (BlockPos line : List.of(start, protectedStart)) {
            for (int z = -7; z <= 7; z++) RailwayGameTests.straight(level, line.south(z), 0);
            test.assertTrue(TrackItem.place(level, driver, kind, new TrackItem.Placement(line, 0, true, false, shape, 0, List.of())),
                "Both lines start with a bare multi-block kit");
            // Isolate the failure to the middle block of the kit.
            for (BlockPos cell : List.of(line, line.south(2))) {
                level.setBlockAndUpdate(cell, level.getBlockState(cell).setValue(TrackBlock.CONDITION, TrackBlock.Condition.BALLASTED));
            }
        }
        var engine = RailwayGameTests.vehicle(level, TrainVehicle.Kind.LOCOMOTIVE, start.north(3));
        var guarded = RailwayGameTests.vehicle(level, TrainVehicle.Kind.LOCOMOTIVE, protectedStart.north(3));
        var regulator = RailwayGameTests.vehicle(level, TrainVehicle.Kind.BALLAST_CAR, protectedStart.north(6));
        regulator.cargo.setItem(0, new ItemStack(Items.GRAVEL, 2));
        regulator.cycleBallastMode();
        regulator.cycleBallastMode();
        couple(protectedDriver, guarded, -1, regulator, 1);
        protectedDriver.setPos(regulator.position());
        protectedDriver.setShiftKeyDown(true);
        regulator.interact(protectedDriver, InteractionHand.MAIN_HAND, Vec3.ZERO);
        protectedDriver.setShiftKeyDown(false);
        for (var locomotive : List.of(engine, guarded)) {
            locomotive.supplies.setItem(0, new ItemStack(Trainmod.DIESEL_BUCKET.get()));
        }
        driver.setPos(engine.position());
        driver.startRiding(engine);
        protectedDriver.setPos(guarded.position());
        protectedDriver.startRiding(guarded);
        long seed = 0;
        while (true) {
            engine.getRandom().setSeed(seed);
            if (engine.getRandom().nextFloat() < 0.05f) break;
            seed++;
        }
        long failingRoll = seed;
        test.onEachTick(() -> {
            engine.getRandom().setSeed(failingRoll);
            guarded.getRandom().setSeed(failingRoll);
            engine.receiveControls(driver, TrainControls.FORWARD);
            guarded.receiveControls(protectedDriver, TrainControls.FORWARD);
        });
        BlockPos damaged = start.south();
        test.startSequence()
            .thenWaitUntil(() -> {
                test.assertTrue(TrackBlock.broken(level.getBlockState(damaged)), "An uncovered consist can damage bare track");
                test.assertTrue(TrackBlock.ballasted(level.getBlockState(protectedStart.south())),
                    "An active regulator protects the leading locomotive and fills the rails when it follows");
            })
            .thenExecute(() -> {
                var first = RailwayGameTests.at(level, start);
                var last = RailwayGameTests.at(level, start.south(2));
                test.assertTrue(RailNetwork.advance(level, first, 0.4) != null
                    && RailNetwork.advance(level, first, 2) == null && RailNetwork.advance(level, last, -2) == null,
                    "The intact parts remain usable and the damaged middle blocks both directions");
                test.assertTrue(RailNetwork.advance(level, engine.cursor(), -0.5) != null,
                    "The engine can reverse away after breaking track");
                test.assertTrue(TrackWork.ballast(level, driver, damaged.below(), 8, TrainVehicle.BallastMode.TRACK_ONLY, List.of()) == 0,
                    "Damaged track must be recycled");
                test.assertTrue(level.getBlockState(damaged).getShape(level, damaged).max(Direction.Axis.X) > 1,
                    "The displaced rail and ties can be targeted for recycling");
                var hit = new BlockHitResult(TrackGeometry.origin(damaged), Direction.UP, damaged, false);
                driver.stopRiding();
                driver.setPos(TrackGeometry.origin(damaged));
                driver.gameMode.useItemOn(driver, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
                driver.gameMode.useItemOn(driver, level, ItemStack.EMPTY, InteractionHand.OFF_HAND, hit);
                driver.gameMode.useItemOn(driver, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
                var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(damaged).inflate(1));
                var straight = Trainmod.TRACK_ITEMS.get(TrackGeometry.Kind.STRAIGHT).get();
                test.assertTrue(List.of(start, damaged, start.south(2)).stream().allMatch(cell -> level.getBlockState(cell).isAir())
                    && drops.size() == 1 && drops.getFirst().getItem().is(straight) && drops.getFirst().getItem().getCount() == 3,
                    "Repeated clicks remove the kit and return a single stack of its three ingredients");

                BlockPos single = damaged.east(2);
                RailwayGameTests.straight(level, single, 0);
                level.setBlockAndUpdate(single, level.getBlockState(single).setValue(TrackBlock.CONDITION, TrackBlock.Condition.BROKEN));
                driver.gameMode.useItemOn(driver, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                    new BlockHitResult(TrackGeometry.origin(single), Direction.UP, single, false));
                var scrap = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(single).inflate(0.5));
                test.assertTrue(level.getBlockState(single).isAir() && scrap.size() == 1
                    && scrap.getFirst().getItem().is(Trainmod.TRACK_SCRAP.get()) && scrap.getFirst().getItem().getCount() == 1,
                    "A single straight returns one scrap from its four-piece batch");
                protectedDriver.stopRiding();
                for (var player : List.of(driver, protectedDriver)) level.getServer().getPlayerList().remove(player);
                engine.discard();
                guarded.discard();
                regulator.discard();
            })
            .thenSucceed();
    }

    static void regulatorGrade(GameTestHelper test) {
        var level = test.getLevel();
        // Keep the locomotive in the test's ticking chunk until the trailing regulator crosses the bridge.
        BlockPos low = isolatedTrack(test, 226).south();
        var grade = TrackGeometry.Kind.GRADE_8;
        for (int i = 1; i <= 7; i++) RailwayGameTests.straight(level, low.north(i), 0);
        for (BlockPos offset : grade.shape(0, true).cells()) {
            level.setBlockAndUpdate(low.offset(offset.getX(), -1, offset.getZ()), Blocks.STONE.defaultBlockState());
        }
        var driver = test.makeMockServerPlayerInLevel();
        var kit = Trainmod.TRACK_ITEMS.get(grade).get();
        driver.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(kit));
        driver.setYRot(0);
        driver.setPos(TrackGeometry.origin(low.north()));
        kit.useOn(new UseOnContext(driver, InteractionHand.MAIN_HAND,
            new BlockHitResult(TrackGeometry.origin(low.north()).add(0, 0, 0.47), Direction.UP, low.north(), false)));
        BlockPos upper = low.south(8).above();
        test.assertTrue(level.getBlockState(upper.below()).isAir() && RailNetwork.railAt(level, upper) != null,
            "A placed 1:8 grade can leave a supported gap beneath the uppermost track block");
        BlockPos bridge = upper.south();
        RailwayGameTests.piece(level, bridge, TrackGeometry.Kind.BRIDGE_STRAIGHT, 0, true);
        for (int i = 1; i <= 9; i++) RailwayGameTests.straight(level, bridge.south(i), 0);
        var engine = RailwayGameTests.vehicle(level, TrainVehicle.Kind.LOCOMOTIVE, low.north(2));
        var regulator = RailwayGameTests.vehicle(level, TrainVehicle.Kind.BALLAST_CAR, low.north(5));
        engine.supplies.setItem(0, new ItemStack(Trainmod.DIESEL_BUCKET.get()));
        regulator.cargo.setItem(0, new ItemStack(Items.GRAVEL, 32));
        couple(driver, engine, -1, regulator, 1);
        test.assertTrue(engine.coupledId(1) == regulator.getId(), "The locomotive and regulator are coupled");
        driver.setPos(regulator.position());
        driver.setShiftKeyDown(true);
        regulator.interact(driver, InteractionHand.MAIN_HAND, Vec3.ZERO);
        driver.setShiftKeyDown(false);
        driver.setPos(engine.position());
        driver.startRiding(engine);
        test.onEachTick(() -> engine.receiveControls(driver, TrainControls.FORWARD));
        test.startSequence()
            .thenWaitUntil(() -> test.assertTrue(regulator.getZ() > bridge.getZ() + 1,
                "The regulator crosses the bridge rail. Engine " + engine.position().subtract(TrackGeometry.origin(low))
                    + ", regulator " + regulator.position().subtract(TrackGeometry.origin(low)) + ", controls "
                    + RailwayGameTests.overlayMessages(driver).stream().map(net.minecraft.network.chat.Component::getString).distinct().toList()))
            .thenExecute(() -> {
                for (BlockPos cell : grade.shape(0, true).cells()) {
                    BlockPos track = low.offset(cell);
                    test.assertTrue(TrackBlock.ballasted(level.getBlockState(track))
                        && level.getBlockState(track.below()).is(Trainmod.BALLAST.get()),
                        "The moving regulator ballasts each grade segment, including the raised end " + cell);
                }
                test.assertTrue(level.getBlockState(bridge.below()).is(Blocks.STONE), "Driving over the bridge rail leaves its foundation alone");
                driver.stopRiding();
                engine.discard();
                regulator.discard();
                level.getServer().getPlayerList().remove(driver);
            })
            .thenSucceed();
    }

    static void workTrainCargoSorting(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos track = isolatedTrack(test, 226);
        RailwayGameTests.piece(level, track, TrackGeometry.Kind.STRAIGHT_12, 0, true);
        RailwayGameTests.piece(level, track.east(5), TrackGeometry.Kind.STRAIGHT_12, 0, true);
        var regulator = RailwayGameTests.vehicle(level, TrainVehicle.Kind.BALLAST_CAR, track.south(9));
        var flatcar = RailwayGameTests.vehicle(level, TrainVehicle.Kind.FLATCAR, track.south(6));
        var gondola = RailwayGameTests.vehicle(level, TrainVehicle.Kind.GONDOLA, track.south(3));
        var siding = RailwayGameTests.vehicle(level, TrainVehicle.Kind.GONDOLA, track.offset(5, 0, 5));
        var player = test.makeMockServerPlayerInLevel();
        var customName = net.minecraft.core.component.DataComponents.CUSTOM_NAME;
        var label = Component.literal("Reserved bolts");
        var bolts = new ItemStack(Items.IRON_NUGGET, 4);
        bolts.set(customName, label);
        regulator.cargo.setItem(4, bolts);
        flatcar.cargo.setItem(2, bolts.copyWithCount(6));
        flatcar.cargo.setItem(5, new ItemStack(Items.IRON_NUGGET, 2));
        flatcar.cargo.setItem(6, new ItemStack(Items.GRAVEL, 18));
        gondola.cargo.setItem(4, new ItemStack(Items.GRAVEL, 12));
        gondola.cargo.setItem(5, new ItemStack(Trainmod.RAIL.get(), 9));
        siding.cargo.setItem(4, new ItemStack(Items.GRAVEL, 27));
        test.startSequence()
            .thenExecute(() -> {
                couple(player, regulator, -1, flatcar, 1);
                test.assertTrue(regulator.coupledId(1) == flatcar.getId(), "The first freight car is coupled");
            })
            .thenExecuteAfter(22, () -> {
                test.assertTrue(flatcar.cargo.getItem(6).getCount() == 18 && regulator.cargo.getItem(4).getCount() == 4,
                    "With all work cars off, cargo stays where it was loaded");
                regulator.toggleBallastWork(player);
            })
            .thenExecuteAfter(22, () -> {
                test.assertTrue(regulator.cargo.countItem(Items.GRAVEL) == 18 && regulator.cargo.countItem(Items.IRON_NUGGET) == 0
                    && flatcar.cargo.countItem(Items.IRON_NUGGET) == 12 && flatcar.cargo.getItems().stream()
                        .anyMatch(stack -> stack.is(Items.IRON_NUGGET) && stack.getCount() == 10 && label.equals(stack.get(customName))),
                    "The regulator takes gravel and matching named stacks merge separately from unmarked items");
                test.assertTrue(gondola.cargo.getItem(4).getCount() == 12 && siding.cargo.getItem(4).getCount() == 27,
                    "Nearby uncoupled stock keeps its cargo");
                couple(player, flatcar, -1, gondola, 1);
                test.assertTrue(flatcar.coupledId(1) == gondola.getId(), "The gondola joins the running sort");
            })
            .thenExecuteAfter(22, () -> {
                test.assertTrue(regulator.cargo.countItem(Items.GRAVEL) == 30 && flatcar.cargo.countItem(Trainmod.RAIL.get()) == 9,
                    "Newly coupled cars contribute to the regulator's supplies and the track load");
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.COUPLER.get()));
                player.setPos(flatcar.position().add(1, 0, 0));
                flatcar.interact(player, InteractionHand.MAIN_HAND, flatcar.couplerOffset(1, flatcar.getYRot(), 0, 0));
                test.assertTrue(flatcar.coupledId(1) != gondola.getId(), "The gondola is detached");
                gondola.cargo.setItem(10, new ItemStack(Items.GRAVEL, 5));
            })
            .thenExecuteAfter(22, () -> {
                test.assertTrue(gondola.cargo.getItem(10).getCount() == 5 && regulator.cargo.countItem(Items.GRAVEL) == 30
                    && siding.cargo.getItem(4).getCount() == 27, "Detached and unrelated stock is outside the running sort");
                for (var car : List.of(regulator, flatcar, gondola, siding)) car.discard();
                level.getServer().getPlayerList().remove(player);
            })
            .thenSucceed();
    }

    static void workTrainBallastSharing(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos track = isolatedTrack(test, 234);
        RailwayGameTests.piece(level, track, TrackGeometry.Kind.STRAIGHT_12, 0, true);
        var first = RailwayGameTests.vehicle(level, TrainVehicle.Kind.BALLAST_CAR, track.south(9));
        var freight = RailwayGameTests.vehicle(level, TrainVehicle.Kind.FLATCAR, track.south(6));
        var second = RailwayGameTests.vehicle(level, TrainVehicle.Kind.BALLAST_CAR, track.south(3));
        var player = test.makeMockServerPlayerInLevel();
        var ballast = Trainmod.BALLAST.get().asItem();
        first.cargo.setItem(0, new ItemStack(Items.GRAVEL, 7));
        freight.cargo.setItem(0, new ItemStack(Items.GRAVEL, 8));
        freight.cargo.setItem(1, new ItemStack(ballast, 10));

        test.startSequence()
            .thenExecute(() -> {
                couple(player, first, -1, freight, 1);
                couple(player, freight, -1, second, 1);
                test.assertTrue(first.loadedTrain(level).size() == 3, "Both ballast regulators are coupled through the flatcar");
                first.toggleBallastWork(player);
            })
            .thenExecuteAfter(22, () -> {
                test.assertTrue(ballastCount(first) == 13 && ballastCount(second) == 12 && freight.cargo.isEmpty(),
                    "Both regulators share the combined supply, and the running car gets the odd block");
                test.assertTrue(first.cargo.countItem(Items.GRAVEL) + second.cargo.countItem(Items.GRAVEL) == 15
                    && first.cargo.countItem(ballast) + second.cargo.countItem(ballast) == 10, "Sorting preserves both item types");
                freight.cargo.setItem(0, new ItemStack(Items.GRAVEL, 5));
            })
            .thenExecuteAfter(22, () -> {
                test.assertTrue(ballastCount(first) == 15 && ballastCount(second) == 15 && freight.cargo.isEmpty(),
                    "The next sort rebalances ballast added to the train");
                for (int slot = 0; slot < first.cargo.getContainerSize(); slot++) {
                    first.cargo.setItem(slot, new ItemStack(Items.GRAVEL, 64));
                    second.cargo.setItem(slot, new ItemStack(ballast, 64));
                }
                freight.cargo.setItem(0, new ItemStack(ballast, 32));
            })
            .thenExecuteAfter(22, () -> {
                test.assertTrue(ballastCount(first) == 27 * 64 && ballastCount(second) == 27 * 64
                    && ballastCount(freight) == 32, "Full stacks fill both regulators and leave the surplus in freight");
                for (var car : List.of(first, freight, second)) car.discard();
                level.getServer().getPlayerList().remove(player);
            })
            .thenSucceed();
    }

    private static int ballastCount(TrainVehicle car) {
        return car.cargo.getItems().stream().filter(TrainVehicle::ballastSupply).mapToInt(ItemStack::getCount).sum();
    }

    static void boreWorkTrain(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos end = isolatedTrack(test, 72).south(10);
        level.getChunkAt(end.south(8));
        for (int i = 0; i <= 10; i++) RailwayGameTests.straight(level, end.north(i), 0);
        BlockPos brick = end.south();
        BlockPos deck = end.south(2);
        BlockPos gap = end.south(3);
        BlockPos grass = end.south(4);
        level.setBlockAndUpdate(brick.below(), Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(deck.below(), Trainmod.STEEL_GIRDER.get().defaultBlockState()
            .setValue(BridgeBlock.AXIS, Direction.Axis.Z));
        level.setBlockAndUpdate(grass.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        for (int y = 0; y <= 6; y++) level.setBlockAndUpdate(brick.above(y), Blocks.OAK_LOG.defaultBlockState());
        BlockPos branch = brick.above(7).east();
        level.setBlockAndUpdate(branch, Blocks.OAK_LOG.defaultBlockState());
        var pig = net.minecraft.world.entity.EntityTypes.PIG.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        pig.setNoAi(true);
        pig.setPos(Vec3.atBottomCenterOf(deck));
        level.addFreshEntity(pig);

        var bore = RailwayGameTests.vehicle(level, TrainVehicle.Kind.TUNNEL_BORE, end.north(2));
        var gondola = RailwayGameTests.vehicle(level, TrainVehicle.Kind.GONDOLA, end.north(6));
        var flatcar = RailwayGameTests.vehicle(level, TrainVehicle.Kind.FLATCAR, end.north(9));
        var driver = test.makeMockServerPlayerInLevel();
        try {
            GameType.SURVIVAL.updatePlayerAbilities(driver.getAbilities());
            couple(driver, bore, -1, gondola, 1);
            test.assertTrue(bore.coupledId(1) == gondola.getId(), "The gondola is the bore's trailing supply car");
            couple(driver, gondola, -1, flatcar, 1);
            test.assertTrue(gondola.coupledId(1) == flatcar.getId(), "The sorted track supplies can ride in a coupled flatcar");
            bore.supplies.setItem(TrainVehicle.RAIL_SUPPLY, new ItemStack(Trainmod.RAIL.get(), 1));
            gondola.cargo.setItem(0, new ItemStack(Trainmod.RAIL.get(), 5));
            gondola.cargo.setItem(1, new ItemStack(Trainmod.TIE_BUNDLE.get(), 2));
            flatcar.cargo.setItem(0, new ItemStack(Trainmod.RAIL.get(), 6));
            flatcar.cargo.setItem(1, new ItemStack(Trainmod.TIE_BUNDLE.get(), 2));
            flatcar.cargo.setItem(2, new ItemStack(Items.COBBLESTONE));
            driver.setPos(bore.position());
            test.assertTrue(driver.startRiding(bore), "The driver boards the bore");
            for (int tick = 0; tick < 75; tick++) {
                bore.receiveControls(driver, (byte) (TrainControls.FORWARD | TrainControls.WORK));
                bore.tick();
            }
            var bridge = Trainmod.TRACK.get(TrackGeometry.Kind.BRIDGE_STRAIGHT).get();
            test.assertTrue(level.getBlockState(brick).is(bridge) && level.getBlockState(deck).is(bridge)
                && level.getBlockState(brick.below()).is(Blocks.STONE_BRICKS)
                && level.getBlockState(deck.below()).is(Trainmod.STEEL_GIRDER.get()),
                "The bore lays reinforced track on both bridge foundations. Status: " + RailwayGameTests.overlayMessages(driver));
            test.assertTrue(RailNetwork.railAt(level, gap) != null && RailNetwork.railAt(level, grass) != null
                && level.getBlockState(gap.below()).is(Blocks.COBBLESTONE)
                && level.getBlockState(grass.below()).is(Blocks.GRASS_BLOCK),
                "The bore fills an unsupported center and retains a sturdy grass foundation");
            test.assertTrue(bore.supplies.isEmpty() && gondola.cargo.countItem(Trainmod.RAIL.get()) == 0
                && gondola.cargo.countItem(Trainmod.TIE_BUNDLE.get()) == 0
                && gondola.cargo.countItem(Items.COBBLESTONE) == 0 && flatcar.cargo.isEmpty(),
                "Four sections spend twelve rails, four tie bundles, and one cobblestone across the three cars");
            test.assertTrue(gondola.cargo.countItem(Items.OAK_LOG) == 8 && level.getBlockState(branch).isAir(),
                "The excavation follows logs above and diagonally beyond its face");
            test.assertTrue(pig.isAlive() && Math.abs(pig.getX() - bore.getX()) > 1,
                "The bore pushes a mob to the side and continues laying track");
        } finally {
            driver.stopRiding();
            bore.discard();
            gondola.discard();
            flatcar.discard();
            pig.discard();
            level.getServer().getPlayerList().remove(driver);
        }
        test.succeed();
    }

    static void treecuttersCrew(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = isolatedTrack(test, 258).south(7);
        for (int z = -5; z <= 5; z++) {
            RailwayGameTests.straight(level, root.south(z), 0);
            for (int x = -7; x <= 7; x++) {
                level.setBlockAndUpdate(root.offset(x, -1, z), Blocks.DIRT.defaultBlockState());
            }
        }
        var passengerCar = RailwayGameTests.vehicle(level, TrainVehicle.Kind.TREECUTTERS_PASSENGER_CAR, root);
        var gondola = RailwayGameTests.vehicle(level, TrainVehicle.Kind.GONDOLA, root.north(3));
        BlockPos stump = root.east(6);
        BlockPos branch = stump.above(5).east();
        BlockPos distant = root.west(7);
        BlockPos coveredTrack = root.south(2).above(4);
        for (int i = 0; i <= 4; i++) level.setBlockAndUpdate(stump.above(i), Blocks.OAK_LOG.defaultBlockState());
        level.setBlockAndUpdate(branch, Blocks.OAK_LOG.defaultBlockState());
        level.setBlockAndUpdate(distant, Blocks.OAK_LOG.defaultBlockState());
        level.setBlockAndUpdate(coveredTrack.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(coveredTrack, Blocks.BIRCH_LOG.defaultBlockState());
        var operator = test.makeMockServerPlayerInLevel();
        var workers = new java.util.ArrayList<TreecuttersWorker>();
        test.runAtTickTime(4, () -> {
            test.assertTrue(level.getBlockState(stump).is(Blocks.OAK_LOG)
                && passengerCar.getFirstPassenger() == null && !passengerCar.treecuttersActive(), "A new car starts with its lantern off");
            couple(operator, passengerCar, -1, gondola, 1);
            test.assertTrue(passengerCar.coupledId(1) == gondola.getId(), "The passenger car couples to a regular gondola");
            useTreecuttersControl(test, operator, passengerCar, VehicleHitboxes.Component.CRAFTING_TABLE);
            test.assertTrue(operator.containerMenu instanceof CraftingMenu, "The table opens the 3 by 3 crafting grid");
            var crafting = (CraftingMenu) operator.containerMenu;
            crafting.getInputGridSlots().getFirst().setByPlayer(new ItemStack(Items.OAK_LOG));
            test.assertTrue(crafting.getResultSlot().getItem().is(Items.OAK_PLANKS) && crafting.stillValid(operator),
                "The onboard crafting table makes vanilla recipes");
            operator.closeContainer();
            useTreecuttersControl(test, operator, passengerCar, VehicleHitboxes.Component.TREECUTTERS_LANTERN);
            var restored = new TrainVehicle(Trainmod.VEHICLES.get(passengerCar.kind).get(), level, passengerCar.kind);
            restored.readAdditionalSaveData(vehicleData(level, passengerCar));
            test.assertTrue(passengerCar.treecuttersActive() && restored.treecuttersActive(), "The lit lantern state survives a reload");
        });
        test.runAtTickTime(12, () -> workers.add(level.getEntitiesOfClass(TreecuttersWorker.class, new AABB(root).inflate(10)).getFirst()));
        test.runAtTickTime(54, () -> {
            test.assertTrue(gondola.cargo.countItem(Items.OAK_LOG) == 6 && level.getBlockState(branch).isAir(),
                "The villager walks to the edge of the six-block radius and puts the whole tree in the gondola before the teleport timeout");
            test.assertTrue(level.getBlockState(distant).is(Blocks.OAK_LOG), "Logs outside the six-block radius stay intact");
            workers.getFirst().kill(level);
        });
        BlockPos floating = root.west(2).above(5);
        test.runAtTickTime(60, () -> {
            test.assertTrue(passengerCar.getFirstPassenger() instanceof TreecuttersWorker replacement
                && replacement != workers.getFirst(), "The passenger car respawns killed crew");
            workers.add((TreecuttersWorker) passengerCar.getFirstPassenger());
            level.removeBlock(floating.atY(root.getY() - 1), false);
            level.setBlockAndUpdate(floating, Blocks.BIRCH_LOG.defaultBlockState());
        });
        test.runAtTickTime(84, () -> {
            test.assertTrue(level.getBlockState(floating).is(Blocks.BIRCH_LOG) && workers.getLast().getY() < floating.getY() - 2,
                "The worker tries reaching an overhead log on foot before teleporting");
        });
        test.runAtTickTime(130, () -> {
            test.assertTrue(level.getBlockState(floating).isAir() && gondola.cargo.countItem(Items.BIRCH_LOG) == 1,
                "The worker still teleports after the walking timeout when the trunk has no ground underneath");
            test.assertTrue(level.getBlockState(coveredTrack).is(Blocks.BIRCH_LOG),
                "The worker preserves logs above a nearby roof even when the car is outside");
            var worker = workers.getLast();
            worker.stopRiding();
            Vec3 tooFar = passengerCar.position().add(0, 30, 0);
            worker.teleportTo(tooFar.x, tooFar.y, tooFar.z);
        });
        BlockPos tunnelLog = root.above(4);
        test.runAtTickTime(135, () -> {
            test.assertTrue(workers.getLast().isRemoved() && passengerCar.getFirstPassenger() instanceof TreecuttersWorker,
                "The passenger car replaces crew that go out of range");
            workers.add((TreecuttersWorker) passengerCar.getFirstPassenger());
            level.setBlockAndUpdate(tunnelLog.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(tunnelLog, Blocks.OAK_LOG.defaultBlockState());
        });
        test.runAtTickTime(205, () -> {
            test.assertTrue(level.getBlockState(tunnelLog.below()).is(Blocks.STONE)
                && level.getBlockState(tunnelLog).is(Blocks.OAK_LOG) && workers.getLast().getVehicle() == passengerCar,
                "Under a non-log tunnel roof the worker stays aboard and leaves the log overhead intact");
            useTreecuttersControl(test, operator, passengerCar, VehicleHitboxes.Component.TREECUTTERS_LANTERN);
            test.assertTrue(!passengerCar.treecuttersActive() && workers.getLast().isRemoved(),
                "Extinguishing the lantern immediately dismisses the worker");
        });
        test.runAtTickTime(209, () -> {
            test.assertTrue(passengerCar.getFirstPassenger() == null, "The unlit car stays unstaffed");
            gondola.discard();
            passengerCar.discard();
            level.getServer().getPlayerList().remove(operator);
            test.succeed();
        });
    }

    static void treecuttersSwimmingAndOverhead(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = isolatedTrack(test, 20).south(7);
        for (int z = -4; z <= 4; z++) {
            RailwayGameTests.straight(level, root.south(z), 0);
            for (int x = -2; x <= 7; x++) {
                BlockPos floor = root.offset(x, -1, z);
                level.setBlockAndUpdate(floor.below(2), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(floor.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            }
        }
        for (int x = 2; x <= 4; x++) {
            for (int z = -3; z <= 3; z++) {
                level.setBlockAndUpdate(root.offset(x, -2, z), Blocks.WATER.defaultBlockState());
                level.setBlockAndUpdate(root.offset(x, -1, z), Blocks.WATER.defaultBlockState());
            }
            for (int z : new int[]{-4, 4}) {
                for (int y = 0; y <= 2; y++) level.setBlockAndUpdate(root.offset(x, y, z), Blocks.STONE.defaultBlockState());
            }
        }
        var car = RailwayGameTests.vehicle(level, TrainVehicle.Kind.TREECUTTERS_PASSENGER_CAR, root);
        BlockPos crown = root.east(6).above(50);
        level.setBlockAndUpdate(crown, Blocks.SPRUCE_LOG.defaultBlockState());
        var player = test.makeMockServerPlayerInLevel();
        boolean[] swam = {false};
        test.runAtTickTime(3, () -> {
            useTreecuttersControl(test, player, car, VehicleHitboxes.Component.TREECUTTERS_LANTERN);
            player.setPos(Vec3.atBottomCenterOf(root.west(2)));
        });
        test.onEachTick(() -> {
            for (var worker : level.getEntitiesOfClass(TreecuttersWorker.class, new AABB(root).inflate(8))) {
                swam[0] |= worker.isInWater();
            }
        });
        test.runAtTickTime(59, () -> {
            test.assertTrue(swam[0], "The villager enters and swims across the water to reach the trunk");
            test.assertTrue(level.getBlockState(crown).isAir()
                && !level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(crown).setMinY(root.getY() - 1).inflate(2),
                    item -> item.getItem().is(Items.SPRUCE_LOG)).isEmpty(),
                "The villager reaches fifty blocks overhead from dry ground before any teleport can occur");
            car.discard();
            level.getServer().getPlayerList().remove(player);
            test.succeed();
        });
    }

    static void treecuttersGroundDrops(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = isolatedTrack(test, 175).south(7);
        for (int z = -5; z <= 5; z++) {
            RailwayGameTests.straight(level, root.south(z), 0);
            for (int x = 1; x <= 5; x++) {
                level.setBlockAndUpdate(root.offset(x, -1, z), Blocks.DIRT.defaultBlockState());
            }
        }
        var car = RailwayGameTests.vehicle(level, TrainVehicle.Kind.TREECUTTERS_PASSENGER_CAR, root);
        var gondola = RailwayGameTests.vehicle(level, TrainVehicle.Kind.GONDOLA, root.north(3));
        BlockPos stump = root.east(3);
        level.setBlockAndUpdate(stump, Blocks.OAK_LOG.defaultBlockState());
        level.setBlockAndUpdate(stump.above(), Blocks.OAK_LOG.defaultBlockState());
        for (int i = 1; i < gondola.cargo.getContainerSize(); i++) {
            gondola.cargo.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        gondola.cargo.setItem(0, new ItemStack(Items.OAK_LOG, 63));
        Vec3 ground = Vec3.atBottomCenterOf(stump.south()).add(0, 0.25, 0);
        for (var item : List.of(Items.STICK, Items.OAK_SAPLING, Items.APPLE, Items.DIRT)) {
            var drop = new ItemEntity(level, ground.x, ground.y, ground.z, new ItemStack(item), 0, 0, 0);
            drop.setNoPickUpDelay();
            level.addFreshEntity(drop);
        }
        var player = test.makeMockServerPlayerInLevel();
        test.runAtTickTime(4, () -> {
            couple(player, car, -1, gondola, 1);
            test.assertTrue(car.coupledId(1) == gondola.getId(), "A regular gondola is coupled to receive the items");
            useTreecuttersControl(test, player, car, VehicleHitboxes.Component.TREECUTTERS_LANTERN);
            player.setPos(Vec3.atBottomCenterOf(root));
        });
        var area = new AABB(stump).inflate(3);
        test.runAtTickTime(50, () -> {
            test.assertTrue(level.getBlockState(stump).isAir() && gondola.cargo.countItem(Items.OAK_LOG) == 64
                && level.getEntitiesOfClass(ItemEntity.class, area, item -> item.getItem().is(Items.OAK_LOG))
                    .stream().mapToInt(item -> item.getItem().getCount()).sum() == 1,
                "The gondola takes the log it can fit and the other log drops at the tree");
            gondola.cargo.removeItem(0, 1);
            for (int i = 1; i <= 3; i++) gondola.cargo.setItem(i, ItemStack.EMPTY);
        });
        test.runAtTickTime(115, () -> {
            test.assertTrue(gondola.cargo.countItem(Items.OAK_LOG) == 64 && gondola.cargo.countItem(Items.STICK) == 1
                && gondola.cargo.countItem(Items.OAK_SAPLING) == 1 && gondola.cargo.countItem(Items.APPLE) == 1,
                "The villager picks up fallen logs and leaf drops after space opens in the gondola");
            test.assertTrue(gondola.cargo.countItem(Items.DIRT) == 0
                && !level.getEntitiesOfClass(ItemEntity.class, area, item -> item.getItem().is(Items.DIRT)).isEmpty(),
                "Other ground items stay where they were dropped");
            car.discard();
            gondola.discard();
            level.getServer().getPlayerList().remove(player);
            test.succeed();
        });
    }

    static void treecuttersCooperate(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = isolatedTrack(test, 142).south(2).west(6);
        for (int z = -1; z <= 13; z++) {
            for (int x = -2; x <= 14; x++) {
                level.setBlockAndUpdate(root.offset(x, -1, z), Blocks.DIRT.defaultBlockState());
            }
            for (int x : new int[]{0, 9, 12}) RailwayGameTests.straight(level, root.offset(x, 0, z), 0);
        }
        var lead = RailwayGameTests.vehicle(level, TrainVehicle.Kind.TREECUTTERS_PASSENGER_CAR, root.south(10));
        var mainCargo = RailwayGameTests.vehicle(level, TrainVehicle.Kind.GONDOLA, root.south(7));
        var flatcar = RailwayGameTests.vehicle(level, TrainVehicle.Kind.FLATCAR, root.south(4));
        var tail = RailwayGameTests.vehicle(level, TrainVehicle.Kind.FLATCAR, root.south());
        var helper = RailwayGameTests.vehicle(level, TrainVehicle.Kind.TREECUTTERS_PASSENGER_CAR, root.offset(12, 0, 3));
        var helperCargo = RailwayGameTests.vehicle(level, TrainVehicle.Kind.GONDOLA, root.offset(12, 0, 6));
        var siding = RailwayGameTests.vehicle(level, TrainVehicle.Kind.GONDOLA, root.offset(9, 0, 3));
        BlockPos oak = root.offset(4, 0, 1);
        BlockPos birch = root.offset(5, 0, 4);
        BlockPos foliage = oak.east().above();
        BlockPos decoration = oak.west().above();
        level.setBlockAndUpdate(oak, Blocks.OAK_LOG.defaultBlockState());
        level.setBlockAndUpdate(birch, Blocks.BIRCH_LOG.defaultBlockState());
        level.setBlockAndUpdate(foliage, Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.DISTANCE, 1));
        level.setBlockAndUpdate(decoration, Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true));
        var player = test.makeMockServerPlayerInLevel();
        test.runAtTickTime(4, () -> {
            couple(player, lead, -1, mainCargo, 1);
            couple(player, mainCargo, -1, flatcar, 1);
            couple(player, flatcar, -1, tail, 1);
            couple(player, helper, 1, helperCargo, -1);
            test.assertTrue(lead.loadedTrain(level).size() == 4 && helper.loadedTrain(level).size() == 2,
                "The two crews are on separate consists");
            useTreecuttersControl(test, player, lead, VehicleHitboxes.Component.TREECUTTERS_LANTERN);
            useTreecuttersControl(test, player, helper, VehicleHitboxes.Component.TREECUTTERS_LANTERN);
            player.setPos(Vec3.atBottomCenterOf(root.west(2)));
        });
        test.runAtTickTime(95, () -> {
            test.assertTrue(level.getBlockState(oak).isAir() && level.getBlockState(birch).isAir()
                && level.getBlockState(foliage).isAir() && level.getBlockState(decoration).is(Blocks.OAK_LEAVES),
                "The workers clear trees along the train and remove natural leaves around the trunks");
            int mainLogs = mainCargo.cargo.countItem(Items.OAK_LOG) + mainCargo.cargo.countItem(Items.BIRCH_LOG);
            int helperLogs = helperCargo.cargo.countItem(Items.OAK_LOG) + helperCargo.cargo.countItem(Items.BIRCH_LOG);
            test.assertTrue(mainLogs == 1 && helperLogs == 1 && siding.cargo.isEmpty(),
                "Nearby crews split the jobs and each uses only its own gondola. Logs: " + mainLogs + " / " + helperLogs
                    + ", crew ticks: " + lead.tickCount + " / " + helper.tickCount);
            for (var car : List.of(lead, mainCargo, flatcar, tail, helper, helperCargo, siding)) car.discard();
            level.getServer().getPlayerList().remove(player);
            test.succeed();
        });
    }

    static void treecuttersBallastClearance(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = isolatedTrack(test, 194).south(5);
        for (int z = -5; z <= 6; z++) {
            RailwayGameTests.straight(level, root.south(z), 0);
            level.setBlockAndUpdate(root.offset(6, -1, z), Blocks.DIRT.defaultBlockState());
        }
        var regulator = RailwayGameTests.vehicle(level, TrainVehicle.Kind.BALLAST_CAR, root);
        var crew = RailwayGameTests.vehicle(level, TrainVehicle.Kind.TREECUTTERS_PASSENGER_CAR, root.north(3));
        BlockPos leaf = root.below().east();
        level.setBlockAndUpdate(leaf.below(), Blocks.STONE.defaultBlockState());
        var player = test.makeMockServerPlayerInLevel();
        test.runAtTickTime(4, () -> {
            couple(player, regulator, -1, crew, 1);
            regulator.cycleBallastMode();
            regulator.toggleBallastWork(player);
            regulator.cargo.setItem(0, new ItemStack(Items.GRAVEL, 16));
            useTreecuttersControl(test, player, crew, VehicleHitboxes.Component.TREECUTTERS_LANTERN);
        });
        test.startSequence().thenWaitUntil(() -> test.assertTrue(crew.getFirstPassenger() instanceof TreecuttersWorker,
            "The worker boards before the regulator asks for help")).thenExecute(() -> {
            var worker = (TreecuttersWorker) crew.getFirstPassenger();
            worker.stopRiding();
            Vec3 footing = Vec3.atBottomCenterOf(root.east(6));
            worker.teleportTo(footing.x, footing.y, footing.z);
            level.setBlockAndUpdate(leaf, Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true));
            regulator.regulate(level, player, root.below());
            test.assertTrue(level.getBlockState(leaf).is(Blocks.OAK_LEAVES), "Leaves initially block the ballast crest");
        }).thenWaitUntil(() -> test.assertTrue(level.getBlockState(leaf).isAir(), "The worker clears the requested leaf"))
            .thenExecute(() -> {
                var worker = level.getEntitiesOfClass(TreecuttersWorker.class, new AABB(root).inflate(9)).getFirst();
                test.assertTrue(worker.position().subtract(Vec3.atCenterOf(leaf)).horizontalDistanceSqr() > 16,
                    "The worker clears the leaf from more than four blocks away");
                Vec3 inWay = Vec3.atBottomCenterOf(leaf);
                worker.teleportTo(inWay.x, inWay.y, inWay.z);
                regulator.regulate(level, player, root.below());
                test.assertTrue(level.getBlockState(leaf).isAir(), "The regulator leaves room for a worker in the placement cell");
                Vec3 footing = Vec3.atBottomCenterOf(root.east(6));
                worker.teleportTo(footing.x, footing.y, footing.z);
                regulator.regulate(level, player, root.below());
                test.assertTrue(level.getBlockState(leaf).is(Trainmod.BALLAST.get()), "The regulator fills the cleared crest");
                crew.discard();
                regulator.discard();
                level.getServer().getPlayerList().remove(player);
            }).thenSucceed();
    }

    static void treecuttersFromAbove(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = isolatedTrack(test, 279).south(5);
        for (int z = -3; z <= 3; z++) {
            RailwayGameTests.straight(level, root.south(z), 0);
            for (int x = 1; x <= 5; x++) level.setBlockAndUpdate(root.offset(x, -1, z), Blocks.DIRT.defaultBlockState());
        }
        var crew = RailwayGameTests.vehicle(level, TrainVehicle.Kind.TREECUTTERS_PASSENGER_CAR, root);
        BlockPos trunk = root.east(5);
        for (int y = 0; y < 8; y++) level.setBlockAndUpdate(trunk.above(y), Blocks.OAK_LOG.defaultBlockState());
        BlockPos edgeLeaf = trunk.east(2).above(7);
        BlockPos standingLog = edgeLeaf.east(2);
        BlockPos cutLeaf = trunk.west().above(7);
        level.getChunkAt(standingLog);
        level.setBlockAndUpdate(standingLog, Blocks.OAK_LOG.defaultBlockState());
        var leaves = Blocks.OAK_LEAVES.defaultBlockState();
        level.setBlockAndUpdate(edgeLeaf.east(), leaves.setValue(BlockStateProperties.DISTANCE, 1));
        level.setBlockAndUpdate(edgeLeaf, leaves.setValue(BlockStateProperties.DISTANCE, 2));
        level.setBlockAndUpdate(cutLeaf, leaves.setValue(BlockStateProperties.DISTANCE, 1));
        var player = test.makeMockServerPlayerInLevel();
        useTreecuttersControl(test, player, crew, VehicleHitboxes.Component.TREECUTTERS_LANTERN);
        test.startSequence().thenWaitUntil(() -> test.assertTrue(
            !level.getEntitiesOfClass(TreecuttersWorker.class, new AABB(root).inflate(10), worker -> !worker.isPassenger()).isEmpty(),
            "The worker dismounts and takes the tree job")).thenExecute(() -> {
                var worker = level.getEntitiesOfClass(TreecuttersWorker.class, new AABB(root).inflate(10)).getFirst();
                Vec3 top = Vec3.atBottomCenterOf(trunk.above(8));
                worker.teleportTo(top.x, top.y, top.z);
                worker.setOnGround(true);
            }).thenExecuteAfter(3, () -> {
                test.assertTrue(level.getBlockState(trunk).isAir() && level.getBlockState(trunk.above(7)).isAir(),
                    "The villager can cut down through the trunk while standing above it");
                test.assertTrue(level.getBlockState(cutLeaf).isAir() && level.getBlockState(edgeLeaf).is(Blocks.OAK_LEAVES)
                    && level.getBlockState(standingLog).is(Blocks.OAK_LOG),
                    "Pruning clears leaves by the felled trunk and keeps edge leaves two blocks from a standing log");
                crew.discard();
                level.getServer().getPlayerList().remove(player);
            }).thenSucceed();
    }

    private static void useTreecuttersControl(GameTestHelper test, ServerPlayer player, TrainVehicle car,
                                             VehicleHitboxes.Component component) {
        Vec3 target = VehicleHitboxes.of(car.kind).controls().stream().filter(control -> control.component() == component)
            .findFirst().orElseThrow().box().getCenter();
        Vec3 eye = target.add(2, 0, 0);
        var frame = new VehicleHitboxes.Frame(car.position(), car.getYRot(), car.getXRot(), 0);
        player.setPos(frame.world(eye).subtract(0, player.getEyeHeight(), 0));
        var picked = VehicleHitboxes.pick(car, eye, target);
        test.assertTrue(picked != null && picked.component() == component, "The " + component + " is selectable from the open side");
        car.interact(player, InteractionHand.MAIN_HAND, frame.world(picked.point()).subtract(car.position()));
    }

    static void boreTickets(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos anchor = test.absolutePos(new BlockPos(0, 160, 0));
        ChunkPos origin = ChunkPos.containing(anchor);
        BlockPos track = new BlockPos(origin.getMinBlockX() + 8, anchor.getY(), origin.getMinBlockZ());
        for (int i = -10; i < 26; i++) RailwayGameTests.straight(level, track.south(i), 0);
        var bore = RailwayGameTests.vehicle(level, TrainVehicle.Kind.TUNNEL_BORE, track.south(2));
        var tail = RailwayGameTests.vehicle(level, TrainVehicle.Kind.BOXCAR, track.north(2));
        var driver = test.makeMockServerPlayerInLevel();
        couple(driver, bore, -1, tail, 1);
        long rear = ChunkPos.pack(origin.x(), origin.z() - 1);
        long ahead = ChunkPos.pack(origin.x(), origin.z() + 1);
        test.runAtTickTime(6, () -> {
            var chunks = BoreTickets.chunks(level, bore.getUUID());
            test.assertTrue(chunks.containsAll(List.of(origin.pack(), rear, ahead)), "A parked bore owns tickets for itself, ahead, and its trailing car");
            driver.setPos(bore.position().add(0, 1, -1));
            driver.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.COUPLER.get()));
            bore.interact(driver, InteractionHand.MAIN_HAND, bore.couplerOffset(1, bore.getYRot(), 0, 0));
        });
        test.runAtTickTime(9, () -> {
            test.assertTrue(!BoreTickets.chunks(level, bore.getUUID()).contains(rear), "Uncoupling releases the trailing chunk");
            bore.moveToRail(RailwayGameTests.at(level, track.south(21)));
        });
        test.runAtTickTime(18, () -> {
            var chunks = BoreTickets.chunks(level, bore.getUUID());
            test.assertTrue(!chunks.contains(origin.pack()) && chunks.contains(ahead)
                && chunks.contains(ChunkPos.pack(origin.x(), origin.z() + 2)), "The bore updates tickets after changing chunks. Position: " + bore.blockPosition() + ", tickets: "
                    + chunks.stream().map(ChunkPos::unpack).toList());
            var nether = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
            var transition = new net.minecraft.world.level.portal.TeleportTransition(nether,
                new Vec3(20488.5, 120, 20482.5), Vec3.ZERO, 0, 0,
                net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING);
            var moved = bore.teleport(transition);
            test.assertTrue(moved instanceof TrainVehicle && BoreTickets.chunks(level, bore.getUUID()).isEmpty(),
                "Dimension transfer releases all old tickets");
            tail.discard();
            test.runAfterDelay(12, () -> {
                test.assertTrue(BoreTickets.chunks(nether, bore.getUUID()).contains(moved.chunkPosition().pack()),
                    "The bore obtains tickets in a destination chunk outside player simulation distance");
                moved.discard();
                test.assertTrue(BoreTickets.chunks(nether, bore.getUUID()).isEmpty(), "Removing a bore releases every owned ticket");
                test.succeed();
            });
        });
    }

    static void bridgesAndCabin(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos site = test.absolutePos(new BlockPos(0, 185, 0));
        var girder = Trainmod.STEEL_GIRDER.get();
        var stone = Trainmod.STONE_ARCH.get();
        var trestle = Trainmod.WOOD_TRESTLE.get();
        for (BridgeBlock bridge : List.of(girder, stone)) {
            BlockPos pier = bridge == girder ? site : site.east(3);
            int limit = bridge == girder ? 6 : 4;
            level.setBlockAndUpdate(pier.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pier.south(2 * limit + 1).below(), Blocks.STONE.defaultBlockState());
            for (int i = 0; i <= limit; i++) {
                setDeck(test, level, bridge, pier.south(i));
            }
            test.assertTrue(!bridge.canPlace(level, pier.south(limit + 1), Direction.Axis.Z),
                "A deck cannot exceed the limit when building from one pier");
            for (int i = 2 * limit + 1; i > limit; i--) setDeck(test, level, bridge, pier.south(i));
            BlockPos one = pier.south(limit).above();
            level.setBlockAndUpdate(one, Trainmod.TRACK.get(TrackGeometry.Kind.STRAIGHT).get().segment(0, true, 0));
            test.assertTrue(RailNetwork.railAt(level, one) != null, "Players can place rails as soon as a deck is placed");
        }
        BlockPos side = site.south(3).east();
        test.assertTrue(girder.canPlace(level, side, Direction.Axis.X), "A main girder supports a one-block side extension");
        level.setBlockAndUpdate(side, girder.defaultBlockState().setValue(BridgeBlock.AXIS, Direction.Axis.X));
        test.assertTrue(!girder.canPlace(level, side.east(), Direction.Axis.X),
            "A side extension cannot support another block farther out");
        level.setBlockAndUpdate(side.above(), Trainmod.CATENARY_SINGLE.get().defaultBlockState());
        test.assertTrue(level.getBlockState(side.above()).is(Trainmod.CATENARY_SINGLE.get()),
            "A catenary pole can stand on the side extension");
        setDeck(test, level, girder, site.above());
        setDeck(test, level, girder, site.above(2));
        test.assertTrue(girder.girderColumn(level, site) && girder.girderColumn(level, site.above())
            && girder.girderFeet(level, site.above(2)), "Stacked girders reuse the lower blocks as extended feet");

        for (BridgeBlock bridge : List.of(trestle, stone)) {
            var base = site.west(bridge == trestle ? 3 : 6);
            int cap = bridge == trestle ? 4 : 6;
            level.setBlockAndUpdate(base.below(), Blocks.STONE.defaultBlockState());
            for (int y = 0; y < cap; y++) setDeck(test, level, bridge, base.above(y));
            test.assertTrue(!bridge.canPlace(level, base.above(cap), Direction.Axis.Z), "Columns obey their height limit");
            if (bridge == trestle) {
                level.setBlockAndUpdate(base.west().below(), Blocks.STONE.defaultBlockState());
                test.assertTrue(bridge.canPlace(level, base.west(), Direction.Axis.Z), "Adjacent trestle columns can be placed");
                test.assertTrue(bridge.trestleDepth(level, base) == 3 && bridge.trestleDepth(level, base.above(3)) == 0,
                    "The trestle widens to the bottom and carries a deck at the top");
            }
        }

        BlockPos base = site.east(6);
        level.setBlockAndUpdate(base.below(), Blocks.STONE.defaultBlockState());
        var player = test.makeMockServerPlayerInLevel();
        player.setPos(base.getX() + 0.5, base.getY(), base.getZ() - 1.5);
        var kit = new ItemStack(Trainmod.SIGNAL_CABIN.get().asItem());
        player.setItemInHand(InteractionHand.MAIN_HAND, kit);
        var placed = kit.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(base.below()), Direction.UP, base.below(), false)));
        var cabin = Trainmod.SIGNAL_CABIN.get();
        test.assertTrue(level.getBlockState(base).is(cabin) && level.getBlockState(base.above()).is(cabin),
            "A cabin kit places both halves. Result: " + placed + ", item: " + kit + ", lower: "
                + level.getBlockState(base) + ", upper: " + level.getBlockState(base.above()));
        var bottom = level.getBlockState(base);
        var roofShape = level.getBlockState(base.above()).getShape(level, base.above());
        test.assertTrue(roofShape.max(Direction.Axis.Y) > 0.75 && roofShape.min(Direction.Axis.X) < 0
            && roofShape.max(Direction.Axis.Z) > 1, "The gabled roof rises above the eaves and overhangs the whole cabin");
        test.assertTrue(bottom.getCollisionShape(level, base).toAabbs().stream()
            .noneMatch(box -> box.contains(0.5, 0.5, 0.5)), "The cabin has an empty interior");
        test.useBlock(test.relativePos(base));
        test.assertTrue(level.getBlockState(base).getValue(BlockStateProperties.OPEN)
            && level.getBlockState(base.above()).getValue(BlockStateProperties.OPEN), "The cabin door opens across both halves");
        test.succeed();
    }

    static void wideGirder(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        var bridge = Trainmod.WIDE_STEEL_GIRDER.get();
        var player = test.makeMockServerPlayerInLevel();
        for (Direction.Axis axis : List.of(Direction.Axis.Z, Direction.Axis.X)) {
            BlockPos center = test.absolutePos(new BlockPos(axis == Direction.Axis.Z ? 0 : 8, 190, 0));
            Direction along = Direction.get(Direction.AxisDirection.POSITIVE, axis);
            Direction across = WideGirder.across(axis);
            BlockPos foundation = center.below();
            level.setBlockAndUpdate(foundation, Blocks.STONE.defaultBlockState());
            player.setPos(Vec3.atCenterOf(center.relative(along.getOpposite(), 2)));
            player.setYRot(axis == Direction.Axis.Z ? 0 : -90);
            var item = new ItemStack(bridge.asItem());
            player.setItemInHand(InteractionHand.MAIN_HAND, item);
            var context = new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(foundation), Direction.UP, foundation, false));
            BlockPos blocked = center.relative(across);
            level.setBlockAndUpdate(blocked, Blocks.STONE.defaultBlockState());
            test.assertTrue(item.useOn(context) == net.minecraft.world.InteractionResult.FAIL && level.getBlockState(center).isAir(),
                "A blocked wing prevents the entire wide section from placing");
            level.removeBlock(blocked, false);
            for (Direction side : List.of(across, across.getOpposite())) {
                level.setBlockAndUpdate(foundation.relative(side), Blocks.STONE.defaultBlockState());
            }
            test.assertTrue(item.useOn(context).consumesAction(), "One item places a wide girder section");
            for (int part = 0; part < 3; part++) {
                BlockPos cell = center.relative(across, part - WideGirder.CENTER);
                BlockState state = level.getBlockState(cell);
                var shape = state.getCollisionShape(level, cell);
                test.assertTrue(state.is(bridge) && state.getValue(BridgeBlock.AXIS) == axis
                    && state.getValue(WideGirder.PART) == part && WideGirder.center(cell, state).equals(center)
                    && Math.abs(shape.max(Direction.Axis.Y) - 1) < 1e-7,
                    "The center and both wings occupy their own blocks and meet the normal deck height");
                if (part == WideGirder.CENTER) {
                    test.assertTrue(shape.min(Direction.Axis.Y) == 0.5, "The deck center is open beneath the crossmembers");
                } else {
                    double outer = part == 0 ? 0.125 : 0.875;
                    Vec3 underGirder = axis == Direction.Axis.Z ? new Vec3(outer, 0.25, 0.5) : new Vec3(0.5, 0.25, outer);
                    test.assertTrue(shape.toAabbs().stream().anyMatch(box -> box.contains(underGirder)),
                        "Both wing I-beams sit beneath the outside girders on each axis");
                }
            }
            BlockPos next = center.relative(along);
            level.removeBlock(foundation, false);
            test.assertTrue(bridge.canPlace(level, next, axis), "The wide bridge can span from a pier along its centerline");
            level.setBlockAndUpdate(next, Trainmod.STEEL_GIRDER.get().defaultBlockState().setValue(BridgeBlock.AXIS, axis));
            test.assertTrue(bridge.canPlace(level, next.relative(along), axis), "Both steel deck widths can share a span");
            var track = Trainmod.TRACK.get(TrackGeometry.Kind.BRIDGE_STRAIGHT).get();
            level.setBlockAndUpdate(center.above(), track.segment(axis == Direction.Axis.Z ? 0 : 12, true, 0));
            test.assertTrue(RailNetwork.railAt(level, center.above()) != null, "Bridge rail is supported on the timber deck");
            level.removeBlock(center.above(), false);
            player.gameMode.destroyBlock(blocked);
            test.assertTrue(level.getBlockState(center).isAir() && level.getBlockState(center.relative(across.getOpposite())).isAir(),
                "Mining a wing lifts the entire section");
        }
        level.getServer().getPlayerList().remove(player);
        test.succeed();
    }

    private static BlockPos isolatedTrack(GameTestHelper test, int height) {
        BlockPos pos = test.absolutePos(new BlockPos(0, height, 0));
        var chunk = ChunkPos.containing(pos);
        return new BlockPos(chunk.getMinBlockX() + 8, pos.getY(), chunk.getMinBlockZ() + 1);
    }

    private static void setDeck(GameTestHelper test, ServerLevel level, BridgeBlock block, BlockPos pos) {
        test.assertTrue(block.canPlace(level, pos, Direction.Axis.Z), "The deck segment is within reach of ground or a pier");
        level.setBlockAndUpdate(pos, block.defaultBlockState().setValue(BridgeBlock.AXIS, Direction.Axis.Z));
    }

    private static void gantry(ServerLevel level, BlockPos rail) {
        setTower(level, rail.above(CatenaryTower.Kind.GANTRY.height), Trainmod.CATENARY_GANTRY.get(), Direction.NORTH);
    }

    private static void setTower(ServerLevel level, BlockPos pos, CatenaryTower tower, Direction facing) {
        level.setBlockAndUpdate(pos, tower.defaultBlockState().setValue(CatenaryTower.FACING, facing));
    }

    private static void couple(ServerPlayer player, TrainVehicle first, int firstEnd, TrainVehicle second, int secondEnd) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.COUPLER.get()));
        player.setPos(first.position().add(1, 0, 0));
        int end = firstEnd > 0 ? 0 : 1;
        first.interact(player, InteractionHand.MAIN_HAND, first.couplerOffset(end, first.getYRot(), 0, 0));
        if (first.coupledId(end) != second.getId()) {
            player.setPos(second.position().add(1, 0, 0));
            second.interact(player, InteractionHand.MAIN_HAND, second.couplerOffset(secondEnd > 0 ? 0 : 1, second.getYRot(), 0, 0));
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    private static VehicleFuel reloadFuel(ServerLevel level, VehicleFuel fuel) {
        var saved = output(level);
        fuel.save(saved);
        var restored = new VehicleFuel();
        restored.load(input(level, saved));
        return restored;
    }

    private static ValueInput vehicleData(ServerLevel level, TrainVehicle vehicle) {
        var saved = output(level);
        vehicle.addAdditionalSaveData(saved);
        return input(level, saved);
    }

    private static TagValueOutput output(ServerLevel level) {
        return TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
    }

    private static ValueInput input(ServerLevel level, TagValueOutput output) {
        return TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult());
    }
}
