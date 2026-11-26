package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Packed panel textures use the same texel density on both axes. */
final class VehiclePaint {
    static final int ATLAS_WIDTH = 256;
    static final int ATLAS_HEIGHT = 256;
    static final int TEXELS_PER_UNIT = 2;
    static final int PADDING = 2;
    static final Map<Pattern, Region> REGIONS = pack();

    private VehiclePaint() {}

    record Pattern(VehicleModels.Panel panel, float width, float height) {
        static Pattern of(VehicleModels.Box source) {
            var panel = source.panel();
            return panel.onTop() ? new Pattern(panel, source.width(), source.length())
                : new Pattern(panel, source.length(), source.height());
        }

        int pixelWidth() { return (int) Math.ceil(width * TEXELS_PER_UNIT); }
        int pixelHeight() { return (int) Math.ceil(height * TEXELS_PER_UNIT); }

        int itemWidth() { return spriteSize(pixelWidth()); }
        int itemHeight() { return spriteSize(pixelHeight()); }

        private static int spriteSize(int pixels) {
            return Math.max(16, Integer.highestOneBit(pixels + 2 * PADDING - 1) << 1);
        }

        float itemU(float modelU) { return 16 * (PADDING + modelU * TEXELS_PER_UNIT) / itemWidth(); }
        float itemV(float modelV) { return 16 * (PADDING + modelV * TEXELS_PER_UNIT) / itemHeight(); }
    }

    record Region(int x, int y) {
        float u(float modelU) { return (x + modelU * TEXELS_PER_UNIT) / ATLAS_WIDTH; }
        float v(float modelV) { return (y + modelV * TEXELS_PER_UNIT) / ATLAS_HEIGHT; }
    }

    private static Map<Pattern, Region> pack() {
        Set<Pattern> patterns = new LinkedHashSet<>();
        for (var kind : TrainVehicle.Kind.values()) {
            for (var box : VehicleModels.bodyBoxes(kind)) {
                if (box.panel() != null) patterns.add(Pattern.of(box));
            }
        }
        for (var box : VehicleModels.doors(TrainVehicle.Kind.BOXCAR, true)) {
            if (box.panel() != null) patterns.add(Pattern.of(box));
        }
        var sorted = new ArrayList<>(patterns);
        sorted.sort(Comparator.comparingInt(Pattern::pixelHeight).thenComparingInt(Pattern::pixelWidth).reversed());
        Map<Pattern, Region> regions = new LinkedHashMap<>();
        // The first 128 columns are reserved for the ordinary cuboid UVs.
        int x = 128, y = 0, rowHeight = 0;
        for (var pattern : sorted) {
            int width = pattern.pixelWidth() + 2 * PADDING, height = pattern.pixelHeight() + 2 * PADDING;
            if (x + width > ATLAS_WIDTH) {
                x = 128;
                y += rowHeight;
                rowHeight = 0;
            }
            if (x + width > ATLAS_WIDTH || y + height > ATLAS_HEIGHT) {
                throw new IllegalStateException("Vehicle paint atlas is too small for " + pattern);
            }
            regions.put(pattern, new Region(x + PADDING, y + PADDING));
            x += width;
            rowHeight = Math.max(rowHeight, height);
        }
        return Collections.unmodifiableMap(regions);
    }
}
