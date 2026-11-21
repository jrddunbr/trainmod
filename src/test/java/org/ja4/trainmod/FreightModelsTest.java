package org.ja4.trainmod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class FreightModelsTest {
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
    void turningBrakeCenterStaysInsideTheWheel() {
        for (var kind : TrainVehicle.Kind.values()) {
            if (kind.powered) continue;
            var center = VehicleModels.brakeCenter(kind).getFirst();
            double radius = Math.hypot(center.width() / 2, center.height() / 2);
            assertTrue(radius < Math.min(0.9, kind.width), kind.id());
        }
    }

    private static float[] bounds(VehicleModels.Box b) {
        return new float[]{b.x(), b.y(), b.z(), b.x() + b.width(), b.y() + b.height(), b.z() + b.length()};
    }

    private static double overlap(float[] a, float[] b, int axis) {
        return Math.min(a[3 + axis], b[3 + axis]) - Math.max(a[axis], b[axis]);
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
