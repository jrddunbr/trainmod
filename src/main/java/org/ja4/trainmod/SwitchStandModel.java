package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.List;

final class SwitchStandModel {
    static final int METAL = 0x343840;
    static final int WHITE = 0xe3dcc5;
    static final int RED = 0xbe2f2b;

    private SwitchStandModel() {}

    static List<VehicleModels.Box> base() {
        return List.of(new VehicleModels.Box(METAL, -5, 0, -5, 10, 2, 10),
            new VehicleModels.Box(METAL, -2, 2, -2, 4, 3, 4),
            new VehicleModels.Box(METAL, -0.75f, 5, -0.75f, 1.5f, 8, 1.5f),
            new VehicleModels.Box(METAL, -2.5f, 6.5f, -0.5f, 5, 1, 1),
            new VehicleModels.Box(RED, -3, 6, -1, 1.25f, 2, 2));
    }

    // Coordinates are in sixteenths relative to the vertical spindle.
    static List<VehicleModels.Box> head() {
        List<VehicleModels.Box> parts = new ArrayList<>();
        parts.add(new VehicleModels.Box(METAL, -1, 13, -1, 2, 6, 2));
        parts.add(new VehicleModels.Box(WHITE, -5.5f, 13.75f, -0.45f, 11, 4.5f, 0.9f));
        parts.add(new VehicleModels.Box(RED, -0.46f, 14.7f, -1.04f, 0.92f, 2.6f, 7.89f));
        for (int step = 0; step < 4; step++) {
            float half = 3.15f - step * 0.75f;
            parts.add(new VehicleModels.Box(RED, -0.46f, 16 - half, -2.37f - step * 1.35f,
                0.92f, 2 * half, 1.35f));
        }
        return parts;
    }
}
