package org.ja4.trainmod;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** The same stock rails and heel positions are used by the track mesh and the moving points. */
final class SwitchLayout {
    private static final Map<TrackGeometry.Shape, SwitchLayout> CACHE = new ConcurrentHashMap<>();
    static final double GAUGE = CurveBanking.GAUGE / 2;
    static final double POINT_CLEARANCE = 1.78;

    record Blade(int route, TrackGeometry.Path path, boolean reverse, double toe, double heel, double side) {
        double distance(double fraction) {
            return distance(path, reverse, toe + (heel - toe) * fraction);
        }

        private static double distance(TrackGeometry.Path path, boolean reverse, double at) {
            return reverse ? path.length - at : at;
        }

        Vec3 point(double fraction, double opening) {
            double at = distance(fraction);
            double throwAtToe = 0.038 + 0.10 * opening;
            double inset = throwAtToe * (1 - fraction) * (1 - fraction);
            return railPoint(path, at, side - Math.copySign(inset, side));
        }

        Vec3 lateral(double fraction) { return SwitchLayout.lateral(path.tangent(distance(fraction))); }
    }

    record Panel(Blade first, Blade second) {
        List<Blade> blades() { return List.of(first, second); }

        BlockPos control() { return TrackGeometry.cell(first.path.point(first.distance(0))); }

        boolean divergesLeft() {
            Vec3 direction = first.path.tangent(first.distance(0)).scale(first.reverse ? -1 : 1);
            Vec3 branch = second.path.point(second.distance(1)).subtract(first.path.point(first.distance(1)));
            return branch.dot(lateral(direction)) > 0;
        }

        boolean containsVehicle(Vec3 center) {
            for (Blade blade : blades()) {
                double at = blade.path.project(center);
                double from = Math.min(blade.distance(0), blade.distance(1)) - POINT_CLEARANCE;
                double to = Math.max(blade.distance(0), blade.distance(1)) + POINT_CLEARANCE;
                if (at > from && at < to && blade.path.point(at).distanceToSqr(center) < 1.1) return true;
            }
            return false;
        }
    }

    private final List<Panel> panels;
    private final List<ScissorRoute> scissorRoutes;

    record ScissorRoute(Panel entrance, Panel exit, List<TrackGeometry.Path> paths, boolean diverging) {
        TrackGeometry.Path path(boolean startOpen, boolean endOpen) {
            return paths.get((startOpen ? 1 : 0) | (endOpen ? 2 : 0));
        }

        private static ScissorRoute build(int route, TrackGeometry.Path path, Panel entrance, Panel exit) {
            Blade start = entrance.first.route == route ? entrance.first : entrance.second;
            Blade end = exit.first.route == route ? exit.first : exit.second;
            double closedStart = start.distance(1) + POINT_CLEARANCE;
            double closedEnd = end.distance(1) - POINT_CLEARANCE;
            return new ScissorRoute(entrance, exit, List.of(path.section(closedStart, closedEnd),
                path.section(0, closedEnd), path.section(closedStart, path.length), path), route >= 2);
        }
    }

    private SwitchLayout(TrackGeometry.Kind kind, TrackGeometry.Shape shape) {
        var routes = shape.routes();
        if (kind == TrackGeometry.Kind.SCISSORS) {
            panels = List.of(panel(routes, 0, 2, false, 2.13), panel(routes, 1, 3, false, 2.13),
                panel(routes, 0, 3, true, 2.13), panel(routes, 1, 2, true, 2.13));
            scissorRoutes = List.of(ScissorRoute.build(0, routes.get(0), panels.get(0), panels.get(2)),
                ScissorRoute.build(1, routes.get(1), panels.get(1), panels.get(3)),
                ScissorRoute.build(2, routes.get(2), panels.get(0), panels.get(3)),
                ScissorRoute.build(3, routes.get(3), panels.get(1), panels.get(2)));
        } else if (kind.turnout()) {
            panels = List.of(panel(routes, 0, 1, false, 0.44));
            scissorRoutes = List.of();
        } else {
            panels = List.of();
            scissorRoutes = List.of();
        }
    }

    static SwitchLayout of(TrackGeometry.Kind kind, TrackGeometry.Shape shape) {
        return CACHE.computeIfAbsent(shape, value -> new SwitchLayout(kind, value));
    }

    List<Panel> panels() { return panels; }

    Panel panelAt(BlockPos cell) {
        for (Panel panel : panels) {
            if (panel.control().equals(cell)) return panel;
        }
        return null;
    }

    List<ScissorRoute> scissorRoutes() { return scissorRoutes; }

    double fixedStart(int route, double side) {
        for (Panel panel : panels) {
            for (Blade blade : panel.blades()) {
                if (blade.route == route && blade.side == side && !blade.reverse) return blade.distance(1);
            }
        }
        return 0;
    }

    double fixedEnd(int route, double side, double length) {
        for (Panel panel : panels) {
            for (Blade blade : panel.blades()) {
                if (blade.route == route && blade.side == side && blade.reverse) return blade.distance(1);
            }
        }
        return length;
    }

    private static Panel panel(List<TrackGeometry.Path> routes, int first, int second, boolean reverse, double toe) {
        var a = routes.get(first);
        var b = routes.get(second);
        return new Panel(blade(first, a, b, reverse, toe), blade(second, b, a, reverse, toe));
    }

    private static Blade blade(int route, TrackGeometry.Path path, TrackGeometry.Path adjacent, boolean reverse, double toe) {
        // Put the heel where the two inner running rails are clear of their stock rails.
        for (double heel = toe + 0.55; heel <= path.length - 0.2; heel += 0.04) {
            double distance = Blade.distance(path, reverse, heel);
            Vec3 point = path.point(distance);
            Vec3 across = adjacent.point(adjacent.project(point)).subtract(point);
            double separation = across.dot(lateral(path.tangent(distance)));
            if (Math.abs(separation) >= 0.25) {
                return new Blade(route, path, reverse, toe, heel, Math.copySign(GAUGE, separation));
            }
        }
        throw new IllegalStateException("Turnout paths never reach their heel spread");
    }

    static Vec3 railPoint(TrackGeometry.Path path, double distance, double side) {
        return path.point(distance).add(lateral(path.tangent(distance)).scale(side));
    }

    static Vec3 lateral(Vec3 tangent) { return new Vec3(tangent.z, 0, -tangent.x).normalize(); }
}
