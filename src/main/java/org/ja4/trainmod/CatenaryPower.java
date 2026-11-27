package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

@EventBusSubscriber(modid = Trainmod.ID)
public final class CatenaryPower {
    static final int DRAW = 20;
    private static final double EPSILON = 1e-5;
    private static final Map<Level, Grid> GRIDS = new WeakHashMap<>();

    private static final class Grid {
        final Set<BlockPos> towers = new TreeSet<>();
        final Set<BlockPos> banks = new TreeSet<>();
        final Set<BlockPos> tips = new TreeSet<>();
        final Set<TrainVehicle> charging = new LinkedHashSet<>();
        Map<Route, List<Section>> wireSections;
        Graph graph;
        Map<BlockPos, Set<BlockPos>> transmission;
    }

    private record Route(BlockPos root, int number) {
        Route(RailNetwork.Rail rail) { this(rail.root(), rail.route()); }
    }

    private record Wire(Catenary.Attachment start, Catenary.Span span) {
        Vec3 above(Vec3 point) {
            Vec3 nearest = span.wire().getFirst();
            double best = Double.POSITIVE_INFINITY;
            Vec3 expected = point.add(0, start.offset(), 0);
            for (int i = 1; i < span.wire().size(); i++) {
                Vec3 first = span.wire().get(i - 1);
                Vec3 delta = span.wire().get(i).subtract(first);
                double horizontal = delta.horizontalDistanceSqr();
                if (horizontal < EPSILON) continue;
                double along = expected.subtract(first).multiply(1, 0, 1).dot(delta) / horizontal;
                Vec3 candidate = first.add(delta.scale(Math.clamp(along, 0, 1)));
                Vec3 error = candidate.subtract(expected);
                double score = error.horizontalDistanceSqr() + 1e-7 * error.y * error.y;
                if (score >= best) continue;
                best = score;
                nearest = candidate;
            }
            return nearest;
        }
    }

    private record Section(Wire wire, Catenary.Section path) {
        int height() { return wire.start.offset(); }

        boolean covers(double distance) {
            return distance >= path.low() - EPSILON && distance <= path.high() + EPSILON;
        }
    }

    record SharedApproach(boolean firstEnd, boolean secondEnd, double length) {}
    private record Tap(CatenaryReach.Key route, double distance, Vec3 point) {}
    record Contact(Vec3 point, Circuit circuit) {}
    record Range(double from, double to, boolean powered) {}
    private record Feed(Tap tap, BlockPos transmission, Junction junction) {}
    private record Graph(Map<Route, List<Section>> sections, Map<CatenaryReach.Key, CatenaryReach.Line> reach,
                         Map<BlockPos, Feed> feeds, Map<Junction, Circuit> circuits) {
        Circuit circuitAt(RailNetwork.Rail rail, int height, double distance) {
            var line = reach.get(new CatenaryReach.Key(rail, height));
            return line == null ? null : line.circuitAt(distance);
        }
    }

    private static final class Junction {
        private Junction parent = this;
        private int rank;

        Junction root() {
            if (parent != this) parent = parent.root();
            return parent;
        }

        void join(Junction other) {
            Junction a = root(), b = other.root();
            if (a == b) return;
            if (a.rank < b.rank) a.parent = b;
            else {
                b.parent = a;
                if (a.rank == b.rank) a.rank++;
            }
        }
    }

    record Circuit(List<BlockPos> banks) {
        long available(ServerLevel level) {
            long energy = 0;
            for (BlockPos pos : banks) {
                if (bankAt(level, pos) instanceof TransformerBank.Bank bank) energy += bank.energy.getAmountAsInt();
            }
            return energy;
        }

        boolean draw(ServerLevel level, TransactionContext transaction) {
            int remaining = DRAW;
            for (BlockPos pos : banks) {
                var bank = bankAt(level, pos);
                if (bank == null) continue;
                remaining -= bank.draw(remaining, transaction);
                if (remaining == 0) return true;
            }
            return false;
        }
    }

    private CatenaryPower() {}

    private static synchronized Grid grid(Level level) { return GRIDS.computeIfAbsent(level, ignored -> new Grid()); }

    public static void blockChanged(Level level) {
        Grid grid;
        synchronized (CatenaryPower.class) { grid = GRIDS.get(level); }
        if (grid != null) {
            grid.wireSections = null;
            grid.graph = null;
            grid.transmission = null;
        }
    }

    static void trackTower(Level level, BlockPos pos, boolean added) {
        if (level != null) track(grid(level).towers, level, pos, added);
    }

    static void trackBank(Level level, BlockPos pos, boolean added) {
        if (level instanceof ServerLevel) track(grid(level).banks, level, pos, added);
    }

    static void trackTip(Level level, BlockPos pos, boolean added) {
        if (level != null) track(grid(level).tips, level, pos, added);
    }

    private static void track(Set<BlockPos> positions, Level level, BlockPos pos, boolean added) {
        if (added) positions.add(pos.immutable());
        else positions.remove(pos);
        blockChanged(level);
    }

    static Set<BlockPos> transmissionNeighbors(ServerLevel level, BlockPos tip) {
        return transmission(level).getOrDefault(tip, Set.of());
    }

    static Set<BlockPos> trackedTips(Level level) { return grid(level).tips; }

    private static Map<BlockPos, Set<BlockPos>> transmission(ServerLevel level) {
        Grid grid = grid(level);
        if (grid.transmission != null) return grid.transmission;
        var links = TransmissionLines.connect(level, grid.tips);
        grid.transmission = links;
        // Confirmed endpoints remain useful to the renderer after a chunk goes out of view.
        for (BlockPos pos : grid.tips) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof TransmissionExtension.Tip tip) {
                tip.updateNeighbors(level, TransmissionLines.visibleNeighbors(level, pos, links.getOrDefault(pos, Set.of()), tip.neighbors()));
            }
        }
        return links;
    }

    private static Graph graph(ServerLevel level) {
        Grid grid = grid(level);
        if (grid.graph == null) grid.graph = build(level, grid);
        return grid.graph;
    }

    private static Map<Route, List<Section>> wireSections(Level level) {
        Grid grid = grid(level);
        if (grid.wireSections != null) return grid.wireSections;
        Map<Route, List<Section>> sections = new HashMap<>();
        for (BlockPos pos : grid.towers) {
            if (!level.isLoaded(pos)) continue;
            for (var start : Catenary.attachments(level, pos, level.getBlockState(pos))) {
                for (int direction : new int[]{-1, 1}) {
                    for (var span : Catenary.spansFrom(level, start, direction)) {
                        if (pos.compareTo(span.end().tower()) >= 0 || span.wire().size() < 2) continue;
                        Wire wire = new Wire(start, span);
                        for (var path : span.sections()) {
                            if (path.high() - path.low() < EPSILON) continue;
                            sections.computeIfAbsent(new Route(path.rail()), ignored -> new ArrayList<>()).add(new Section(wire, path));
                        }
                    }
                }
            }
        }
        shareTurnoutWires(level, sections);
        grid.wireSections = sections;
        return sections;
    }

    private static Graph build(ServerLevel level, Grid grid) {
        var sections = wireSections(level);
        Map<BlockPos, Feed> feeds = new LinkedHashMap<>();
        Map<BlockPos, Junction> lines = new HashMap<>();
        transmission(level).forEach((tip, neighbors) -> {
            Junction line = lines.computeIfAbsent(tip, ignored -> new Junction());
            for (BlockPos neighbor : neighbors) line.join(lines.computeIfAbsent(neighbor, ignored -> new Junction()));
        });
        for (BlockPos pos : grid.banks) {
            var bank = bankAt(level, pos);
            if (bank == null || !bank.complete()) continue;
            Direction facing = bank.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
            Tap tap = findTap(level, pos, facing, sections);
            BlockPos tip = TransmissionLines.nearestBankTip(level, pos, facing, lines.keySet());
            if (tap == null && tip == null) continue;
            Junction junction = tip == null ? new Junction() : lines.get(tip);
            feeds.put(pos, new Feed(tap, tip, junction));
        }
        Map<Junction, List<BlockPos>> grouped = new HashMap<>();
        feeds.forEach((pos, feed) -> grouped.computeIfAbsent(feed.junction.root(), ignored -> new ArrayList<>()).add(pos));
        Map<Junction, Circuit> circuits = new HashMap<>();
        grouped.forEach((junction, banks) -> circuits.put(junction, new Circuit(List.copyOf(banks))));
        var reach = new CatenaryReach.Builder();
        sections.values().forEach(parts -> parts.forEach(section -> reach.add(section.path, section.height())));
        feeds.values().forEach(feed -> {
            if (feed.tap != null) reach.feed(feed.tap.route, feed.tap.distance, circuits.get(feed.junction.root()));
        });
        return new Graph(sections, reach.build(level), feeds, circuits);
    }

    static boolean canTap(Level level, BlockPos root, Direction facing) {
        return findTap(level, root, facing, wireSections(level)) != null;
    }

    private static void shareTurnoutWires(Level level, Map<Route, List<Section>> sections) {
        Map<BlockPos, List<Section>> turnouts = new HashMap<>();
        for (var entry : sections.entrySet()) {
            BlockPos root = entry.getKey().root;
            if (level.getBlockState(root).getBlock() instanceof TrackBlock track && track.kind.turnout()) {
                turnouts.computeIfAbsent(root, ignored -> new ArrayList<>()).addAll(entry.getValue());
            }
        }
        for (var entry : turnouts.entrySet()) {
            var rails = RailNetwork.physicalRailsAt(level, entry.getKey());
            var paths = entry.getValue();
            for (int i = 0; i < rails.size(); i++) {
                var first = rails.get(i);
                for (int j = i + 1; j < rails.size(); j++) {
                    var second = rails.get(j);
                    for (var shared : sharedApproaches(first, second)) {
                        var a = paths.stream().filter(section -> section.path.rail().route() == first.route()).toList();
                        var b = paths.stream().filter(section -> section.path.rail().route() == second.route()).toList();
                        copySharedWire(sections, a, second, shared.firstEnd(), shared.secondEnd(), shared.length());
                        copySharedWire(sections, b, first, shared.secondEnd(), shared.firstEnd(), shared.length());
                    }
                }
            }
        }
    }

    static List<SharedApproach> sharedApproaches(RailNetwork.Rail first, RailNetwork.Rail second) {
        // Only turnout routes that begin or end on the same track port can share a wire.
        List<SharedApproach> shared = new ArrayList<>();
        for (boolean firstEnd : new boolean[]{false, true}) {
            for (boolean secondEnd : new boolean[]{false, true}) {
                var a = firstEnd ? first.path().points().reversed() : first.path().points();
                var b = secondEnd ? second.path().points().reversed() : second.path().points();
                if (a.getFirst().distanceToSqr(b.getFirst()) > EPSILON * EPSILON) continue;
                Vec3 aStep = a.get(1).subtract(a.getFirst()), bStep = b.get(1).subtract(b.getFirst());
                if (aStep.normalize().dot(bStep.normalize()) >= 1 - EPSILON) {
                    shared.add(new SharedApproach(firstEnd, secondEnd, Math.min(aStep.length(), bStep.length())));
                }
            }
        }
        return shared;
    }

    private static void copySharedWire(Map<Route, List<Section>> routes, List<Section> from, RailNetwork.Rail to,
                                       boolean sourceEnd, boolean targetEnd, double sharedLength) {
        for (var section : from) {
            double low = fromPort(section.path, sourceEnd, false);
            double high = Math.min(sharedLength, fromPort(section.path, sourceEnd, true));
            if (high <= low + EPSILON) continue;
            double start = targetEnd ? to.path().length - high : low;
            double end = targetEnd ? to.path().length - low : high;
            routes.computeIfAbsent(new Route(to), ignored -> new ArrayList<>())
                .add(new Section(section.wire, new Catenary.Section(to, start, end)));
        }
    }

    private static double fromPort(Catenary.Section path, boolean end, boolean high) {
        if (end) return path.rail().path().length - (high ? path.low() : path.high());
        return high ? path.high() : path.low();
    }

    private static Tap findTap(Level level, BlockPos root, Direction facing, Map<Route, List<Section>> sections) {
        BlockPos target = root.relative(facing, TransformerBank.TRACK_OFFSET);
        Vec3 across = Vec3.atLowerCornerOf(facing.getUnitVec3i());
        Tap best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int dy : new int[]{0, -1, 1}) {
            BlockPos cell = target.above(dy);
            Vec3 center = TrackGeometry.origin(cell);
            for (var rail : RailNetwork.physicalRailsAt(level, cell)) {
                double distance = Catenary.project(rail, center);
                Vec3 point = rail.point(distance);
                double offset = point.subtract(center).horizontalDistance();
                double angle = Math.abs(across.dot(rail.path().tangent(distance)));
                if (offset > 0.35 || angle > 0.82) continue;
                for (Section section : sections.getOrDefault(new Route(rail), List.of())) {
                    if (!section.covers(distance)) continue;
                    Vec3 contact = section.wire.above(point);
                    double score = offset + angle + 2 * Math.abs(dy) + 0.1 * Math.abs(contact.y - (root.getY() + 3));
                    if (score >= bestScore) continue;
                    bestScore = score;
                    best = new Tap(new CatenaryReach.Key(rail, section.height()), distance, contact);
                }
            }
            if (best != null) break;
        }
        return best;
    }

    static List<Contact> contacts(ServerLevel level, RailNetwork.Position position, Vec3 head) {
        if (position == null) return List.of();
        Graph graph = graph(level);
        List<Contact> contacts = new ArrayList<>();
        for (Section section : graph.sections.getOrDefault(new Route(position.rail()), List.of())) {
            var rail = section.path.rail();
            double distance = Catenary.project(rail, head);
            if (!section.covers(distance)) continue;
            var contact = new Contact(section.wire.above(rail.point(distance)), graph.circuitAt(rail, section.height(), distance));
            if (!contacts.contains(contact)) contacts.add(contact);
        }
        return contacts;
    }

    static List<Contact> headContacts(ServerLevel level, RailNetwork.Rail route, VehicleHitboxes.Frame frame, double z) {
        Graph graph = graph(level);
        List<Contact> contacts = new ArrayList<>();
        for (Section section : graph.sections.getOrDefault(new Route(route), List.of())) {
            var rail = section.path.rail();
            var wire = section.wire.span.wire();
            for (int i = 1; i < wire.size(); i++) {
                Vec3 start = wire.get(i - 1), end = wire.get(i);
                double first = frame.local(start).z - z, last = frame.local(end).z - z;
                if (Math.abs(first - last) < EPSILON || first * last > EPSILON * EPSILON) continue;
                Vec3 point = start.lerp(end, Math.clamp(first / (first - last), 0, 1));
                double distance = Catenary.project(rail, point);
                if (!section.covers(distance) || rail.point(distance).subtract(point).horizontalDistanceSqr() > 1e-6) continue;
                var contact = new Contact(point, graph.circuitAt(rail, section.height(), distance));
                if (!contacts.contains(contact)) contacts.add(contact);
            }
        }
        return contacts;
    }

    static List<Range> wireRanges(ServerLevel level, RailNetwork.Rail rail) {
        Graph graph = graph(level);
        var edges = new TreeMap<Double, int[]>();
        Map<Circuit, Long> readings = new IdentityHashMap<>();
        for (int height : new int[]{CatenaryTower.Kind.SINGLE.height, CatenaryTower.Kind.GANTRY.height}) {
            var line = graph.reach.get(new CatenaryReach.Key(rail, height));
            if (line == null) continue;
            for (Range range : line.ranges(level, readings)) {
                countWire(edges.computeIfAbsent(range.from(), ignored -> new int[2]), range.powered(), 1);
                countWire(edges.computeIfAbsent(range.to(), ignored -> new int[2]), range.powered(), -1);
            }
        }
        List<Range> ranges = new ArrayList<>();
        double from = 0;
        int connected = 0, live = 0;
        for (var edge : edges.entrySet()) {
            double to = edge.getKey();
            if (connected > 0 && to - from > EPSILON) {
                boolean powered = live > 0;
                if (!ranges.isEmpty() && ranges.getLast().powered() == powered && ranges.getLast().to() >= from - EPSILON) {
                    from = ranges.removeLast().from();
                }
                ranges.add(new Range(from, to, powered));
            }
            connected += edge.getValue()[0];
            live += edge.getValue()[1];
            from = to;
        }
        return ranges;
    }

    private static void countWire(int[] counts, boolean powered, int change) {
        counts[0] += change;
        if (powered) counts[1] += change;
    }

    private static TransformerBank.Bank bankAt(ServerLevel level, BlockPos pos) {
        var entity = level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
        return entity instanceof TransformerBank.Bank bank && !bank.isRemoved() ? bank : null;
    }

    static void queueCharge(ServerLevel level, TrainVehicle vehicle) { grid(level).charging.add(vehicle); }

    @SubscribeEvent
    static void chunkLoaded(ChunkEvent.Load event) {
        if (event.getLevel() instanceof Level level) blockChanged(level);
    }

    @SubscribeEvent
    static void chunkUnloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof Level level) blockChanged(level);
    }

    @SubscribeEvent
    static void finishTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Grid grid = grid(level);
        for (TrainVehicle vehicle : grid.charging) vehicle.chargeReserve(level);
        grid.charging.clear();
        if (!grid.tips.isEmpty()) transmission(level);
        if (!grid.banks.isEmpty()) {
            Graph graph = graph(level);
            Map<Circuit, Long> readings = new IdentityHashMap<>();
            for (BlockPos pos : grid.banks) {
                var bank = bankAt(level, pos);
                if (bank == null) continue;
                Feed feed = graph.feeds.get(pos);
                Circuit circuit = feed == null ? null : graph.circuits.get(feed.junction.root());
                bank.updateMeter(circuit == null ? 0 : readings.computeIfAbsent(circuit, key -> key.available(level)),
                    circuit == null ? TransformerBank.CAPACITY : (long) circuit.banks.size() * TransformerBank.CAPACITY,
                    feed == null || feed.tap == null ? null : feed.tap.point, feed == null ? null : feed.transmission);
            }
        }
        CatenaryOverlay.send(level);
    }
}
