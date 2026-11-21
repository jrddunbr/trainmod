package org.ja4.trainmod;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
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
        test.assertTrue(recipes.getRecipeFor(RecipeType.CRAFTING, crafted, level).orElseThrow().value()
            .assemble(crafted).is(Trainmod.BALLAST.get().asItem()), "One gravel crafts one ballast");
        var cut = new SingleRecipeInput(gravel);
        test.assertTrue(recipes.getRecipeFor(RecipeType.STONECUTTING, cut, level).orElseThrow().value()
            .assemble(cut).is(Trainmod.BALLAST.get().asItem()), "The stonecutter also converts gravel into ballast");

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
        test.assertTrue(Catenary.powered(level, middle)
            && Catenary.attachments(level, earlierMount, level.getBlockState(earlierMount)).getFirst().height() == middle.point().y + 4,
            "Existing lower gantry blocks still support the newly raised wire");
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
        test.succeed();
    }

    static void regulatorCargo(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos start = test.absolutePos(new BlockPos(0, 135, 0));
        var substrates = List.of(Blocks.SAND, Blocks.GRAVEL, Blocks.STONE, Blocks.COBBLESTONE, Blocks.DIRT);
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
        regulator.cargo.setItem(1, new ItemStack(Trainmod.BALLAST.get(), 3));
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
        test.assertTrue(regulator.cargo.isEmpty(), "Each successful replacement spends exactly one gravel or ballast");
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
        test.assertTrue(regulator.cargo.isEmpty(), "The ballast layer and 24 shoulder blocks each consume one gravel item");
        for (int depth = 1; depth <= 4; depth++) {
            for (int x = -5; x <= 5; x++) {
                var state = level.getBlockState(slope.offset(x, -1 - depth, 0));
                test.assertTrue(state.is(Trainmod.BALLAST.get()) == (Math.abs(x) <= depth) && !state.is(Blocks.GRAVEL),
                    "Gravel cargo produces railway ballast that widens by one block per side");
            }
        }
        test.assertTrue(level.getBlockState(slope.below(6)).is(Blocks.STONE), "The ballast cascade ends four layers below the track foundation");
        regulator.discard();
        test.succeed();
    }

    static void regulatorGrade(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos low = isolatedTrack(test, 226).south(6);
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
        var engine = RailwayGameTests.vehicle(level, TrainVehicle.Kind.LOCOMOTIVE, low.north(3));
        var regulator = RailwayGameTests.vehicle(level, TrainVehicle.Kind.BALLAST_CAR, low.north(6));
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
                "The regulator completes the climb and crosses the bridge rail"))
            .thenExecute(() -> {
                for (BlockPos cell : grade.shape(0, true).cells()) {
                    test.assertTrue(level.getBlockState(low.offset(cell).below()).is(Trainmod.BALLAST.get()),
                        "The moving regulator ballasts each grade segment, including the raised end " + cell);
                }
                test.assertTrue(level.getBlockState(bridge.below()).is(Blocks.STONE), "Driving over the bridge rail leaves its foundation alone");
                driver.stopRiding();
                engine.discard();
                regulator.discard();
            })
            .thenSucceed();
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
        test.assertTrue(bottom.getCollisionShape(level, base).toAabbs().stream()
            .noneMatch(box -> box.contains(0.5, 0.5, 0.5)), "The cabin has an empty interior");
        test.useBlock(test.relativePos(base));
        test.assertTrue(level.getBlockState(base).getValue(BlockStateProperties.OPEN)
            && level.getBlockState(base.above()).getValue(BlockStateProperties.OPEN), "The cabin door opens across both halves");
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
