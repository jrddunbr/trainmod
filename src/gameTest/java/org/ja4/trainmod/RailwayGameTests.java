package org.ja4.trainmod;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = Trainmod.ID)
final class RailwayGameTests {
    private record Case(String name, Consumer<GameTestHelper> function) {
        Identifier id() { return Identifier.fromNamespaceAndPath(Trainmod.ID, name); }
    }

    private static final List<Case> TESTS = List.of(
        new Case("connections", RailwayGameTests::connections),
        new Case("curve_banking", RailwayGameTests::curveBanking),
        new Case("buffer_stop", RailwayGameTests::bufferStop),
        new Case("wheel_end_of_track", RailwayGameTests::wheelEndOfTrack),
        new Case("track_items", RailwayGameTests::trackItems),
        new Case("flat_ground_grades", RailwayGameTests::flatGroundGrades),
        new Case("ballast_steps", RailwayGameTests::ballastSteps),
        new Case("crossovers", RailwayGameTests::crossovers),
        new Case("switch_point_clearance", RailwayGameTests::switchPointClearance),
        new Case("diagonal_turnouts_and_rising_curves", RailwayGameTests::diagonalTurnoutsAndRisingCurves),
        new Case("scissor_stands", RailwayGameTests::scissorStands),
        new Case("long_scissor_stands", test -> scissorStands(test, TrackGeometry.Kind.SCISSORS_LONG)),
        new Case("visible_freight_and_doors", RailwayGameTests::visibleFreightAndDoors),
        new Case("placement_previews", RailwayGameTests::placementPreviews),
        new Case("freight_riders_and_brakes", RailwayGameTests::freightRidersAndBrakes),
        new Case("bore_drives_and_lays_rail", RailwayGameTests::boreDrivesAndLaysRail),
        new Case("creative_bore", RailwayGameTests::creativeBore),
        new Case("turnout_redstone", RailwayGameTests::turnoutRedstone),
        new Case("fuel_and_legacy_stock", RailwayAdditionsGameTest::fuelAndLegacyStock),
        new Case("track_manufacturing", RailwayAdditionsGameTest::trackManufacturing),
        new Case("steam_tender_controls", RailwayAdditionsGameTest::steamTenderControls),
        new Case("touching_tender", RailwayAdditionsGameTest::touchingTender),
        new Case("powered_impact_and_crew", RailwayAdditionsGameTest::poweredImpactAndCrew),
        new Case("equal_locomotive_speeds", RailwayAdditionsGameTest::equalLocomotiveSpeeds),
        new Case("electric_reserve", RailwayAdditionsGameTest::electricReserve),
        new Case("catenary_routes", RailwayAdditionsGameTest::catenaryRoutes),
        new Case("regulator_cargo", RailwayAdditionsGameTest::regulatorCargo),
        new Case("track_wear", RailwayAdditionsGameTest::trackWear),
        new Case("regulator_walls", RailwayAdditionsGameTest::regulatorWalls),
        new Case("regulator_grade", RailwayAdditionsGameTest::regulatorGrade),
        new Case("bore_tickets", RailwayAdditionsGameTest::boreTickets),
        new Case("bridges_and_cabin", RailwayAdditionsGameTest::bridgesAndCabin),
        new Case("wide_steel_girder", RailwayAdditionsGameTest::wideGirder),
        new Case("coupled_movement", RailwayGameTests::coupledMovement),
        new Case("impact_coupling", RailwayGameTests::impactCoupling),
        new Case("coupling_notice", RailwayGameTests::couplingNotice),
        new Case("excavation", RailwayGameTests::excavation),
        new Case("worldedit", WorldEditGameTest::run));

    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        for (Case test : TESTS) {
            event.register(Registries.TEST_FUNCTION, test.id(), () -> test.function);
        }
    }

    @SubscribeEvent
    static void registerTests(RegisterGameTestsEvent event) {
        for (Case test : TESTS) {
            if (test.name.equals("worldedit") && !ModList.get().isLoaded("worldedit")) {
                continue;
            }
            var environment = event.registerEnvironment(test.id());
            var function = ResourceKey.create(Registries.TEST_FUNCTION, test.id());
            event.registerTest(test.id(), new FunctionGameTestInstance(function,
                new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 240, 1, true)));
        }
    }

    private static void connections(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = test.absolutePos(new BlockPos(0, 10, 0));
        straight(level, root.north(), 0);
        piece(level, root, TrackGeometry.Kind.CURVE_SMALL, 0, true);
        BlockPos exit = root.offset(3, 0, 5);
        piece(level, exit, TrackGeometry.Kind.DIAGONAL, 14, true);
        var start = at(level, root.north());
        double distance = RailNetwork.railAt(level, root).path().length + 0.75;
        var position = RailNetwork.advance(level, start, distance);
        test.assertTrue(position != null && position.rail().root().equals(exit),
            "The small turn should connect to the diagonal");
        var returned = RailNetwork.advance(level, position, -distance);
        test.assertTrue(returned != null && returned.point().distanceToSqr(start.point()) < 0.0001,
            "Backing up should return to the starting point");
        level.setBlockAndUpdate(root.south(2), Blocks.AIR.defaultBlockState());
        test.assertTrue(RailNetwork.railAt(level, root) == null, "A missing rail segment disconnects the piece");

        BlockPos grade = root.above(8);
        straight(level, grade.north(), 0);
        piece(level, grade, TrackGeometry.Kind.GRADE_8, 0, true);
        BlockPos high = grade.offset(0, 1, 9);
        straight(level, high, 0);
        var highPosition = RailNetwork.advance(level, at(level, grade.north()), 10.2);
        test.assertTrue(highPosition != null && highPosition.rail().root().equals(high),
            "The gentle grade joins level track one block higher");
        test.succeed();
    }

    private static void curveBanking(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = test.absolutePos(new BlockPos(0, 92, 0));
        var large = TrackGeometry.Kind.CURVE_LARGE;
        var small = TrackGeometry.Kind.CURVE_SMALL;
        var path = large.shape(0, true).routes().getFirst();
        int ends = CurveBanking.START | CurveBanking.END;
        piece(level, root.north(5), TrackGeometry.Kind.TURNOUT_45, 0, true);
        piece(level, root, large, 0, true);
        BlockPos diagonal = root.offset(6, 0, 13);
        piece(level, diagonal, TrackGeometry.Kind.DIAGONAL, 14, true);
        test.assertTrue(CurveBanking.connections(level, root, large, path, ends) == 0,
            "A switch and a standalone diagonal leave both curve ends level");
        test.assertTrue(CurveBanking.slope(large, path, 0, 0) == 0 && CurveBanking.slope(large, path, path.length, 0) == 0,
            "Incompatible joints have zero cant exactly at the rail ends");

        BlockPos next = root.offset(10, 0, 15);
        piece(level, next, small, 4, false);
        var adjacent = small.shape(4, false).routes().getFirst();
        int firstEnd = CurveBanking.connections(level, root, large, path, ends);
        int secondEnd = CurveBanking.connections(level, next, small, adjacent, ends);
        test.assertTrue(firstEnd == CurveBanking.variant(0, 1) && secondEnd == CurveBanking.variant(0, 1),
            "Different curve sizes carry the bank through a compatible end-to-end joint");
        double first = CurveBanking.slope(large, path, path.length, firstEnd);
        double second = CurveBanking.slope(small, adjacent, adjacent.length, secondEnd);
        test.assertTrue(Math.abs(first + second) < 1e-9 && Math.abs(first) > 0.02,
            "The two ends have identical elevation when viewed in a common direction");

        BlockPos reverseRoot = root.above(6);
        piece(level, reverseRoot, large, 0, true);
        BlockPos opposing = reverseRoot.offset(8, 0, 17);
        piece(level, opposing, small, 8, true);
        test.assertTrue(RailNetwork.next(level, RailNetwork.railAt(level, reverseRoot), true) != null,
            "The opposing curves form a traversable S bend");
        test.assertTrue(CurveBanking.connections(level, reverseRoot, large, path, ends) == 0,
            "An S bend levels out where the outside rail changes sides");
        test.succeed();
    }

    private static void bufferStop(GameTestHelper test) {
        var level = test.getLevel();
        ServerPlayer player = test.makeMockServerPlayerInLevel();
        try {
            BlockPos root = test.absolutePos(new BlockPos(0, 78, 0));
            for (int i = 0; i <= 4; i++) {
                straight(level, root.south(i), 0);
            }
            BlockPos end = root.south(4);
            BlockPos offRail = end.south();
            level.setBlockAndUpdate(offRail.below(), Blocks.STONE.defaultBlockState());
            player.setPos(TrackGeometry.origin(root).add(1, 1, 0));
            player.setYRot(0);
            player.setYHeadRot(0);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.BUFFER_ITEM.get()));
            var stopItem = Trainmod.BUFFER_ITEM.get();
            test.assertTrue(stopItem.useOn(useAt(player, offRail.below(), 0.5, 1, 0.5))
                == net.minecraft.world.InteractionResult.FAIL, "A buffer cannot be placed off rail");
            stopItem.useOn(useAt(player, end, 0.5, TrackGeometry.RAIL_HEIGHT, 0.7));
            var stops = level.getEntitiesOfClass(BufferStop.Mounted.class, new net.minecraft.world.phys.AABB(end));
            test.assertTrue(stops.size() == 1 && Math.abs(stops.getFirst().getZ() - end.getZ() - 0.7) < 0.01
                && RailNetwork.railAt(level, end) != null, "A buffer mounts at the click and preserves the track");
            test.assertTrue(Math.abs(stops.getFirst().getYRot()) == 180, "The beam faces the approaching player");
            var stop = stops.getFirst();
            test.assertTrue(Math.abs(stop.getBoundingBox().maxY - stop.getY() - 6.0 / 16) < 0.001,
                "The mounted buffer hitbox ends at the top of the coupler");

            var engine = new TrainVehicle(Trainmod.VEHICLES.get(TrainVehicle.Kind.LOCOMOTIVE).get(), level,
                TrainVehicle.Kind.LOCOMOTIVE);
            var clear = at(level, end.north(3));
            var impact = at(level, end);
            test.assertTrue(engine.canMoveTo(level, clear, java.util.Set.of()), "The buffer leaves room for a train to back away");
            test.assertTrue(!engine.canMoveTo(level, impact, java.util.Set.of()), "The beam stops the approaching train");
            for (var kind : List.of(TrainVehicle.Kind.FLATCAR, TrainVehicle.Kind.GONDOLA, TrainVehicle.Kind.BOXCAR)) {
                var car = new TrainVehicle(Trainmod.VEHICLES.get(kind).get(), level, kind);
                test.assertTrue(!car.canMoveTo(level, impact, java.util.Set.of()),
                    "The buffer beam also catches the " + kind.id());
            }
            BlockPos clearance = end.north(3);
            for (BlockPos ceiling : BlockPos.betweenClosed(clearance.offset(-1, 1, -1), clearance.offset(1, 1, 1))) {
                level.setBlockAndUpdate(ceiling, Blocks.STONE.defaultBlockState());
            }
            for (var kind : List.of(TrainVehicle.Kind.FLATCAR, TrainVehicle.Kind.GONDOLA, TrainVehicle.Kind.BOXCAR)) {
                var car = new TrainVehicle(Trainmod.VEHICLES.get(kind).get(), level, kind);
                boolean fits = kind == TrainVehicle.Kind.FLATCAR;
                test.assertTrue(car.canMoveTo(level, clear, java.util.Set.of()) == fits,
                    "One-block clearance matches the restored height of the " + kind.id());
            }
            test.assertTrue(!engine.canMoveTo(level, clear, java.util.Set.of()),
                "The same low clearance still stops the locomotive");

            var bore = new TrainVehicle(Trainmod.VEHICLES.get(TrainVehicle.Kind.TUNNEL_BORE).get(), level,
                TrainVehicle.Kind.TUNNEL_BORE);
            test.assertTrue("buffer_reached".equals(TrackWork.extendIfNeeded(level, player, bore, impact, 0, List.of()).key()),
                "The tunnel bore stops before cutting through the buffer");

            BlockPos diagonal = root.above(7);
            piece(level, diagonal, TrackGeometry.Kind.DIAGONAL, 14, true);
            player.setPos(TrackGeometry.origin(diagonal).add(1, 1, -1));
            player.setYRot(-45);
            player.setYHeadRot(-45);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.BUFFER_ITEM.get()));
            Trainmod.BUFFER_ITEM.get().useOn(useAt(player, diagonal, 0.86, TrackGeometry.RAIL_HEIGHT, 0.86));
            var diagonalStops = level.getEntitiesOfClass(BufferStop.Mounted.class, new net.minecraft.world.phys.AABB(diagonal));
            test.assertTrue(diagonalStops.size() == 1 && Math.abs(diagonalStops.getFirst().getYRot() - 135) < 0.01,
                "A buffer can be mounted along a diagonal rail");
        } finally {
            level.getServer().getPlayerList().remove(player);
        }
        test.succeed();
    }

    private static void trackItems(GameTestHelper test) {
        var level = test.getLevel();
        var player = test.makeMockServerPlayerInLevel();
        for (var entry : Trainmod.TRACK_ITEMS.entrySet()) {
            var kind = entry.getKey();
            var stack = new ItemStack(entry.getValue().get());
            int max = stack.getMaxStackSize();
            int largest = 0;
            for (int rotation : kind.rotations()) {
                for (boolean left : new boolean[]{true, false}) {
                    for (boolean diagonalLow : kind.gradedCurve() ? new boolean[]{false, true} : new boolean[]{false}) {
                        int blocks = kind.shape(rotation, left, true, diagonalLow).cells().size();
                        largest = Math.max(largest, blocks);
                        test.assertTrue(max * blocks <= 64, kind + " kits stay within 64 rail blocks in every orientation");
                    }
                }
            }
            test.assertTrue((max + 1) * largest > 64, kind + " stack size accounts for its largest footprint");
        }
        try {
            BlockPos first = test.absolutePos(new BlockPos(0, 12, 0));
            var kind = TrackGeometry.Kind.CURVE_SMALL;
            for (boolean left : new boolean[]{true, false}) {
                BlockPos root = first.above(left ? 0 : 9);
                for (int x = -4; x <= 4; x++) {
                    for (int z = -1; z <= 6; z++) {
                        level.setBlockAndUpdate(root.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                    }
                }
                var straight = Trainmod.TRACK_ITEMS.get(TrackGeometry.Kind.STRAIGHT).get();
                BlockPos approach = root.north();
                player.setPos(TrackGeometry.origin(approach).add(0, 1, -1));
                player.setYRot(0);
                player.setYHeadRot(0);
                var stack = new ItemStack(straight, 2);
                player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                straight.useOn(useAt(player, approach.below(), 0.5, 1, 0.5));
                test.assertTrue(RailNetwork.railAt(level, approach) != null, "One click places a straight kit");

                var curve = Trainmod.TRACK_ITEMS.get(kind).get();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(curve, 2));
                curve.useOn(useAt(player, approach, left ? 0.75 : 0.25, TrackGeometry.RAIL_HEIGHT, 0.86));
                var rail = RailNetwork.railAt(level, root);
                test.assertTrue(rail != null && (rail.end(true).x > root.getX() + 0.5) == left,
                    "One curve item snaps to the rail end with the aimed handedness");
                test.assertTrue(rail.cells().size() > 1, "One click places the entire curved kit");
                test.assertTrue(RailNetwork.next(level, RailNetwork.railAt(level, approach), true).rail().root().equals(root),
                    "The placed curve connects to the approach rail");
            }
            for (boolean left : new boolean[]{true, false}) {
                BlockPos corner = first.above(left ? 15 : 20);
                for (BlockPos cell : BlockPos.betweenClosed(corner.offset(-7, -1, -1), corner.offset(7, -1, 8))) {
                    level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
                }
                piece(level, corner, TrackGeometry.Kind.DIAGONAL, 14, true);
                player.setPos(TrackGeometry.origin(corner).add(-0.7, 1, -0.7));
                player.setYRot(-45);
                var curve = Trainmod.TRACK_ITEMS.get(kind).get();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(curve));
                curve.useOn(useAt(player, corner, left ? 0.99 : 0.78, TrackGeometry.RAIL_HEIGHT, left ? 0.78 : 0.99));
                var connected = RailNetwork.next(level, RailNetwork.railAt(level, corner), true);
                test.assertTrue(connected != null, "A curve snaps to the far end of a diagonal when facing along it");
                boolean otherEnd = connected.forward() > 0;
                Vec3 exit = connected.rail().path().tangent(otherEnd ? connected.rail().path().length : 0)
                    .scale(connected.forward());
                test.assertTrue((exit.dot(new Vec3(1, 0, -1)) > 0) == left, "Both hands connect from a diagonal");
            }
            BlockPos perpendicular = first.above(26);
            for (int z = 0; z < 6; z++) level.setBlockAndUpdate(perpendicular.south(z).below(), Blocks.STONE.defaultBlockState());
            player.setYRot(-90);
            var six = Trainmod.TRACK_ITEMS.get(TrackGeometry.Kind.STRAIGHT_6).get();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(six));
            test.assertTrue(six.useOn(useAt(player, perpendicular.below(), 0.5, 1, 0.5))
                == net.minecraft.world.InteractionResult.FAIL, "A supported perpendicular track is not suggested");

            BlockPos bridge = first.above(33);
            straight(level, bridge.north(), 0);
            level.setBlockAndUpdate(bridge, Blocks.AIR.defaultBlockState());
            var approachShape = TrackGeometry.Kind.STRAIGHT.shape(0, true);
            var port = TrackEnds.at(approachShape, BlockPos.ZERO, bridge.north()).stream()
                .filter(end -> end.landing().equals(bridge)).findFirst().orElseThrow();
            test.assertTrue(TrackEnds.connection(level, bridge.north(), port) == TrackEnds.Connection.OPEN,
                "An exposed port can display rail end faces");
            level.setBlockAndUpdate(bridge.below(2), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(bridge.below(), Blocks.GRAVEL.defaultBlockState());
            player.setPos(TrackGeometry.origin(bridge.north()).add(0, 1, -1));
            player.setYRot(0);
            var bridgeRail = Trainmod.TRACK_ITEMS.get(TrackGeometry.Kind.BRIDGE_STRAIGHT).get();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(bridgeRail));
            bridgeRail.useOn(useAt(player, bridge.north(), 0.5, TrackGeometry.RAIL_HEIGHT, 0.86));
            test.assertTrue(level.getBlockState(bridge).is(Trainmod.TRACK.get(TrackGeometry.Kind.BRIDGE_STRAIGHT).get()),
                "Bridge rail can snap to regular track at the same rail height");
            var next = RailNetwork.next(level, RailNetwork.railAt(level, bridge.north()), true);
            test.assertTrue(next != null && next.rail().root().equals(bridge),
                "A train can follow the connection to the bridge rail");
            test.assertTrue(TrackEnds.connection(level, bridge.north(), port) == TrackEnds.Connection.BRIDGE,
                "A bridge connection keeps the rail open at the joint and tapers the ballast onto the deck");
            test.assertTrue(TrackWork.ballast(level, player, bridge.below(), 8, TrainVehicle.BallastMode.NARROW) == 0,
                "The ballast car leaves bridge track unballasted");
        } finally {
            level.getServer().getPlayerList().remove(player);
        }
        test.succeed();
    }

    private static net.minecraft.world.item.context.UseOnContext useAt(ServerPlayer player, BlockPos block,
                                                                        double x, double y, double z) {
        var point = new Vec3(block.getX() + x, block.getY() + y, block.getZ() + z);
        return new net.minecraft.world.item.context.UseOnContext(player, InteractionHand.MAIN_HAND,
            new net.minecraft.world.phys.BlockHitResult(point, Direction.UP, block, false));
    }

    private static void flatGroundGrades(GameTestHelper test) {
        var level = test.getLevel();
        ServerPlayer player = test.makeMockServerPlayerInLevel();
        try {
            for (int run : new int[]{6, 8}) {
                var kind = run == 6 ? TrackGeometry.Kind.GRADE_6 : TrackGeometry.Kind.GRADE_8;
                BlockPos root = test.absolutePos(new BlockPos(0, 105 + run, 0));
                for (int i = 0; i <= run; i++) {
                    level.setBlockAndUpdate(root.south(i).below(), Blocks.STONE.defaultBlockState());
                }
                player.setPos(TrackGeometry.origin(root.north()).add(0, 1, 0));
                player.setYRot(0);
                var kit = Trainmod.TRACK_ITEMS.get(kind).get();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(kit));
                kit.useOn(useAt(player, root.below(), 0.5, 1, 0.5));
                var rail = RailNetwork.railAt(level, root);
                test.assertTrue(rail != null && rail.end(true).y - rail.end(false).y == 1
                    && rail.end(true).z - rail.end(false).z == run + 1, "Each grade places on level support and rises away");
                test.assertTrue(level.getBlockState(root.south(run)).isAir(),
                    "The top of a grade does not insert a solid cube under the running surface");
                var car = vehicle(level, TrainVehicle.Kind.FLATCAR, root.south(run / 2));
                double initial = car.getZ();
                for (int i = 0; i < 12; i++) car.tick();
                test.assertTrue(car.getZ() < initial - 0.1, "A parked car accelerates downhill");
                car.interact(player, InteractionHand.MAIN_HAND, new Vec3(car.kind.brakeWheelOffset(), car.kind.brakeWheelHeight() / 16.0, -1.2));
                test.assertTrue(car.handbrakeApplied() && car.handbrakeAngle(0) == 0
                    && car.handbrakeAngle(2.5f) == 45 && car.handbrakeAngle(5) == 90,
                    "The brake engages immediately and its center turns a quarter-turn over five ticks");
                double braked = car.getZ();
                for (int i = 0; i < 12; i++) car.tick();
                test.assertTrue(car.getZ() == braked, "The rear handbrake holds the car against gravity");
                car.interact(player, InteractionHand.MAIN_HAND, new Vec3(car.kind.brakeWheelOffset(), car.kind.brakeWheelHeight() / 16.0, -1.2));
                for (int i = 0; i < 12; i++) car.tick();
                test.assertTrue(car.getZ() < braked - 0.1, "The car rolls again when the brake is released");
                car.discard();
            }
        } finally {
            level.getServer().getPlayerList().remove(player);
        }
        test.succeed();
    }

    private static void ballastSteps(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos cell = test.absolutePos(new BlockPos(0, 150, 0));
        var ballast = Trainmod.BALLAST.get().defaultBlockState();
        level.setBlockAndUpdate(cell, ballast);
        var mound = ballast.getCollisionShape(level, cell);
        double[] samples = {-0.875, -0.625, -0.375, 0.01, 0.99, 1.375, 1.625, 1.875};
        double[] heights = {0.25, 0.5, 0.75, 1, 1, 0.75, 0.5, 0.25};
        for (int i = 0; i < samples.length; i++) {
            test.assertTrue(mound.max(Direction.Axis.Y, 0.5, samples[i]) == heights[i],
                "The ballast has a full-block core and walkable shoulder steps across three blocks");
            test.assertTrue(mound.max(Direction.Axis.Y, samples[i], 0.5) == heights[i],
                "The ballast matches the same collision profile at either end");
        }
        test.assertTrue(TrackBlock.supported(level, cell.above()), "The crest supports rail");
        test.succeed();
    }

    private static void crossovers(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos site = test.absolutePos(new BlockPos(0, 117, 0));
        for (var kind : List.of(TrackGeometry.Kind.CROSSOVER_90, TrackGeometry.Kind.CROSSOVER_45)) {
            boolean diagonal = kind == TrackGeometry.Kind.CROSSOVER_45;
            BlockPos root = diagonal ? site.above(5) : site;
            var compact = kind.shape(0, true);
            var legacy = kind.shape(0, true, false);
            test.assertTrue(compact.cells().size() == 5 && legacy.cells().size() == 9,
                "A compact crossing removes one segment from each approach");
            piece(level, root, kind, 0, true);
            straight(level, root.north(2), 0);
            BlockPos side = diagonal ? root.offset(-2, 0, -2) : root.west(2);
            straight(level, side, diagonal ? 14 : 12);
            var fromNorth = RailNetwork.advance(level, at(level, root.north(2)), 2.8);
            var west = RailNetwork.locate(level, side, TrackGeometry.origin(side), new Vec3(1, 0, diagonal ? 1 : 0));
            var fromWest = RailNetwork.advance(level, west, 2.8);
            test.assertTrue(fromNorth != null && fromNorth.rail().root().equals(root) && fromNorth.rail().route() == 0
                && fromWest != null && fromWest.rail().root().equals(root) && fromWest.rail().route() == 1,
                "Both compact crossing routes connect to their shortened approaches");

            BlockPos older = root.above(1);
            for (int i = 0; i < legacy.cells().size(); i++) {
                BlockPos cell = older.offset(legacy.cells().get(i));
                level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(cell, Trainmod.TRACK.get(kind).get().defaultBlockState().setValue(TrackBlock.INDEX, i));
            }
            var old = RailNetwork.physicalRailsAt(level, older);
            test.assertTrue(old.size() == 2 && old.getFirst().cells().size() == 9,
                "Saved nine-segment crossings keep their original paths and segment indices");
        }
        test.succeed();
    }

    private static void switchPointClearance(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = test.absolutePos(new BlockPos(0, 254, 0));
        var kind = TrackGeometry.Kind.TURNOUT_PARALLEL_LONG;
        piece(level, root, kind, 0, true);
        BlockPos stand = root.west();
        level.setBlockAndUpdate(stand.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(stand, Trainmod.STAND.get().defaultBlockState());
        var far = vehicle(level, TrainVehicle.Kind.BOXCAR, root.south(12));
        Vec3 original = far.position();
        test.useBlock(test.relativePos(stand));
        test.assertTrue(level.getBlockState(root).getValue(BlockStateProperties.POWERED),
            "Stock beyond the point rails leaves the switch free to throw");
        var trailingRoute = far.cursor();
        test.assertTrue(trailingRoute != null && trailingRoute.rail().route() == 0
            && trailingRoute.point().distanceToSqr(original) < 0.0001
            && RailNetwork.advance(level, trailingRoute, -20) == null,
            "Stock stays on the unselected approach and stops short of its closed points");

        var rail = RailNetwork.railAt(level, root);
        var panel = SwitchLayout.of(kind, kind.shape(0, true)).panels().getFirst();
        double heel = panel.first().distance(1);
        far.moveToRail(new RailNetwork.Position(rail, heel + 1.25, 1));
        test.assertTrue(!RailNetwork.panelOccupied(level, root, panel),
            "A car whose wheels have cleared the point rails leaves the switch free even with body overhang");
        far.moveToRail(new RailNetwork.Position(rail, heel + 0.3, 1));
        test.assertTrue(RailNetwork.panelOccupied(level, root, panel), "The running gear over the points locks the switch");
        test.useBlock(test.relativePos(stand));
        test.assertTrue(level.getBlockState(root).getValue(BlockStateProperties.POWERED)
            && level.getBlockState(stand).getValue(RailwayBlocks.Stand.PENDING), "The throw waits for the nearby vehicle to clear");
        far.discard();
        test.succeed();
    }

    private static void wheelEndOfTrack(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        BlockPos first = test.absolutePos(new BlockPos(0, 98, 0));
        BlockPos last = first.south(8);
        for (int i = 0; i <= 8; i++) straight(level, first.south(i), 0);
        var engine = vehicle(level, TrainVehicle.Kind.LOCOMOTIVE, first.south(4));
        engine.supplies.setItem(0, new ItemStack(Trainmod.DIESEL_BUCKET.get()));
        ServerPlayer driver = test.makeMockServerPlayerInLevel();
        try {
            driver.setPos(engine.position());
            test.assertTrue(driver.startRiding(engine), "The driver boards the RS3");
            for (int i = 0; i < 70; i++) {
                engine.receiveControls(driver, TrainControls.FORWARD);
                engine.tick();
            }
            double frontLimit = last.getZ() + 1 - VehicleModels.frontAxle(engine.kind);
            test.assertTrue(Math.abs(engine.getZ() - frontLimit) < 0.001,
                "The RS3 stops with its leading front axle at the end of the rail");
            for (int i = 0; i < 95; i++) {
                engine.receiveControls(driver, TrainControls.REVERSE);
                engine.tick();
            }
            double rearLimit = first.getZ() - VehicleModels.rearAxle(engine.kind);
            test.assertTrue(Math.abs(engine.getZ() - rearLimit) < 0.001,
                "In reverse, the rear axle sets the stopping point");
        } finally {
            driver.stopRiding();
            engine.discard();
            level.getServer().getPlayerList().remove(driver);
        }
        test.succeed();
    }

    private static void diagonalTurnoutsAndRisingCurves(GameTestHelper test) {
        var level = test.getLevel();
        for (var kind : TrackGeometry.Kind.values()) {
            if (!kind.turnout()) continue;
            for (int rotation : List.of(2, 6, 10, 14)) {
                for (boolean left : new boolean[]{true, false}) {
                    var shape = kind.shape(rotation, left);
                    var tangent = shape.routes().getFirst().tangent(0);
                    test.assertTrue(Math.abs(tangent.x * tangent.z) > 0.49 && shape.cells().size() <= kind.segmentLimit(),
                        kind + " diagonal entrance and segment count " + shape.cells().size());
                    if (kind.parallelTurnout()) {
                        double compactGap = shape.routes().getFirst().endpoint(true).distanceTo(shape.routes().get(1).endpoint(true));
                        var original = kind.shape(rotation, left, false);
                        double originalGap = original.routes().getFirst().endpoint(true).distanceTo(original.routes().get(1).endpoint(true));
                        test.assertTrue(Math.abs(compactGap - 2 * Math.sqrt(2)) < 1e-6
                            && Math.abs(originalGap - 3 * Math.sqrt(2)) < 1e-6,
                            "New diagonal parallel exits come in by one diagonal block and old layouts retain their spacing");
                    }
                    test.assertTrue(SwitchLayout.of(kind, shape).panels().stream().allMatch(panel -> shape.cells().contains(panel.control())),
                        kind + " has addressable diagonal switch points");
                }
            }
        }
        BlockPos turnout = test.absolutePos(new BlockPos(0, 290, 0));
        var player = test.makeMockServerPlayerInLevel();
        BlockPos previewRoot = turnout.offset(19, 0, 0);
        for (BlockPos support : BlockPos.betweenClosed(previewRoot.offset(-18, -1, -18), previewRoot.offset(18, -1, 18))) {
            level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        }
        for (int rotation : List.of(2, 6, 10, 14)) {
            Vec3 heading = TrackGeometry.Kind.DIAGONAL.shape(rotation, true).routes().getFirst().tangent(0);
            BlockPos incoming = BlockPos.containing(TrackGeometry.origin(previewRoot).subtract(heading.scale(1.4)));
            piece(level, incoming, TrackGeometry.Kind.DIAGONAL, rotation, true);
            player.setYRot((float) Math.toDegrees(Math.atan2(-heading.x, heading.z)));
            for (boolean left : new boolean[]{true, false}) {
                Vec3 side = SwitchLayout.lateral(heading).scale(left ? 0.9 : -0.9);
                player.setPos(TrackGeometry.origin(incoming).subtract(heading).add(side));
                Vec3 clickPoint = heading.scale(0.64);
                for (var switchKind : List.of(TrackGeometry.Kind.TURNOUT_45, TrackGeometry.Kind.TURNOUT_45_MEDIUM,
                    TrackGeometry.Kind.TURNOUT_45_LARGE, TrackGeometry.Kind.WYE, TrackGeometry.Kind.WYE_MEDIUM)) {
                    var kit = Trainmod.TRACK_ITEMS.get(switchKind).get();
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(kit));
                    for (boolean rail : new boolean[]{true, false}) {
                        var click = rail ? useAt(player, incoming, 0.5 + clickPoint.x, TrackGeometry.RAIL_HEIGHT, 0.5 + clickPoint.z)
                            : useAt(player, previewRoot.below(), 0.5, 1, 0.5);
                        var ghost = kit.preview(level, click, player);
                        test.assertTrue(ghost != null && ghost.supported() && ghost.root().equals(previewRoot)
                            && ghost.rotation() == rotation && ghost.left() == left
                            && ghost.equals(TrackItem.findPlacement(level, click, player, switchKind, null)),
                            switchKind + " previews and places its common points against the diagonal approach " + rotation + "/" + left);
                    }
                }
            }
            level.setBlockAndUpdate(incoming, Blocks.AIR.defaultBlockState());
        }
        var kind = TrackGeometry.Kind.TURNOUT_45;
        BlockPos approach = turnout.north().west();
        piece(level, approach, TrackGeometry.Kind.DIAGONAL, 14, true);
        for (boolean left : new boolean[]{true, false}) {
            for (BlockPos cell : kind.shape(14, left).cells()) {
                level.setBlockAndUpdate(turnout.offset(cell).below(), Blocks.STONE.defaultBlockState());
            }
        }
        player.setPos(TrackGeometry.origin(approach).add(1.8, 0, 0));
        player.setYRot(-45);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.TRACK_ITEMS.get(kind).get()));
        Trainmod.TRACK_ITEMS.get(kind).get().useOn(useAt(player, approach, 0.94, TrackGeometry.RAIL_HEIGHT, 0.94));
        var turnoutState = level.getBlockState(turnout);
        test.assertTrue(turnoutState.is(Trainmod.TRACK.get(kind).get()) && turnoutState.getValue(TrackBlock.ROTATION) == 14,
            "A regular turnout kit snaps to the diagonal approach");
        var entry = RailNetwork.advance(level, RailNetwork.locate(level, approach, TrackGeometry.origin(approach),
            new Vec3(1, 0, 1)), 1.5);
        test.assertTrue(entry != null && entry.rail().root().equals(turnout), "The diagonal through route is traversable");
        BlockPos stand = turnout.west(2);
        var remote = RailwayBlocks.Stand.connection(level, stand);
        var corner = RailwayBlocks.Stand.connection(level, turnout.west().north());
        test.assertTrue(remote != null && remote.position().equals(turnout) && corner != null && corner.position().equals(turnout)
            && RailwayBlocks.Stand.connection(level, turnout.west(3)) == null,
            "Diagonal points accept a stand two blocks away or across the corner");
        BlockPos otherPoints = stand.west();
        piece(level, otherPoints, kind, 4, true);
        var nearby = RailwayBlocks.Stand.connection(level, stand);
        test.assertTrue(nearby != null && nearby.position().equals(otherPoints)
            && RailwayBlocks.Stand.connection(level, otherPoints.west(2)) == null,
            "Adjacent points take priority and cardinal points retain adjacent reach");
        for (BlockPos cell : kind.shape(4, true).cells()) level.setBlockAndUpdate(otherPoints.offset(cell), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(stand.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(stand, Trainmod.STAND.get().defaultBlockState());
        var parkedAtPoints = vehicle(level, TrainVehicle.Kind.GONDOLA, turnout);
        test.useBlock(test.relativePos(stand));
        test.assertTrue(level.getBlockState(stand).getValue(RailwayBlocks.Stand.PENDING)
            && !level.getBlockState(stand).getValue(BlockStateProperties.POWERED),
            "The distant stand waits for the same loading-gauge clearance as an adjacent stand");
        parkedAtPoints.discard();
        test.useBlock(test.relativePos(stand));
        test.useBlock(test.relativePos(stand));
        var branch = RailNetwork.railAt(level, turnout);
        test.assertTrue(branch != null && branch.route() == 1, "The distant stand throws the diagonal turnout");
        Vec3 out = branch.path().tangent(branch.path().length);
        BlockPos landing = BlockPos.containing(branch.end(true).add(out.scale(0.15)));
        straight(level, landing, Math.abs(out.x) > 0.9 ? 12 : 0);
        var exit = RailNetwork.advance(level, new RailNetwork.Position(branch, branch.path().length - 0.25, 1), 0.65);
        test.assertTrue(exit != null && exit.rail().root().equals(landing), "The angled approach branches onto cardinal track");

        for (var rising : List.of(TrackGeometry.Kind.CURVE_MEDIUM_GRADE, TrackGeometry.Kind.CURVE_LARGE_GRADE)) {
            BlockPos root = turnout.above(rising == TrackGeometry.Kind.CURVE_MEDIUM_GRADE ? 10 : 19);
            var shape = rising.shape(0, true);
            for (BlockPos cell : shape.cells()) {
                level.setBlockAndUpdate(root.offset(cell.getX(), -1, cell.getZ()), Blocks.STONE.defaultBlockState());
            }
            player.setPos(TrackGeometry.origin(root).add(1.5, 0, 0));
            player.setYRot(0);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.TRACK_ITEMS.get(rising).get()));
            Trainmod.TRACK_ITEMS.get(rising).get().useOn(useAt(player, root.below(), 0.5, 1, 0.5));
            var rail = RailNetwork.railAt(level, root);
            test.assertTrue(rail != null && Math.abs(rail.end(true).y - rail.end(false).y - 1) < 1e-6,
                rising + " places on level ground and rises one block");
            Vec3 diagonal = rail.path().tangent(rail.path().length);
            BlockPos next = BlockPos.containing(rail.end(true).add(diagonal.scale(0.15)));
            straight(level, next, diagonal.x * diagonal.z > 0 ? 14 : 2);
            var elevated = RailNetwork.advance(level, at(level, root), rail.path().length);
            test.assertTrue(elevated != null && elevated.rail().root().equals(next), rising + " joins a raised diagonal");
        }
        int height = 0;
        for (var curve : List.of(TrackGeometry.Kind.CURVE_MEDIUM, TrackGeometry.Kind.CURVE_LARGE,
                TrackGeometry.Kind.CURVE_MEDIUM_GRADE, TrackGeometry.Kind.CURVE_LARGE_GRADE)) {
            BlockPos low = turnout.offset(19, height += 4, 0);
            for (BlockPos support : BlockPos.betweenClosed(low.offset(-13, -1, -13), low.offset(13, -1, 13))) {
                level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
            }
            BlockPos incoming = low.south().east();
            piece(level, incoming, TrackGeometry.Kind.DIAGONAL, 14, true);
            player.setPos(TrackGeometry.origin(incoming).add(1, 0, 1));
            player.setYRot(135);
            var kit = Trainmod.TRACK_ITEMS.get(curve).get();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(kit));
            var click = useAt(player, incoming, 0.06, TrackGeometry.RAIL_HEIGHT, 0.06);
            var ghost = kit.preview(level, click, player);
            test.assertTrue(ghost != null && ghost.supported() && ghost.diagonalLow() == curve.gradedCurve()
                && ghost.equals(TrackItem.findPlacement(level, click, player, curve, null)),
                curve + " previews from a level diagonal approach");
            kit.useOn(click);
            var incomingRail = RailNetwork.locate(level, incoming, TrackGeometry.origin(incoming), new Vec3(-1, 0, -1));
            var entered = RailNetwork.advance(level, incomingRail, 1);
            test.assertTrue(entered != null && entered.rail().root().equals(ghost.root())
                && (!curve.gradedCurve() || level.getBlockState(low).getValue(TrackBlock.DIAGONAL_LOW)),
                curve + " starts at the diagonal and saves the selected entrance");
            var route = entered.rail();
            boolean otherEnd = entered.forward() > 0;
            Vec3 finish = route.end(otherEnd);
            Vec3 direction = route.path().tangent(otherEnd ? route.path().length : 0).scale(entered.forward());
            test.assertTrue(Math.abs(finish.y - route.end(!otherEnd).y - (curve.gradedCurve() ? 1 : 0)) < 1e-6
                && Math.abs(direction.x * direction.z) < 1e-6, curve + " leads to a cardinal exit at the expected height");
            BlockPos next = BlockPos.containing(finish.add(direction.scale(0.15)));
            int rotation = Math.abs(direction.x) > 0.9 ? 12 : 0;
            if (curve.gradedCurve()) level.setBlockAndUpdate(next.below(2), Blocks.STONE.defaultBlockState());
            straight(level, next, rotation);
            var arrived = RailNetwork.advance(level, entered, route.path().length);
            var returning = arrived == null ? null : RailNetwork.advance(level, arrived, -route.path().length);
            test.assertTrue(arrived != null && arrived.rail().root().equals(next)
                && returning != null && returning.rail().root().equals(ghost.root()),
                curve + " joins the diagonal and cardinal tracks in both directions");
        }
        level.getServer().getPlayerList().remove(player);
        level.setBlockAndUpdate(stand, Blocks.AIR.defaultBlockState());
        test.runAfterDelay(5, () -> {
            test.assertTrue(!level.getBlockState(turnout).getValue(BlockStateProperties.POWERED),
                "Breaking the distant stand clears its power at the points");
            test.succeed();
        });
    }

    private static void scissorStands(GameTestHelper test) {
        scissorStands(test, TrackGeometry.Kind.SCISSORS);
    }

    private static void scissorStands(GameTestHelper test, TrackGeometry.Kind kind) {
        var level = test.getLevel();
        BlockPos root = test.absolutePos(new BlockPos(0, 280, 0));
        piece(level, root, kind, 0, true);
        var layout = SwitchLayout.of(kind, kind.shape(0, true));
        var controls = layout.panels().stream().map(panel -> root.offset(panel.control())).toList();
        var stands = java.util.stream.IntStream.range(0, controls.size())
            .mapToObj(i -> controls.get(i).relative(i % 2 == 0 ? Direction.WEST : Direction.EAST)).toList();
        for (int i = 0; i < stands.size(); i++) {
            BlockPos stand = stands.get(i);
            level.setBlockAndUpdate(stand.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(stand, Trainmod.STAND.get().defaultBlockState());
            var connection = RailwayBlocks.Stand.connection(level, stand);
            test.assertTrue(connection != null && connection.position().equals(controls.get(i))
                && level.getBlockEntity(controls.get(i)) instanceof SwitchPoints,
                "Each stand attaches to a distinct, animated point set");
            test.assertTrue(connection.facing() == (i < 2 ? Direction.SOUTH : Direction.NORTH),
                "Both far stands face their own approach");
        }

        var straight = at(level, root.east(3));
        test.assertTrue(straight.rail().route() == 1 && straight.rail().open(true), "The other lane begins straight");
        test.useBlock(test.relativePos(stands.get(0)));
        for (int i = 0; i < controls.size(); i++) {
            test.assertTrue(level.getBlockState(controls.get(i)).getValue(BlockStateProperties.POWERED) == (i == 0),
                "Operating one stand only changes its own points");
        }
        var partial = at(level, root);
        test.assertTrue(partial.rail().route() == 2 && !partial.rail().open(true)
            && RailNetwork.advance(level, partial, kind.scissorsRun()) == null, "A mismatched exit remains closed to the approaching route");
        test.assertTrue(at(level, root.east(3)).rail().open(true), "The other lane still has a through route");

        test.useBlock(test.relativePos(stands.get(3)));
        BlockPos exit = root.offset(3, 0, kind.scissorsRun() + 1);
        straight(level, exit, 0);
        var through = at(level, root);
        var arrived = RailNetwork.advance(level, through, through.rail().path().length + 0.1);
        test.assertTrue(through.rail().open(true) && arrived != null && arrived.rail().root().equals(exit),
            "Opening the matching far points creates a through crossover route");

        var parked = vehicle(level, TrainVehicle.Kind.GONDOLA, controls.get(0));
        test.useBlock(test.relativePos(stands.get(0)));
        test.assertTrue(level.getBlockState(stands.get(0)).getValue(RailwayBlocks.Stand.PENDING),
            "A car locks the local points under it");
        test.useBlock(test.relativePos(stands.get(1)));
        test.assertTrue(level.getBlockState(controls.get(1)).getValue(BlockStateProperties.POWERED),
            "The occupied track does not lock the neighboring panels");
        parked.discard();
        test.runAtTickTime(6, () -> {
            test.assertFalse(level.getBlockState(controls.get(0)).getValue(BlockStateProperties.POWERED),
                net.minecraft.network.chat.Component.literal("The queued local throw runs when the car clears"));
            test.succeed();
        });
    }

    private static void placementPreviews(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = test.absolutePos(new BlockPos(0, 264, 0));
        var player = test.makeMockServerPlayerInLevel();
        try {
            for (int z = 0; z < 3; z++) {
                if (z != 1) level.setBlockAndUpdate(root.south(z).below(), Blocks.STONE.defaultBlockState());
            }
            var kind = TrackGeometry.Kind.STRAIGHT_3;
            var item = Trainmod.TRACK_ITEMS.get(kind).get();
            player.setPos(TrackGeometry.origin(root).add(0, 1, -1));
            player.setYRot(0);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
            var click = useAt(player, root.below(), 0.5, 1, 0.5);
            var preview = item.preview(level, click, player);
            test.assertTrue(preview != null && preview.root().equals(root)
                && preview.missingSupports().equals(List.of(root.south().below())),
                "The rail ghost identifies the exact missing support block");
            test.assertTrue(TrackItem.findPlacement(level, click, player, kind, null) == null,
                "Unsupported preview is rejected by actual placement");
            level.setBlockAndUpdate(root.south().below(), Blocks.STONE.defaultBlockState());
            var ready = item.preview(level, click, player);
            var actual = TrackItem.findPlacement(level, click, player, kind, null);
            test.assertTrue(ready.equals(actual) && ready.supported(), "The feasible ghost matches actual placement");
        } finally {
            level.getServer().getPlayerList().remove(player);
        }
        test.succeed();
    }

    private static void visibleFreightAndDoors(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = test.absolutePos(new BlockPos(0, 272, 0));
        piece(level, root, TrackGeometry.Kind.STRAIGHT_12, 0, true);
        var flat = vehicle(level, TrainVehicle.Kind.FLATCAR, root.south(2));
        var gondola = vehicle(level, TrainVehicle.Kind.GONDOLA, root.south(5));
        var boxcar = vehicle(level, TrainVehicle.Kind.BOXCAR, root.south(9));
        for (var car : List.of(flat, gondola)) {
            car.cargo.setItem(0, new ItemStack(Items.RAIL, 1));
            test.assertTrue(car.railLayers() == 1, "A small rail load shows one set");
            var kit = Trainmod.TRACK_ITEMS.get(TrackGeometry.Kind.STRAIGHT_3).get();
            for (int slot = 0; slot < 26; slot++) car.cargo.setItem(slot, new ItemStack(kit, new ItemStack(kit).getMaxStackSize()));
            test.assertTrue(car.railLayers() == 4, "A near-full rail load shows four stacked sets");
            car.cargo.setItem(26, new ItemStack(Items.GRAVEL, 64));
            test.assertTrue(car.railLayers() == 0 && (car.gravelFill() > 0) == (car == gondola),
                "Mixed cargo hides the rail stack, with a gravel surface in the gondola");
        }
        for (int slot = 0; slot < 27; slot++) gondola.cargo.setItem(slot, new ItemStack(Items.GRAVEL, 64));
        test.assertTrue(gondola.gravelFill() == 1, "A full gondola renders a full gravel load");
        gondola.cargo.clearContent();
        test.assertTrue(gondola.gravelFill() == 0, "Unloading clears the gravel surface");

        var kit = Trainmod.TRACK_ITEMS.get(TrackGeometry.Kind.STRAIGHT_12).get();
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
            net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
        output.list("Cargo", ItemStack.OPTIONAL_CODEC).add(new ItemStack(kit, 64));
        gondola.readAdditionalSaveData(net.minecraft.world.level.storage.TagValueInput.create(
            net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()));
        test.assertTrue(gondola.cargo.getItem(0).getCount() == 64 && gondola.cargo.getItem(0).getMaxStackSize() == 5,
            "An old oversized freight stack survives loading with the new limit");

        var player = test.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);
        double side = boxcar.kind.width * 0.49;
        boxcar.interact(player, InteractionHand.MAIN_HAND, new Vec3(side, 0.7, 0));
        test.assertTrue(boxcar.doorOpen(true) && !boxcar.doorOpen(false), "The clicked boxcar door slides open separately");
        test.assertTrue(boxcar.doorProgress(true, 0) == 0 && boxcar.doorProgress(true, 2.5f) == 0.5f
            && boxcar.doorProgress(false, 5) == 0, "Only the selected door animates");
        var leftDoor = VehicleHitboxes.of(boxcar.kind).controls().stream()
            .filter(target -> target.component() == VehicleHitboxes.Component.LEFT_DOOR).findFirst().orElseThrow();
        double shift = VehicleModels.OPEN_DOOR_SHIFT * boxcar.kind.length / 40;
        test.assertTrue(Math.abs(VehicleHitboxes.bounds(boxcar, leftDoor, 2.5f).getCenter().z - shift / 2) < 0.001,
            "The interaction box follows the door mid-slide");
        test.runAfterDelay(6, () -> {
            boxcar.interact(player, InteractionHand.MAIN_HAND, new Vec3(side, 0.7, shift));
            test.assertTrue(!boxcar.doorOpen(true) && boxcar.doorProgress(true, 0) == 1
                && boxcar.doorProgress(true, 2.5f) == 0.5f && boxcar.doorProgress(true, 5) == 0,
                "Clicking the open door animates it back to its closed position");
            test.succeed();
        });
    }

    private static void freightRidersAndBrakes(GameTestHelper test) {
        var level = test.getLevel();
        ServerPlayer front = test.makeMockServerPlayerInLevel();
        ServerPlayer rear = test.makeMockServerPlayerInLevel();
        try {
            for (var kind : List.of(TrainVehicle.Kind.BOXCAR, TrainVehicle.Kind.GONDOLA)) {
                BlockPos root = test.absolutePos(new BlockPos(0, kind == TrainVehicle.Kind.BOXCAR ? 127 : 132, 0));
                piece(level, root, TrackGeometry.Kind.STRAIGHT_8, 0, true);
                var car = vehicle(level, kind, root.south(3));
                if (kind == TrainVehicle.Kind.GONDOLA) {
                    var inside = VehicleHitboxes.pick(car, new Vec3(0, 2, 0), Vec3.ZERO);
                    test.assertTrue(inside != null && inside.point().y < 0.5,
                        "Aiming through the open gondola top finds its deck");
                    var wheel = VehicleHitboxes.of(kind).controls().stream()
                        .filter(target -> target.component() == VehicleHitboxes.Component.HANDBRAKE)
                        .findFirst().orElseThrow().box().getCenter();
                    var brake = VehicleHitboxes.pick(car, wheel.add(0.32, 0.7, -1.6), wheel);
                    test.assertTrue(brake != null && brake.component() == VehicleHitboxes.Component.HANDBRAKE,
                        "The brake wheel can be picked from the rear quarter");
                }
                car.cargo.setItem(0, new ItemStack(Items.COAL, 2));
                front.setPos(car.position().add(0, 0, 1));
                rear.setPos(car.position().add(0, 0, -1));
                car.interact(front, InteractionHand.MAIN_HAND, Vec3.ZERO);
                test.assertTrue(front.containerMenu != front.inventoryMenu, "Ordinary right-click opens freight cargo");
                front.closeContainer();
                front.setShiftKeyDown(true);
                rear.setShiftKeyDown(true);
                car.interact(front, InteractionHand.MAIN_HAND, new Vec3(0, 0.55, 1));
                car.interact(rear, InteractionHand.MAIN_HAND, new Vec3(0, 0.55, -1));
                front.rideTick();
                rear.rideTick();
                test.assertTrue(front.getVehicle() == car && rear.getVehicle() == car && car.getControllingPassenger() == null,
                    "Both passengers stay aboard and neither drives the freight car");
                test.assertTrue(car.getPassengerAttachmentPoint(front, front.getDimensions(front.getPose()), 1).z > 0
                    && car.getPassengerAttachmentPoint(rear, rear.getDimensions(rear.getPose()), 1).z < 0,
                    "The clicked ends select opposite seats");
                double initial = car.getZ();
                car.push(0, 0, 0.08);
                for (int i = 0; i < 8; i++) car.tick();
                test.assertTrue(car.getZ() > initial + 0.35 && car.cargo.countItem(Items.COAL) == 2,
                    "Pushing makes a loaded car coast with both passengers onboard");
                front.setShiftKeyDown(true);
                front.rideTick();
                rear.setShiftKeyDown(true);
                rear.rideTick();
                front.setShiftKeyDown(false);
                rear.setShiftKeyDown(false);
                for (int i = 0; i < 60; i++) {
                    front.baseTick();
                    rear.baseTick();
                }
                car.discard();
            }
        } finally {
            level.getServer().getPlayerList().remove(front);
            level.getServer().getPlayerList().remove(rear);
        }
        test.succeed();
    }

    private static void boreDrivesAndLaysRail(GameTestHelper test) {
        var level = test.getLevel();
        var driver = test.makeMockServerPlayerInLevel();
        GameType.SURVIVAL.updatePlayerAbilities(driver.getAbilities());
        try {
            for (boolean diagonal : new boolean[]{false, true}) {
                BlockPos root = test.absolutePos(new BlockPos(0, diagonal ? 248 : 230, 0));
                int dx = diagonal ? 1 : 0;
                int heading = diagonal ? 14 : 0;
                for (int z = 0; z <= 19; z++) {
                    for (int x = -2; x <= 2; x++) {
                        var ground = root.offset(z * dx + x, -1, z);
                        level.getChunkAt(ground);
                        level.setBlockAndUpdate(ground, Blocks.STONE.defaultBlockState());
                    }
                }
                var kit = Trainmod.TRACK_ITEMS.get(TrackGeometry.Kind.STRAIGHT_6).get();
                driver.setYRot(diagonal ? -45 : 0);
                driver.setPos(TrackGeometry.origin(root).add(-dx, 1, -1));
                driver.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(kit));
                kit.useOn(useAt(driver, root.below(), 0.5, 1, 0.5));
                test.assertTrue(level.getBlockState(root).getValue(TrackBlock.ROTATION) == heading,
                    "A regular six-block straight kit places facing along the cardinal or diagonal approach");
                for (BlockPos cell : TrackGeometry.Kind.STRAIGHT_6.shape(heading, true).cells()) {
                    BlockPos rail = root.offset(cell);
                    level.setBlockAndUpdate(rail, level.getBlockState(rail).setValue(TrackBlock.CONDITION, TrackBlock.Condition.BALLASTED));
                }
                for (int z = 6; z <= 12; z++) {
                    for (int x = -2; x <= 2; x++) {
                        for (int y = 0; y < 4; y++) {
                            level.setBlockAndUpdate(root.offset(dx * z + x, y, z), Blocks.STONE.defaultBlockState());
                        }
                    }
                }
                BlockPos start = root.offset(dx * 2, 0, 2);
                test.assertTrue(at(level, start) != null,
                    "The placed starter kit is complete at " + start.toShortString() + ", diagonal=" + diagonal
                        + ", track=" + level.getBlockState(start));
                var bore = vehicle(level, TrainVehicle.Kind.TUNNEL_BORE, start);
                bore.supplies.setItem(TrainVehicle.RAIL_SUPPLY, new ItemStack(Trainmod.RAIL.get(), 24));
                bore.supplies.setItem(TrainVehicle.TIE_SUPPLY, new ItemStack(Trainmod.TIE_BUNDLE.get(), 12));
                driver.setPos(bore.position().add(0, 1, 0));
                test.assertTrue(driver.startRiding(bore), "Board the bore to drive it along the new test track");
                var firstRail = root.offset(dx * 6, 0, 6);
                boolean cutAhead = false, laidAtCenter = false, tiresUsed = false;
                for (int tick = 0; tick < 95; tick++) {
                    bore.receiveControls(driver, (byte) (TrainControls.FORWARD | TrainControls.WORK));
                    bore.tick();
                    if (!RailNetwork.isRail(level.getBlockState(firstRail))) {
                        cutAhead |= level.getBlockState(firstRail).isAir();
                    } else if (!laidAtCenter) {
                        laidAtCenter = true;
                        test.assertTrue(bore.position().subtract(TrackGeometry.origin(firstRail)).horizontalDistance() < 0.57
                            && bore.supplies.getItem(TrainVehicle.RAIL_SUPPLY).getCount() == 22
                            && bore.supplies.getItem(TrainVehicle.TIE_SUPPLY).getCount() == 11,
                            "The first track is fed near the center using two rails and one tie bundle");
                    }
                    tiresUsed |= BoreDrive.tireExtension(level, bore.cursor()) > 0;
                }
                test.assertTrue(cutAhead && laidAtCenter && tiresUsed,
                    "The nose cuts ahead, the tires support the bore, and the center lays rail"
                        + " (diagonal=" + diagonal + ", cut=" + cutAhead + ", laid=" + laidAtCenter
                        + ", tires=" + tiresUsed + ", travel=" + (bore.getZ() - root.getZ())
                        + ", status=" + overlayMessages(driver) + ")");
                for (int z = 6; z <= 11; z++) {
                    var cell = root.offset(dx * z, 0, z);
                    test.assertTrue(level.getBlockState(cell).is(Trainmod.TRACK.get(TrackGeometry.Kind.UNBALLASTED_STRAIGHT).get())
                        && RailNetwork.railAt(level, cell) != null
                        && level.getBlockState(cell).getValue(TrackBlock.ROTATION) == heading
                        && level.getBlockState(cell.below()).is(Blocks.STONE)
                        && level.getBlockState(cell.above()).isAir(),
                        "The bore lays aligned rail through successive solid slices, diagonal=" + diagonal
                            + ", cell=" + z + ", bore=" + (bore.getZ() - root.getZ()));
                }
                test.assertTrue(bore.getZ() > root.getZ() + 10 && bore.cargo.countItem(Items.COBBLESTONE) >= 60,
                    "The bore advances beyond the starter kit and collects the excavated blocks");
                var regulator = new TrainVehicle(Trainmod.VEHICLES.get(TrainVehicle.Kind.BALLAST_CAR).get(), level,
                    TrainVehicle.Kind.BALLAST_CAR);
                regulator.cargo.setItem(0, new ItemStack(Items.GRAVEL, 2));
                regulator.regulate(level, driver, firstRail.below());
                var finished = at(level, firstRail);
                test.assertTrue(regulator.cargo.isEmpty() && finished != null
                    && level.getBlockState(firstRail).is(Trainmod.TRACK.get(TrackGeometry.Kind.STRAIGHT).get())
                    && level.getBlockState(firstRail).getValue(TrackBlock.ROTATION) == heading
                    && level.getBlockState(firstRail.below()).is(Trainmod.BALLAST.get())
                    && RailNetwork.next(level, finished.rail(), true) != null,
                    "The regulator spends two gravel and preserves the connection to the next unballasted section");
                driver.stopRiding();
                bore.discard();
                for (int tick = 0; tick < 60; tick++) driver.baseTick();
            }
            BlockPos starter = test.absolutePos(new BlockPos(0, 269, 0));
            for (BlockPos ground : BlockPos.betweenClosed(starter.offset(-1, -1, 0), starter.offset(1, -1, 5))) {
                level.setBlockAndUpdate(ground, Blocks.STONE.defaultBlockState());
            }
            straight(level, starter, 0);
            driver.setPos(TrackGeometry.origin(starter).add(1, 0, 0));
            driver.setYRot(0);
            driver.setYHeadRot(0);
            driver.setXRot(0);
            var boreItem = Trainmod.VEHICLE_ITEMS.get(TrainVehicle.Kind.TUNNEL_BORE).get();
            driver.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(boreItem));
            test.assertTrue(boreItem.useOn(useAt(driver, starter, 0.5, TrackGeometry.RAIL_HEIGHT, 0.5))
                == net.minecraft.world.InteractionResult.SUCCESS, "A single starter rail and solid ground support a newly placed bore");
            var bore = level.getEntitiesOfClass(TrainVehicle.class, new net.minecraft.world.phys.AABB(starter).inflate(4))
                .stream().filter(vehicle -> vehicle.kind == TrainVehicle.Kind.TUNNEL_BORE).findFirst().orElseThrow();
            test.assertTrue(bore.getZ() > starter.getZ() + 1 && BoreDrive.onRearRails(level, bore.cursor())
                && BoreDrive.tireExtension(level, bore.cursor()) > 0, "The rear truck starts on the rail and the front tires are on the ground");
            driver.setPos(bore.position());
            test.assertTrue(driver.startRiding(bore), "The driver boards the bore on its short starter");
            for (int tick = 0; tick < 35; tick++) {
                bore.receiveControls(driver, TrainControls.FORWARD);
                bore.tick();
            }
            double stop = starter.getZ() + 1 - VehicleModels.boreInnerRearAxle();
            test.assertTrue(Math.abs(bore.getZ() - stop) < 0.001, "The bore stops before its rear truck leaves the rail");
            for (int tick = 0; tick < 35; tick++) {
                bore.receiveControls(driver, TrainControls.REVERSE);
                bore.tick();
            }
            double reverseStop = starter.getZ() - VehicleModels.rearAxle(bore.kind);
            test.assertTrue(Math.abs(bore.getZ() - reverseStop) < 0.001, "The bore can reverse with its center still past the rail end");
            driver.stopRiding();
            bore.discard();
        } finally {
            level.getServer().getPlayerList().remove(driver);
        }
        test.succeed();
    }

    private static void creativeBore(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        BlockPos start = test.absolutePos(new BlockPos(0, 276, 0));
        for (int x = -12; x <= 0; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(start.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(start.offset(x, -2, z), Blocks.DIRT.defaultBlockState());
            }
        }
        for (int x = 0; x <= 4; x++) straight(level, start.west(x), 4);
        var bore = new TrainVehicle(Trainmod.VEHICLES.get(TrainVehicle.Kind.TUNNEL_BORE).get(), level, TrainVehicle.Kind.TUNNEL_BORE);
        bore.moveToRail(RailNetwork.locate(level, start.west(2), TrackGeometry.origin(start.west(2)), new Vec3(-1, 0, 0)));
        level.addFreshEntity(bore);
        var rail = Trainmod.TRACK_ITEMS.get(TrackGeometry.Kind.STRAIGHT).get();
        bore.cargo.setItem(0, new ItemStack(rail, 64));
        ServerPlayer driver = test.makeMockServerPlayerInLevel();
        GameType.CREATIVE.updatePlayerAbilities(driver.getAbilities());
        try {
            driver.setPos(bore.position());
            driver.setShiftKeyDown(true);
            bore.interact(driver, InteractionHand.MAIN_HAND, Vec3.ZERO);
            test.assertTrue(driver.containerMenu instanceof net.minecraft.world.inventory.ChestMenu menu
                && menu.getContainer() == bore.supplies, "Sneak-using the bore opens its supplies directly");
            driver.closeContainer();
            driver.setShiftKeyDown(false);
            test.assertTrue(driver.startRiding(bore), "The driver boards the bore");
            bore.receiveControls(driver, TrainControls.WORK);
            bore.tick();
            for (int tick = 0; tick < 48; tick++) {
                bore.receiveControls(driver, TrainControls.FORWARD);
                bore.tick();
            }
            BlockPos firstNewRail = start.west(5);
            test.assertTrue(RailNetwork.railAt(level, firstNewRail) != null
                && RailNetwork.railAt(level, firstNewRail.west()) != null
                && level.getBlockState(firstNewRail).is(Trainmod.TRACK.get(TrackGeometry.Kind.UNBALLASTED_STRAIGHT).get())
                && level.getBlockState(firstNewRail.below()).is(Blocks.COBBLESTONE)
                && level.getBlockState(firstNewRail.below(2)).is(Blocks.DIRT),
                "The creative driver can lay rail on a westbound, grass-covered flatland with empty supply slots");
            test.assertTrue(bore.supplies.isEmpty() && bore.cargo.getItem(0).is(rail) && bore.cargo.getItem(0).getCount() == 64,
                "Creative track laying leaves the rail in cargo");
        } finally {
            driver.stopRiding();
            bore.discard();
            level.getServer().getPlayerList().remove(driver);
        }
        test.succeed();
    }

    private static void turnoutRedstone(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = test.absolutePos(new BlockPos(0, 30, 0));
        piece(level, root, TrackGeometry.Kind.WYE, 0, true);
        BlockPos stand = root.west();
        level.setBlockAndUpdate(stand, Trainmod.STAND.get().defaultBlockState());
        straight(level, root.north(), 0);
        test.assertTrue(RailNetwork.railAt(level, root).end(true).x > root.getX(), "Unpowered wye selects left");

        test.onEachTick(() -> {
            if (test.getTick() < 10 || test.getTick() >= 34) return;
            var state = level.getBlockState(stand);
            boolean output = state.getValue(BlockStateProperties.POWERED);
            boolean points = level.getBlockState(root).getValue(BlockStateProperties.POWERED);
            test.assertTrue(output == points && (state.getValue(RailwayBlocks.Stand.TARGET) == RailwayBlocks.RouteTarget.RIGHT) == points,
                "The stand output, turnout position, and indicator change on the same tick");
            test.assertTrue(level.getSignal(stand, Direction.WEST) == (points ? 15 : 0),
                "Neighbors receive the new redstone signal at the start of the throw");
        });
        test.runAtTickTime(10, () -> {
            vehicle(level, TrainVehicle.Kind.BOXCAR, root);
            test.useBlock(test.relativePos(stand));
        });
        test.runAtTickTime(24, () -> {
            var state = level.getBlockState(stand);
            test.assertTrue(RailNetwork.railAt(level, root).route() == 0 && !state.getValue(BlockStateProperties.POWERED)
                && state.getValue(RailwayBlocks.Stand.PENDING), "An occupied turnout queues both the throw and the stand output");
            test.assertTrue(state.getValue(RailwayBlocks.Stand.TARGET) == RailwayBlocks.RouteTarget.LEFT,
                "The stand indicates the actual locked route");
            level.getEntitiesOfClass(TrainVehicle.class, new net.minecraft.world.phys.AABB(root).inflate(2))
                .forEach(net.minecraft.world.entity.Entity::discard);
        });
        test.runAtTickTime(31, () -> {
            var state = level.getBlockState(stand);
            test.assertTrue(RailNetwork.railAt(level, root).route() == 1 && !state.getValue(RailwayBlocks.Stand.PENDING),
                "The pending throw completes after the car clears");
            test.assertTrue(state.getValue(RailwayBlocks.Stand.TARGET) == RailwayBlocks.RouteTarget.RIGHT,
                "The stand turns to the right-hand arrow");
            test.useBlock(test.relativePos(stand));
            test.assertTrue(RailNetwork.railAt(level, root).route() == 0
                && level.getSignal(stand, Direction.WEST) == 0
                && level.getBlockState(stand).getValue(RailwayBlocks.Stand.TARGET) == RailwayBlocks.RouteTarget.LEFT,
                "Clicking a clear turnout removes power and starts the return throw immediately");
        });
        test.runAtTickTime(34, () -> level.setBlockAndUpdate(root.east(), Blocks.REDSTONE_BLOCK.defaultBlockState()));
        test.runAtTickTime(37, () -> {
            var state = level.getBlockState(stand);
            test.assertTrue(RailNetwork.railAt(level, root).route() == 1
                && state.getValue(RailwayBlocks.Stand.TARGET) == RailwayBlocks.RouteTarget.RIGHT
                && level.getSignal(stand, Direction.WEST) == 0,
                "The indicator follows external power without feeding the circuit");
            test.succeed();
        });
    }

    private static void coupledMovement(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos anchor = test.absolutePos(new BlockPos(0, 45, 0));
        int chunkX = Math.floorDiv(anchor.getX(), 16);
        int chunkZ = Math.floorDiv(anchor.getZ(), 16);
        BlockPos start = new BlockPos(chunkX * 16 + 8, anchor.getY(), chunkZ * 16 + 1);
        level.setChunkForced(chunkX, chunkZ, true);
        int quiet = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
        for (int i = 0; i < 6; i++) {
            level.setBlockAndUpdate(start.south(i).below(), Blocks.STONE.defaultBlockState());
            level.setBlock(start.south(i), Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.NORTH_SOUTH), quiet);
        }
        for (int i = 6; i <= 13; i++) {
            straight(level, start.south(i), 0);
        }
        var engine = vehicle(level, TrainVehicle.Kind.LOCOMOTIVE, start.south(5));
        engine.supplies.setItem(0, new ItemStack(Trainmod.DIESEL_BUCKET.get()));
        var car = vehicle(level, TrainVehicle.Kind.BOXCAR, start.south());
        ServerPlayer driver = test.makeMockServerPlayerInLevel();
        driver.setPos(engine.position().add(1, 1, 0));
        driver.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.COUPLER.get()));
        engine.interact(driver, InteractionHand.MAIN_HAND, new Vec3(0, 0, -1));
        car.interact(driver, InteractionHand.MAIN_HAND, new Vec3(0, 0, 1));
        double contact = (engine.kind.length + car.kind.length) / 2.0;
        test.assertTrue(Math.abs(engine.getZ() - car.getZ() - contact) < 0.001,
            "Connecting the cars closes the gap between their couplers");
        driver.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        engine.interact(driver, InteractionHand.MAIN_HAND, Vec3.ZERO);
        test.assertTrue(engine.getFirstPassenger() == driver, "The locomotive can be ridden");
        test.assertTrue(!engine.isClientAuthoritative(), "Cab movement remains server-controlled");
        test.assertTrue(Math.abs(engine.getBoundingBox().getXsize() - 0.90) < 0.001
            && engine.getBoundingBox().getZsize() > 3, "The RS3 uses the freight loading gauge");
        byte[] controls = {TrainControls.FORWARD};
        test.onEachTick(() -> engine.receiveControls(driver, controls[0]));
        test.startSequence()
            .thenWaitUntil(() -> test.assertTrue(engine.position().z > start.getZ() + 9
                && car.position().z > start.getZ() + 6, "Both vehicles should reach mod rails. Positions: "
                + (engine.getZ() - start.getZ()) + ", " + (car.getZ() - start.getZ()) + "; ticks=" + engine.tickCount))
            .thenExecute(() -> {
                test.assertTrue(engine.headlight() == 1, "The leading RS3 headlight lights in forward");
                test.assertTrue(Math.abs(engine.position().z - car.position().z - contact) < 0.001,
                    "Coupled vehicles maintain spacing");
                var saved = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                    net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
                engine.addAdditionalSaveData(saved);
                var input = net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), saved.buildResult());
                test.assertTrue(input.read("Rear", net.minecraft.core.UUIDUtil.CODEC).orElseThrow().equals(car.getUUID()),
                    "The rear link is persisted by UUID");
                controls[0] = TrainControls.REVERSE;
            })
            .thenWaitUntil(() -> test.assertTrue(engine.position().z < start.getZ() + 8
                && car.position().z < start.getZ() + 5, "The locomotive reverses with its coupled car"))
            .thenExecute(() -> {
                test.assertTrue(engine.headlight() == -1, "The opposite headlight lights in reverse");
                driver.stopRiding();
                level.getServer().getPlayerList().remove(driver);
                level.setChunkForced(chunkX, chunkZ, false);
            })
            .thenSucceed();
    }

    private static void impactCoupling(GameTestHelper test) {
        var level = test.getLevel();
        for (boolean fast : new boolean[]{false, true}) {
            BlockPos root = test.absolutePos(new BlockPos(0, fast ? 155 : 152, 0));
            piece(level, root, TrackGeometry.Kind.STRAIGHT_12, 0, true);
            var moving = vehicle(level, TrainVehicle.Kind.BOXCAR, root.south(2));
            var stopped = vehicle(level, TrainVehicle.Kind.GONDOLA, root.south(5));
            double contact = (moving.kind.length + stopped.kind.length) / 2.0;
            stopped.moveToRail(RailNetwork.advance(level, moving.cursor(), contact + 0.04));
            moving.push(0, 0, fast ? 0.07 : 0.019);
            for (int tick = 0; tick < 9; tick++) moving.tick();
            test.assertTrue((moving.coupledId(0) == stopped.getId()) == fast
                && (stopped.coupledId(1) == moving.getId()) == fast,
                "Only a closing impact above one mph links both free ends");
            if (fast) {
                test.assertTrue(Math.abs(stopped.getZ() - moving.getZ() - contact) < 1e-6,
                    "The impact closes the entire gap");
                var player = test.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);
                moving.interact(player, InteractionHand.MAIN_HAND, new Vec3(0, 0.65, 0.45));
                test.assertTrue(moving.coupledId(0) == stopped.getId(), "Clicking the car body keeps the link");
                double couplerY = (VehicleModels.COUPLER_BOTTOM + VehicleModels.COUPLER_HEIGHT / 2) / 16;
                moving.interact(player, InteractionHand.MAIN_HAND, new Vec3(0, couplerY, moving.kind.length / 2.0));
                test.assertTrue(moving.coupledId(0) == -1 && stopped.coupledId(1) == -1,
                    "An empty-hand click on the coupler releases both vehicles");

                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.COUPLER.get()));
                moving.interact(player, InteractionHand.MAIN_HAND, new Vec3(0, 0, 1));
                stopped.interact(player, InteractionHand.MAIN_HAND, new Vec3(0, 0, -1));
                test.assertTrue(moving.coupledId(0) == stopped.getId(), "The tool reconnects the two cars");
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                double along = moving.kind.length / 2.0 - 0.025;
                Vec3 tip = moving.position().add(0, couplerY, along);
                Vec3 eye = tip.add(2, 0.8, 0);
                player.setPos(eye.subtract(0, player.getEyeHeight(), 0));
                double carSide = moving.kind.width / 2.0;
                moving.interact(player, InteractionHand.MAIN_HAND, new Vec3(carSide, couplerY + carSide * 0.4, along));
                test.assertTrue(moving.coupledId(0) == -1 && stopped.coupledId(1) == -1,
                    "A side ray aimed at the coupler releases it while holding an ordinary item");
            }
            moving.discard();
            stopped.discard();
        }
        test.succeed();
    }

    private static void couplingNotice(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos root = test.absolutePos(new BlockPos(0, 300, 0));
        piece(level, root, TrackGeometry.Kind.STRAIGHT_12, 0, true);
        var moving = vehicle(level, TrainVehicle.Kind.BOXCAR, root.south(2));
        var stopped = vehicle(level, TrainVehicle.Kind.GONDOLA, root.south(5));
        double separation = (moving.kind.length + stopped.kind.length) / 2.0;
        stopped.moveToRail(RailNetwork.advance(level, moving.cursor(), separation + 0.04));
        Vec3 joint = stopped.position().add(stopped.couplerOffset(1, stopped.getYRot(), stopped.getXRot(), 0));

        var operator = test.makeMockServerPlayerInLevel();
        var edge = test.makeMockServerPlayerInLevel();
        var outside = test.makeMockServerPlayerInLevel();
        try {
            operator.setPos(joint.add(2, 0, 0));
            edge.setPos(joint.add(16, 0, 0));
            outside.setPos(joint.add(12, 0, 11));
            long operatorBefore = couplingNotices(operator);
            long edgeBefore = couplingNotices(edge);
            long outsideBefore = couplingNotices(outside);

            moving.push(0, 0, 0.07);
            for (int i = 0; i < 9; i++) moving.tick();
            test.assertTrue(couplingNotices(operator) == operatorBefore + 1 && couplingNotices(edge) == edgeBefore + 1
                && couplingNotices(outside) == outsideBefore, "An impact notifies players at most 16 blocks from the couplers once");

            moving.interact(operator, InteractionHand.MAIN_HAND, moving.couplerOffset(0, moving.getYRot(), moving.getXRot(), 0));
            test.assertTrue(couplingNotices(operator) == operatorBefore + 1, "Releasing the link sends no coupling notice");
            operator.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Trainmod.COUPLER.get()));
            stopped.interact(operator, InteractionHand.MAIN_HAND, new Vec3(0, 0, -1));
            moving.interact(operator, InteractionHand.MAIN_HAND, new Vec3(0, 0, 1));
            test.assertTrue(couplingNotices(operator) == operatorBefore + 2 && couplingNotices(edge) == edgeBefore + 1
                && couplingNotices(outside) == outsideBefore, "Manual coupling sends confirmation only to the player using the tool");
        } finally {
            for (var player : List.of(operator, edge, outside)) level.getServer().getPlayerList().remove(player);
        }
        test.succeed();
    }

    private static long couplingNotices(ServerPlayer player) {
        var channel = (io.netty.channel.embedded.EmbeddedChannel) player.connection.getConnection().channel();
        channel.runPendingTasks();
        return channel.outboundMessages().stream().filter(packet ->
            packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat && chat.overlay()
                && chat.content().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents
                && contents.getKey().equals("message.trainmod.coupled")).count();
    }

    static List<net.minecraft.network.chat.Component> overlayMessages(ServerPlayer player) {
        var channel = (io.netty.channel.embedded.EmbeddedChannel) player.connection.getConnection().channel();
        channel.runPendingTasks();
        return channel.outboundMessages().stream()
            .filter(net.minecraft.network.protocol.game.ClientboundSystemChatPacket.class::isInstance)
            .map(net.minecraft.network.protocol.game.ClientboundSystemChatPacket.class::cast)
            .filter(net.minecraft.network.protocol.game.ClientboundSystemChatPacket::overlay)
            .map(net.minecraft.network.protocol.game.ClientboundSystemChatPacket::content).toList();
    }

    private static void excavation(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos start = test.absolutePos(new BlockPos(0, 65, 0));
        straight(level, start, 0);
        BlockPos slice = start.south();
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y < 4; y++) {
                level.setBlock(slice.offset(x, y, 0), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        level.setBlock(slice.offset(-1, 1, 0), Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(slice.offset(1, 2, 0), Blocks.LAVA.defaultBlockState(), Block.UPDATE_CLIENTS);
        TrainVehicle bore = vehicle(level, TrainVehicle.Kind.TUNNEL_BORE, start);
        TrainVehicle freight = new TrainVehicle(Trainmod.VEHICLES.get(TrainVehicle.Kind.GONDOLA).get(), level,
            TrainVehicle.Kind.GONDOLA);
        ServerPlayer driver = test.makeMockServerPlayerInLevel();
        GameType.SURVIVAL.updatePlayerAbilities(driver.getAbilities());
        driver.setPos(bore.position().add(1, 2, 0));
        var origin = RailNetwork.advanceBore(level, at(level, start), 0.7);
        test.assertTrue("bore_need_rail".equals(TrackWork.extendIfNeeded(level, driver, bore, origin, 0, List.of(freight)).key()),
            "Missing supplies block excavation before changing the world");
        test.assertTrue(level.getBlockState(slice).is(Blocks.STONE), "Preflight leaves the slice alone");
        bore.supplies.setItem(TrainVehicle.RAIL_SUPPLY, new ItemStack(Trainmod.RAIL.get(), 4));
        test.assertTrue("bore_need_ties".equals(TrackWork.extendIfNeeded(level, driver, bore, origin, 0, List.of()).key()),
            "An empty tie slot identifies tie bundles specifically");
        bore.supplies.setItem(TrainVehicle.TIE_SUPPLY, new ItemStack(Trainmod.TIE_BUNDLE.get(), 4));
        test.assertTrue("bore_need_stone".equals(TrackWork.extendIfNeeded(level, driver, bore, origin, 0, List.of()).key()),
            "An empty cobblestone slot identifies cobblestone specifically");
        bore.supplies.setItem(TrainVehicle.STONE_SUPPLY, new ItemStack(Items.COBBLESTONE, 4));
        for (var inventory : List.of(freight.cargo, bore.cargo, bore.overflow)) {
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
            }
        }
        test.assertTrue("bore_full".equals(TrackWork.extendIfNeeded(level, driver, bore, origin, 0, List.of(freight)).key())
            && level.getBlockState(slice).is(Blocks.STONE)
            && bore.supplies.getItem(TrainVehicle.RAIL_SUPPLY).getCount() == 4,
            "A full train leaves the cut and the rail supply unchanged");
        freight.cargo.clearContent();
        bore.cargo.clearContent();
        bore.overflow.clearContent();

        BlockPos protectedCell = slice.above(3);
        level.setBlock(protectedCell, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        test.assertTrue("bore_unbreakable".equals(TrackWork.extendIfNeeded(level, driver, bore, origin, 0, List.of(freight)).key())
            && level.getBlockState(slice).is(Blocks.STONE), "An unbreakable block prevents the entire cut");
        level.setBlock(protectedCell, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        test.assertTrue(TrackWork.extendIfNeeded(level, driver, bore, origin, 0, List.of(freight)) == null,
            "The supplied bore excavates a slice");
        test.assertTrue(RailNetwork.railAt(level, slice) != null, "The completed slice contains usable new track");
        test.assertTrue(level.getBlockState(slice).is(Trainmod.TRACK.get(TrackGeometry.Kind.UNBALLASTED_STRAIGHT).get())
            && level.getBlockState(slice.below()).is(Blocks.COBBLESTONE), "Cobblestone supports the bare ties");
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y < 4; y++) {
                if (x != 0 || y != 0) {
                    test.assertTrue(level.getBlockState(slice.offset(x, y, 0)).isAir(), "Bore clears the entire 3×4 face");
                }
            }
        }
        test.assertTrue(freight.cargo.countItem(Items.COBBLESTONE) == 10 && bore.cargo.isEmpty(),
            "Excavation drops go to freight before the buffer");
        test.assertTrue(TrackWork.ballast(level, driver, slice.below(), 1, TrainVehicle.BallastMode.NARROW) == 1
            && TrackBlock.ballasted(level.getBlockState(slice)) && level.getBlockState(slice.below()).is(Blocks.COBBLESTONE),
            "One eighth of a ballast block fills the ties and leaves the roadbed intact");
        test.assertTrue(TrackWork.ballast(level, driver, slice.below(), 8, TrainVehicle.BallastMode.NARROW) == 8,
            "Replacing the roadbed takes one full ballast block");
        test.assertTrue(level.getBlockState(slice.below()).is(Trainmod.BALLAST.get())
            && RailNetwork.railAt(level, slice) != null, "Replacing ballast preserves usable track");
        bore.cargo.setItem(53, new ItemStack(Items.STONE, 6));
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
            net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
        bore.addAdditionalSaveData(output);
        var restored = new TrainVehicle(Trainmod.VEHICLES.get(TrainVehicle.Kind.TUNNEL_BORE).get(), level,
            TrainVehicle.Kind.TUNNEL_BORE);
        restored.readAdditionalSaveData(net.minecraft.world.level.storage.TagValueInput.create(
            net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()));
        test.assertTrue(restored.cargo.getItem(53).is(Items.STONE)
            && restored.supplies.getItem(TrainVehicle.STONE_SUPPLY).getCount() == 3,
            "Sparse cargo and supply slot identities survive saving and loading");
        level.getServer().getPlayerList().remove(driver);
        test.succeed();
    }

    static RailNetwork.Position at(ServerLevel level, BlockPos pos) {
        return RailNetwork.locate(level, pos, TrackGeometry.origin(pos), new Vec3(0, 0, 1));
    }

    static TrainVehicle vehicle(ServerLevel level, TrainVehicle.Kind kind, BlockPos pos) {
        TrainVehicle car = new TrainVehicle(Trainmod.VEHICLES.get(kind).get(), level, kind);
        car.moveToRail(at(level, pos));
        level.addFreshEntity(car);
        return car;
    }

    static void straight(ServerLevel level, BlockPos pos, int rotation) {
        piece(level, pos, TrackGeometry.Kind.STRAIGHT, rotation, true);
    }

    static void piece(ServerLevel level, BlockPos root, TrackGeometry.Kind kind, int rotation, boolean left) {
        piece(level, root, kind, rotation, left, false);
    }

    static void piece(ServerLevel level, BlockPos root, TrackGeometry.Kind kind, int rotation, boolean left, boolean diagonalLow) {
        var cells = kind.shape(rotation, left, true, diagonalLow).cells();
        for (int index = 0; index < cells.size(); index++) {
            BlockPos cell = root.offset(cells.get(index));
            level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(cell, Trainmod.TRACK.get(kind).get().segment(rotation, left, index, diagonalLow));
        }
    }
}
