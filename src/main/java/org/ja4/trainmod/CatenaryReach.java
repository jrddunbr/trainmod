package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Contact wire carries each transformer's feed only as far as its own tap can reach. */
final class CatenaryReach {
    static final int LIMIT = 192;
    private static final double EPSILON = 1e-5;

    record Key(BlockPos root, int route, int height) {
        Key(RailNetwork.Rail rail, int height) { this(rail.root(), rail.route(), height); }
    }

    record Interval(double from, double to) {
        boolean covers(double at) { return at >= from - EPSILON && at <= to + EPSILON; }
    }

    record Line(List<Interval> connected, Map<CatenaryPower.Circuit, List<Interval>> supplies) {
        CatenaryPower.Circuit circuitAt(double at) {
            var banks = new TreeSet<BlockPos>();
            for (var entry : supplies.entrySet()) {
                if (entry.getValue().stream().anyMatch(interval -> interval.covers(at))) banks.addAll(entry.getKey().banks());
            }
            return banks.isEmpty() ? null : new CatenaryPower.Circuit(List.copyOf(banks));
        }

        List<CatenaryPower.Range> ranges(ServerLevel level, Map<CatenaryPower.Circuit, Long> readings) {
            var events = new TreeMap<Double, Long>();
            for (Interval interval : connected) {
                events.putIfAbsent(interval.from, 0L);
                events.putIfAbsent(interval.to, 0L);
            }
            for (var entry : supplies.entrySet()) {
                long available = readings.computeIfAbsent(entry.getKey(), circuit -> circuit.available(level));
                if (available == 0) continue;
                for (Interval interval : entry.getValue()) {
                    events.merge(interval.from, available, Long::sum);
                    events.merge(interval.to, -available, Long::sum);
                }
            }
            List<CatenaryPower.Range> result = new ArrayList<>();
            double from = 0;
            long available = 0;
            for (var event : events.entrySet()) {
                double to = event.getKey();
                double midpoint = (from + to) / 2;
                if (to - from > EPSILON && connected.stream().anyMatch(interval -> interval.covers(midpoint))) {
                    result.add(new CatenaryPower.Range(from, to, available >= CatenaryPower.DRAW));
                }
                available += event.getValue();
                from = to;
            }
            return result;
        }
    }

    private static final class Node {
        final Map<Node, Double> neighbors = new IdentityHashMap<>();
        final Map<CatenaryPower.Circuit, Double> reached = new IdentityHashMap<>();

        void connect(Node other, double length) {
            if (this == other) return;
            neighbors.merge(other, length, Math::min);
            other.neighbors.merge(this, length, Math::min);
        }
    }

    private static final class Route {
        final RailNetwork.Rail rail;
        final List<Interval> connected = new ArrayList<>();
        final TreeMap<Double, Node> nodes = new TreeMap<>();

        Route(RailNetwork.Rail rail) { this.rail = rail; }

        Node at(double offset) { return nodes.computeIfAbsent(offset, ignored -> new Node()); }

        boolean covers(double offset) { return connected.stream().anyMatch(interval -> interval.covers(offset)); }

        double offset(double fromPort, boolean end) { return end ? rail.path().length - fromPort : fromPort; }

        List<Double> offsetsFromPort(boolean end, double reach) {
            double low = end ? rail.path().length - reach : 0;
            double high = end ? rail.path().length : reach;
            return nodes.subMap(low, true, high, true).keySet().stream().map(offset -> offset(offset, end)).toList();
        }
    }

    private record Visit(Node node, CatenaryPower.Circuit circuit, double distance) {}

    static final class Builder {
        private final Map<Key, Route> routes = new HashMap<>();
        private final List<Visit> feeds = new ArrayList<>();

        void add(Catenary.Section section, int height) {
            var route = routes.computeIfAbsent(new Key(section.rail(), height), ignored -> new Route(section.rail()));
            route.connected.add(new Interval(section.low(), section.high()));
            route.at(section.low());
            route.at(section.high());
        }

        void feed(Key key, double offset, CatenaryPower.Circuit circuit) {
            var route = routes.get(key);
            if (route != null && route.covers(offset)) feeds.add(new Visit(route.at(offset), circuit, 0));
        }

        Map<Key, Line> build(Level level) {
            var turnouts = new HashMap<BlockPos, List<Map.Entry<Key, Route>>>();
            for (var entry : routes.entrySet()) {
                Key key = entry.getKey();
                Route route = entry.getValue();
                if (level.getBlockState(key.root).getBlock() instanceof TrackBlock track && track.kind.turnout()) {
                    turnouts.computeIfAbsent(key.root, ignored -> new ArrayList<>()).add(entry);
                }
                for (boolean end : new boolean[]{false, true}) {
                    double from = route.offset(0, end);
                    if (!route.covers(from)) continue;
                    for (var connection : RailNetwork.physicalConnections(level, route.rail, end)) {
                        var other = routes.get(new Key(connection.rail(), key.height));
                        if (other != null && other.covers(connection.distance())) {
                            route.at(from).connect(other.at(connection.distance()), 0);
                        }
                    }
                }
            }
            for (var turnout : turnouts.values()) joinTurnout(turnout);
            for (Route route : routes.values()) {
                for (var entry : route.nodes.entrySet()) {
                    var next = route.nodes.higherEntry(entry.getKey());
                    if (next != null && route.covers((next.getKey() + entry.getKey()) / 2)) {
                        entry.getValue().connect(next.getValue(), next.getKey() - entry.getKey());
                    }
                }
            }
            spread();
            var result = new HashMap<Key, Line>();
            routes.forEach((key, route) -> result.put(key, new Line(merge(route.connected), supplied(route))));
            return result;
        }

        private static void joinTurnout(List<Map.Entry<Key, Route>> turnout) {
            for (int i = 0; i < turnout.size(); i++) {
                var first = turnout.get(i);
                for (int j = i + 1; j < turnout.size(); j++) {
                    var second = turnout.get(j);
                    if (first.getKey().height != second.getKey().height) continue;
                    var a = first.getValue();
                    var b = second.getValue();
                    for (var shared : CatenaryPower.sharedApproaches(a.rail, b.rail)) {
                        var splits = new TreeSet<Double>(List.of(0d, shared.length()));
                        splits.addAll(a.offsetsFromPort(shared.firstEnd(), shared.length()));
                        splits.addAll(b.offsetsFromPort(shared.secondEnd(), shared.length()));
                        for (double split : splits) {
                            double x = a.offset(split, shared.firstEnd()), y = b.offset(split, shared.secondEnd());
                            if (a.covers(x) && b.covers(y)) a.at(x).connect(b.at(y), 0);
                        }
                    }
                }
            }
        }

        private void spread() {
            var queue = new PriorityQueue<>(Comparator.comparingDouble(Visit::distance));
            queue.addAll(feeds);
            while (!queue.isEmpty()) {
                Visit visit = queue.remove();
                Node node = visit.node;
                if (node.reached.getOrDefault(visit.circuit, Double.POSITIVE_INFINITY) <= visit.distance) continue;
                node.reached.put(visit.circuit, visit.distance);
                node.neighbors.forEach((next, length) -> {
                    double distance = visit.distance + length;
                    if (distance <= LIMIT + EPSILON) queue.add(new Visit(next, visit.circuit, distance));
                });
            }
        }
    }

    private static Map<CatenaryPower.Circuit, List<Interval>> supplied(Route route) {
        var result = new IdentityHashMap<CatenaryPower.Circuit, List<Interval>>();
        for (var entry : route.nodes.entrySet()) {
            double from = entry.getKey();
            var next = route.nodes.higherEntry(from);
            if (next == null || !route.covers((from + next.getKey()) / 2)) continue;
            double to = next.getKey();
            entry.getValue().reached.forEach((circuit, distance) ->
                result.computeIfAbsent(circuit, ignored -> new ArrayList<>()).add(new Interval(from, Math.min(to, from + Math.max(0, LIMIT - distance)))));
            next.getValue().reached.forEach((circuit, distance) ->
                result.computeIfAbsent(circuit, ignored -> new ArrayList<>()).add(new Interval(Math.max(from, to - Math.max(0, LIMIT - distance)), to)));
        }
        result.replaceAll((circuit, intervals) -> merge(intervals));
        return result;
    }

    private static List<Interval> merge(List<Interval> intervals) {
        intervals.sort(Comparator.comparingDouble(Interval::from));
        var merged = new ArrayList<Interval>();
        for (Interval interval : intervals) {
            if (interval.to < interval.from - EPSILON) continue;
            if (merged.isEmpty() || merged.getLast().to < interval.from - EPSILON) merged.add(interval);
            else {
                var previous = merged.removeLast();
                merged.add(new Interval(previous.from, Math.max(previous.to, interval.to)));
            }
        }
        return merged;
    }
}
