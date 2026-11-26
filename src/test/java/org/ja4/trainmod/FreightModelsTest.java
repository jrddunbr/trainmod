package org.ja4.trainmod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

final class FreightModelsTest {
    @Test
    void adjacentPanelsWithTheSamePaintShareExteriorFaces() {
        var first = new VehicleModels.Box(0, 0, 0, 0, 1, 1, 1);
        var adjoining = new VehicleModels.Box(0, 1, 0, 0, 1, 1, 1);
        var separated = new VehicleModels.Box(0, 1.125f, 0, 0, 1, 1, 1);
        var differentPaint = new VehicleModels.Box(1, 1, 0, 0, 1, 1, 1);
        assertEquals(6, VehicleModels.visibleFaces(List.of(first, adjoining)).size());
        assertEquals(12, VehicleModels.visibleFaces(List.of(first, separated)).size());
        assertEquals(10, VehicleModels.visibleFaces(List.of(first, differentPaint)).size());
    }

    @Test
    void gondolaAndBallastCarHaveOneOpenTub() {
        for (var kind : List.of(TrainVehicle.Kind.GONDOLA, TrainVehicle.Kind.BALLAST_CAR)) {
            var faces = VehicleModels.visibleFaces(VehicleModels.bodyBoxes(kind));
            var deck = faces.stream().filter(face -> face.panel() == VehicleModels.Panel.DECK).toList();
            assertEquals(1, deck.size(), kind.id());
            var floor = deck.getFirst().bounds();
            assertEquals(7, floor.y(), 0.00001, kind.id());
            assertEquals(VehicleModels.GONDOLA_FLOOR_HALF_WIDTH * kind.width, floor.width(), 0.00001, kind.id());
            assertEquals(VehicleModels.GONDOLA_FLOOR_HALF_LENGTH * 2 * kind.length / 2.5f,
                floor.length(), 0.00001, kind.id());
            var sides = faces.stream().filter(face -> face.panel() == VehicleModels.Panel.GONDOLA_WALL).toList();
            assertEquals(2, sides.size(), kind.id());
            for (var side : sides) {
                assertEquals(13, side.bounds().y() + side.bounds().height(), 0.00001, kind.id());
                Direction inside = side.direction().getOpposite();
                float edge = inside == Direction.EAST ? floor.x() : floor.x() + floor.width();
                assertTrue(faces.stream().anyMatch(face -> face.direction() == inside
                    && Math.abs(face.bounds().x() - edge) < 0.00001
                    && Math.abs(face.bounds().y() - floor.y()) < 0.00001), kind.id());
            }
        }
    }

    @Test
    void vehiclesHaveNoOverlappingCoplanarFaces() {
        var models = new ArrayList<List<VehicleModels.Box>>();
        for (var kind : TrainVehicle.Kind.values()) models.add(VehicleModels.forKind(kind));
        models.add(VehicleModels.buffer());
        for (var model : models) {
            var faces = VehicleModels.visibleFaces(model);
            for (int i = 0; i < faces.size(); i++) {
                var a = faces.get(i);
                for (int j = i + 1; j < faces.size(); j++) {
                    var b = faces.get(j);
                    int axis = a.direction().getAxis().ordinal();
                    if (axis != b.direction().getAxis().ordinal()) continue;
                    float[] first = bounds(a.bounds());
                    float[] second = bounds(b.bounds());
                    if (Math.abs(first[axis] - second[axis]) > 0.00001) continue;
                    int u = (axis + 1) % 3, v = (axis + 2) % 3;
                    assertTrue(overlap(first, second, u) < 0.00002 || overlap(first, second, v) < 0.00002,
                        "Coplanar faces overlap: " + a + ", " + b);
                }
            }
        }
    }

    @Test
    void handbrakeMountsConnectTheWheelToEveryFreightBody() {
        for (var kind : TrainVehicle.Kind.values()) {
            if (kind.powered) continue;
            var parts = VehicleModels.withBrakeMount(kind);
            var shaft = bounds(parts.getLast());
            var mount = bounds(parts.get(parts.size() - 2));
            float x = (float) (kind.brakeWheelOffset() * 16);
            float y = kind.brakeWheelHeight();
            var center = VehicleModels.brakeWheel(kind).stream().map(FreightModelsTest::bounds)
                .filter(wheel -> wheel[0] < 0 && wheel[3] > 0 && wheel[1] < 0 && wheel[4] > 0).findFirst().orElseThrow();
            center[0] += x;
            center[3] += x;
            center[1] += y;
            center[4] += y;
            assertTrue(intersects(center, shaft), kind.id() + " wheel to shaft");
            assertTrue(intersects(shaft, mount), kind.id() + " shaft to mount");
            assertTrue(VehicleModels.bodyBoxes(kind).stream().map(FreightModelsTest::bounds).anyMatch(body -> intersects(body, mount)),
                kind.id() + " mount to body");
        }
    }

    @Test
    void boilerSaddlesReachTheFrameAndBoiler() {
        var boxes = VehicleModels.bodyBoxes(TrainVehicle.Kind.STEAM_LOCOMOTIVE);
        var frame = boxes.stream().filter(box -> box.y() == 4).findFirst().map(FreightModelsTest::bounds).orElseThrow();
        var saddles = boxes.stream().filter(box -> box.y() == 7.8f).toList();
        assertEquals(2, saddles.size());
        for (var saddle : saddles) {
            var support = bounds(saddle);
            assertTrue(intersects(frame, support), "Saddle reaches the frame");
            assertTrue(boxes.stream().filter(box -> box.y() == 9).map(FreightModelsTest::bounds)
                .anyMatch(boiler -> intersects(boiler, support)), "Saddle reaches the boiler");
        }
    }

    @Test
    void vehiclePanelsUseSquareTexelsInWorldAndInventory() {
        var faces = new ArrayList<VehicleModels.Face>();
        for (var kind : TrainVehicle.Kind.values()) faces.addAll(VehicleModels.visibleFaces(VehicleModels.forKind(kind)));
        faces.addAll(VehicleModels.visibleFaces(VehicleModels.doors(TrainVehicle.Kind.BOXCAR, true)));
        faces.addAll(VehicleModels.visibleFaces(VehicleModels.doors(TrainVehicle.Kind.BOXCAR, false)));
        for (var face : faces) {
            if (face.panel() == null) continue;
            var box = face.bounds();
            var pattern = VehiclePaint.Pattern.of(face.source());
            var region = VehiclePaint.REGIONS.get(pattern);
            float u0 = face.panelU(box.x(), box.z()), u1 = face.panelU(box.x() + box.width(), box.z() + box.length());
            float v0 = face.panelV(box.y(), box.z()), v1 = face.panelV(box.y() + box.height(), box.z() + box.length());
            float u = (face.panel().onTop() ? box.width() : box.length()) * VehiclePaint.TEXELS_PER_UNIT;
            float v = (face.panel().onTop() ? box.length() : box.height()) * VehiclePaint.TEXELS_PER_UNIT;
            assertEquals(u, Math.abs(region.u(u1) - region.u(u0)) * VehiclePaint.ATLAS_WIDTH, 0.0001, face.panel().name());
            assertEquals(v, Math.abs(region.v(v1) - region.v(v0)) * VehiclePaint.ATLAS_HEIGHT, 0.0001, face.panel().name());
            assertEquals(u, Math.abs(pattern.itemU(u1) - pattern.itemU(u0)) * pattern.itemWidth() / 16, 0.0001, face.panel().name());
            assertEquals(v, Math.abs(pattern.itemV(v1) - pattern.itemV(v0)) * pattern.itemHeight() / 16, 0.0001, face.panel().name());
        }
    }

    private static float[] bounds(VehicleModels.Box b) {
        return new float[]{b.x(), b.y(), b.z(), b.x() + b.width(), b.y() + b.height(), b.z() + b.length()};
    }

    private static double overlap(float[] a, float[] b, int axis) {
        return Math.min(a[3 + axis], b[3 + axis]) - Math.max(a[axis], b[axis]);
    }

    private static boolean intersects(float[] a, float[] b) {
        return overlap(a, b, 0) > 0 && overlap(a, b, 1) > 0 && overlap(a, b, 2) > 0;
    }

    @Test
    void miniatureBodiesFitTheirVehicleBoundsAndAlignWithTheLocomotiveCoupler() {
        for (var kind : List.of(TrainVehicle.Kind.FLATCAR, TrainVehicle.Kind.GONDOLA, TrainVehicle.Kind.BOXCAR)) {
            var boxes = VehicleModels.forKind(kind);
            double tallest = boxes.stream().mapToDouble(box -> box.y() + box.height()).max().orElseThrow();
            assertEquals(kind.height * 16, tallest, 0.001, kind.id());
            for (var box : boxes) {
                assertTrue(box.x() >= -kind.width * 8 && box.x() + box.width() <= kind.width * 8, kind.id());
            }
            var coupler = boxes.stream().filter(box -> Math.abs(box.z() + kind.length * 8) < 0.001)
                .findFirst().orElseThrow();
            var locomotiveCoupler = VehicleModels.forKind(TrainVehicle.Kind.LOCOMOTIVE).stream()
                .filter(box -> Math.abs(box.z() + TrainVehicle.Kind.LOCOMOTIVE.length * 8) < 0.001)
                .findFirst().orElseThrow();
            assertEquals(locomotiveCoupler.y(), coupler.y(), 0.001, kind.id());
            assertEquals(locomotiveCoupler.height(), coupler.height(), 0.001, kind.id());
        }
    }
}
