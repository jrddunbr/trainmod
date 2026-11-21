package org.ja4.trainmod;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

public final class TrackGeometryTest {
    @Test
    void ballastUsesAFullBlockWithLinearShouldersAndACommonFootprint() {
        expect(BallastProfile.foundationHeight(0.5, 0.5) == 1, "The corners of the ballast block are level");
        for (double distance : new double[]{0.5, 0.75, 1.0, 1.25, 1.5}) {
            double height = 1.5 - distance;
            expect(BallastProfile.foundationHeight(distance, 0) == height, "The side falls at 45 degrees");
            expect(BallastProfile.foundationHeight(0, -distance) == height, "The end falls at 45 degrees");
        }
        var raised = BallastProfile.bed(true);
        expect(raised.length == 4 && raised[0].across() == -1.5 && raised[3].across() == 1.5
            && raised[0].height() == BallastProfile.FOOT && raised[3].height() == BallastProfile.FOOT,
            "The raised shoulders use one plane on each side and end at the same footprint");
        expect(raised[1].across() == -0.5 && raised[2].across() == 0.5
            && raised[1].height() == raised[2].height(), "The raised crest covers the full width of the core");
    }

    @Test
    void allShapesFollowTheGrid() {
        for (TrackGeometry.Kind kind : TrackGeometry.Kind.values()) {
            int base = kind == TrackGeometry.Kind.DIAGONAL ? 14 : 0;
            var reference = kind.shape(base, true);
            expect(reference.cells().getFirst().equals(BlockPos.ZERO), kind + " has a root");
            expect(reference.cells().size() <= 48, kind + " segment IDs fit the block state");
            for (int quarter = 0; quarter < 4; quarter++) {
                for (boolean mirror : new boolean[]{false, true}) {
                    var shape = kind.shape((base + quarter * 4) % 16, !mirror);
                    boolean flipped = mirror && kind.mirrored();
                    for (int i = 0; i < shape.cells().size(); i++) {
                        Vec3 expected = TrackGeometry.transform(Vec3.atLowerCornerOf(reference.cells().get(i)),
                            quarter, flipped);
                        expect(BlockPos.containing(expected).equals(shape.cells().get(i)), kind + " cell transform");
                    }
                    int routes = kind == TrackGeometry.Kind.SCISSORS ? 4 : kind.turnout() || kind.crossing() ? 2 : 1;
                    expect(shape.routes().size() == routes, kind + " route count");
                    for (int route = 0; route < shape.routes().size(); route++) {
                        var path = shape.routes().get(route);
                        var originalPath = reference.routes().get(route);
                        for (boolean end : new boolean[]{false, true}) {
                            Vec3 expected = TrackGeometry.transform(originalPath.endpoint(end), quarter, flipped);
                            expect(path.endpoint(end).distanceToSqr(expected) < 1e-9, kind + " endpoint transform");
                        }
                        for (int i = 1; i < 100; i++) {
                            double offset = path.length * i / 100;
                            expect(Math.abs(path.project(path.point(offset)) - offset) < 1e-6,
                                kind + " arc-length projection");
                        }
                    }
                }
            }
        }
        curve(TrackGeometry.Kind.CURVE_SMALL, 4, 2);
        curve(TrackGeometry.Kind.CURVE_MEDIUM, 8, 3);
        curve(TrackGeometry.Kind.CURVE_LARGE, 12, 5);
        grade(TrackGeometry.Kind.GRADE_6, 6);
        grade(TrackGeometry.Kind.GRADE_8, 8);
        var parallel = TrackGeometry.Kind.TURNOUT_PARALLEL.shape(0, true).routes();
        expect(parallel.get(1).endpoint(true).subtract(parallel.get(0).endpoint(true))
            .distanceToSqr(new Vec3(3, 0, 0)) < 1e-9, "Parallel track centers are three blocks apart");
    }

    @Test
    void theBankLiftsTheOutsideRailAndEasesOverAnEnd() {
        var kind = TrackGeometry.Kind.CURVE_LARGE;
        for (boolean left : new boolean[]{true, false}) {
            var path = kind.shape(0, left).routes().getFirst();
            double outside = (left ? -1 : 1) * CurveBanking.GAUGE / 2;
            double height = CurveBanking.lift(kind, path, path.length / 2, 0, outside);
            expect(Math.abs(height - CurveBanking.GAUGE * CurveBanking.MAX_SLOPE) < 1e-9,
                "The outer rail reaches the six-inch-equivalent cap");
            expect(CurveBanking.lift(kind, path, path.length / 2, 0, -outside) == 0,
                "The inner rail stays at level elevation");
            expect(Math.abs(CurveBanking.lift(kind, path, 0, CurveBanking.variant(2, 0), outside) - height) < 1e-9,
                "A compatible approach starts at full cant");
        }
        for (var curve : List.of(TrackGeometry.Kind.CURVE_SMALL, TrackGeometry.Kind.CURVE_MEDIUM, kind)) {
            var path = curve.shape(0, true).routes().getFirst();
            double fullBank = CurveBanking.slope(curve, path, path.length / 2, 0);
            double transition = curve == TrackGeometry.Kind.CURVE_SMALL ? 1 : 2;
            for (double end : new double[]{0, path.length}) {
                double direction = end == 0 ? 1 : -1;
                expect(Math.abs(CurveBanking.slope(curve, path, end + direction * transition / 2, 0)
                    - fullBank / 2) < 1e-9, curve + " reaches half cant halfway into either transition");
                expect(Math.abs(CurveBanking.slope(curve, path, end + direction * transition, 0)
                    - fullBank) < 1e-9, curve + " reaches full cant at the end of either transition");
            }
        }
    }

    @Test
    void bothBladesSlideTogetherAndStayAttachedAtTheHeel() {
        for (var kind : TrackGeometry.Kind.values()) {
            if (!kind.turnout()) continue;
            for (int rotation : kind.rotations()) {
                for (boolean left : new boolean[]{true, false}) {
                    var shape = kind.shape(rotation, left);
                    var layout = SwitchLayout.of(kind, shape);
                    expect(layout.panels().size() == (kind == TrackGeometry.Kind.SCISSORS ? 4 : 1), "Correct panel count");
                    for (var panel : layout.panels()) {
                        Vec3 firstThrow = panel.first().point(0, 1).subtract(panel.first().point(0, 0));
                        Vec3 secondThrow = panel.second().point(0, 0).subtract(panel.second().point(0, 1));
                        expect(firstThrow.lengthSqr() > 0.009 && firstThrow.distanceToSqr(secondThrow) < 1e-10,
                            "Both toes move together in the same direction");
                        for (var blade : panel.blades()) {
                            expect(blade.point(1, 0).equals(blade.point(1, 1)), "The heel is stationary");
                            double heelDistance = blade.reverse() ? layout.fixedEnd(blade.route(), blade.side(), blade.path().length)
                                : layout.fixedStart(blade.route(), blade.side());
                            expect(blade.point(1, 0).equals(SwitchLayout.railPoint(blade.path(), heelDistance, blade.side())),
                                "The moving rail stays connected to its fixed closure rail");
                            double outer = -blade.side();
                            expect(blade.reverse() ? layout.fixedEnd(blade.route(), outer, blade.path().length) == blade.path().length
                                : layout.fixedStart(blade.route(), outer) == 0, "The stock rail extends through the points");
                        }
                    }
                }
            }
        }
    }

    @Test
    void theSwitchThrowEasesOverFiveTicksAndCanReverseInPlace() {
        var motion = new PartMotion(0);
        motion.moveTo(1, 20);
        expect(motion.at(20) == 0, "The point and stand motion share the onset");
        expect(motion.at(22.5) == 0.5, "Both are halfway through at the same time");
        expect(motion.at(25) == 1, "Both reach their endpoints in five ticks");
        double before = motion.at(22);
        motion.moveTo(0, 22);
        expect(motion.at(22) == before, "Reversing the throw starts from the current position");
        expect(motion.at(27) == 0, "Both return to the initial state");
    }

    @Test
    void tiesVaryByPositionAndStayCloseToTheirOriginalLengthsAndAngles() {
        int skewed = 0;
        int samples = 0;
        var patterns = new java.util.HashSet<Integer>();
        for (int x = 0; x < 80; x++) {
            var pos = new BlockPos(x, 64, -3);
            int pattern = TieVariation.patternAt(pos);
            patterns.add(pattern);
            expect(pattern == TieVariation.patternAt(pos), "A given block keeps its pattern");
            for (int tie = 0; tie < 12; tie++) {
                var uniform = TieVariation.forTie(0, 0, tie);
                var varied = TieVariation.forTie(pattern, 0, tie);
                expect(uniform.length() == 1 && uniform.yaw() == 0, "Uniform ties keep the original dimensions");
                expect(varied.length() >= 0.95 && varied.length() <= 1.05, "Tie length stays within five percent");
                expect(Math.abs(varied.yaw()) <= 4, "Tie skew stays within four degrees");
                skewed += varied.yaw() == 0 ? 0 : 1;
                samples++;
            }
        }
        expect(patterns.size() == TieVariation.PATTERNS - 1, "The track uses all available varied patterns");
        expect(skewed > 0 && skewed < samples / 2, "Only occasional ties are skewed");
    }

    private static void curve(TrackGeometry.Kind kind, int run, int offset) {
        var path = kind.shape(0, true).routes().getFirst();
        expect(path.endpoint(true).equals(new Vec3(offset + 0.5, 0, run + 0.5)), kind + " exits on the grid");
        expect(path.tangent(path.length).dot(new Vec3(1, 0, 1).normalize()) > 0.999, kind + " turns through 45°");
    }

    private static void grade(TrackGeometry.Kind kind, int run) {
        var path = kind.shape(0, true).routes().getFirst();
        expect(path.endpoint(true).equals(new Vec3(0, 1, run + 0.5)), kind + " rises one block");
        Vec3 slope = path.tangent(path.length / 2);
        expect(Math.abs(slope.z / slope.y - run) < 1e-9, kind + " has the specified grade");
    }

    private static void expect(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
