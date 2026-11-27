package org.ja4.trainmod;

import static org.ja4.trainmod.FestiveModels.CRADLE;
import static org.ja4.trainmod.FestiveModels.DECK;
import static org.ja4.trainmod.FestiveModels.box;
import static org.ja4.trainmod.FestiveModels.strap;

import java.util.ArrayList;
import java.util.List;

final class FreightModels {
    private static final float DECK_LENGTH = 34.5f;
    private static final float CRATE_STEP = 11.5f;
    private static final float SLEIGH_SPACE = 23;
    private static final float WREATH_SPACE = 11.4f;
    private static final float PRESENT_SPACE = 11.6f;
    private static final float SECTION_SCALE = 0.48f;
    private static final float SECTION_OFFSET = 8.75f;
    static final float CRATE_SIDE = 5.22f;
    static final float CRATE_ITEM_HEIGHT = DECK + 7.1f;

    private FreightModels() {}

    static List<VehicleModels.Box> cargo(FreightLoad load, boolean flatcar) {
        var holiday = holiday(load, flatcar);
        var freight = !load.track().isEmpty() ? track(load.track())
            : load.timber().isEmpty() ? crates(load.crateCount(), flatcar) : logs(load.timber());
        if (holiday.isEmpty()) return freight;
        if (freight.isEmpty()) return holiday;
        var combined = new ArrayList<VehicleModels.Box>();
        addSection(combined, freight, -SECTION_OFFSET);
        addSection(combined, holiday, SECTION_OFFSET);
        return combined;
    }

    private static void addSection(List<VehicleModels.Box> result, List<VehicleModels.Box> boxes, float center) {
        for (var box : boxes) {
            result.add(new VehicleModels.Box(box.color(), box.x(), box.y(), box.z() * SECTION_SCALE + center,
                box.width(), box.height(), box.length() * SECTION_SCALE));
        }
    }

    private static List<VehicleModels.Box> holiday(FreightLoad load, boolean flatcar) {
        var boxes = new ArrayList<VehicleModels.Box>();
        if (!load.sleigh() && load.wreathCrates() == 0) {
            boxes.addAll(FestiveModels.loadedPresents(load.presents(), flatcar));
        } else {
            float length = load.presents() * PRESENT_SPACE + load.wreathCrates() * WREATH_SPACE
                + (load.sleigh() ? SLEIGH_SPACE : 0);
            float scale = Math.min(1, DECK_LENGTH / length);
            float edge = -length * scale / 2;
            if (load.sleigh()) {
                float center = edge + SLEIGH_SPACE * scale / 2;
                addSleigh(boxes, center, scale, flatcar);
                edge += SLEIGH_SPACE * scale;
            }
            for (int n = 0; n < load.presents(); n++) {
                float center = edge + PRESENT_SPACE * scale / 2;
                FestiveModels.addPresent(boxes, n, flatcar, center, scale);
                edge += PRESENT_SPACE * scale;
            }
            var wreath = FestiveModels.wreathCrate();
            for (int n = 0; n < load.wreathCrates(); n++) {
                float center = edge + WREATH_SPACE * scale / 2;
                for (var part : wreath) boxes.add(FestiveModels.scale(part, 0.74f * scale, 0, DECK, center));
                if (flatcar) strap(boxes, 5.5f * scale, DECK, DECK + 6.4f * scale, center + 3.1f * scale, 0.7f * scale);
                edge += WREATH_SPACE * scale;
            }
        }
        if (load.tree()) boxes.addAll(FestiveModels.loadedTree(!boxes.isEmpty()));
        return boxes;
    }

    private static void addSleigh(List<VehicleModels.Box> boxes, float center, float scale, boolean flatcar) {
        float width = 0.88f * scale, length = 1.44f * scale;
        for (var part : FestiveModels.sleigh()) {
            boxes.add(new VehicleModels.Box(part.color(), part.x() * width, DECK + part.y() * width,
                center + part.z() * length, part.width() * width, part.height() * width, part.length() * length));
        }
        if (flatcar) {
            strap(boxes, 6.46f * width, DECK, DECK + 6.46f * width, center - 2 * length, 0.68f * scale);
        }
    }

    static float crateItemZ(FreightLoad load, int crate, int icon, int side) {
        float center = crateCenter(crate, load.crateCount());
        float z = center - side * (icon - (load.crateItems().size() - 1) / 2f) * 2.85f;
        return load.hasHoliday() ? z * SECTION_SCALE - SECTION_OFFSET : z;
    }

    static float crateItemWidth(FreightLoad load) {
        return load.hasHoliday() ? SECTION_SCALE : 1;
    }

    private static float crateCenter(int index, int count) {
        return (index - (count - 1) / 2f) * CRATE_STEP;
    }

    private static List<VehicleModels.Box> crates(int count, boolean flatcar) {
        var boxes = new ArrayList<VehicleModels.Box>();
        for (int n = 0; n < count; n++) {
            float z = crateCenter(n, count);
            addCrate(boxes, z);
            if (flatcar) strap(boxes, 5.44f, DECK, DECK + 9.25f, z + 4.28f, 0.7f);
        }
        return boxes;
    }

    private static void addCrate(List<VehicleModels.Box> boxes, float z) {
        box(boxes, 0x715034, -5, DECK, z - 5, 5, DECK + 8.65f, z + 5);
        for (int board = 0; board < 4; board++) {
            float y = DECK + 0.45f + board * 2.05f;
            int timber = board % 2 == 0 ? 0xa4774a : 0x95663e;
            box(boxes, timber, -CRATE_SIDE, y, z - CRATE_SIDE, CRATE_SIDE, y + 1.86f, z + CRATE_SIDE);
            float offset = -5.18f + board * 2.61f;
            box(boxes, timber, -5.2f, DECK + 8.65f, z + offset, 5.2f, DECK + 9.01f, z + offset + 2.49f);
        }
        for (int side : new int[]{-1, 1}) {
            float x = side * 4.82f;
            box(boxes, 0xc69b62, x - 0.58f, DECK + 8.97f, z - 5.2f, x + 0.58f, DECK + 9.25f, z + 5.2f);
            for (int end : new int[]{-1, 1}) {
                float corner = z + end * 4.77f;
                box(boxes, 0xc69b62, x - 0.58f, DECK, corner - 0.61f, x + 0.58f, DECK + 9.25f, corner + 0.61f);
            }
        }
    }

    private static List<VehicleModels.Box> track(FreightLoad.Track track) {
        var boxes = new ArrayList<VehicleModels.Box>();
        float half = DECK_LENGTH / 2;
        if (track.rails() == 0) {
            if (track.ties() == 1) {
                trackBundle(boxes, 1, -7.2f, 7.2f, false);
            } else {
                int first = Math.ceilDiv(track.ties(), 2);
                trackBundle(boxes, first, -half, -2, false);
                trackBundle(boxes, track.ties() - first, 2, half, false);
            }
        } else if (track.ties() == 0) {
            trackBundle(boxes, track.rails(), -half, half, true);
        } else {
            float share = track.rails() / (float) (track.rails() + track.ties());
            float split = -half + DECK_LENGTH * Math.clamp(share, 0.38f, 0.62f);
            trackBundle(boxes, track.rails(), -half, split - 0.55f, true);
            trackBundle(boxes, track.ties(), split + 0.55f, half, false);
        }
        return boxes;
    }

    private static void trackBundle(List<VehicleModels.Box> boxes, int count, float start, float end, boolean rail) {
        if (!rail) {
            float center = (start + end) / 2;
            start = Math.max(start, center - 7.2f);
            end = Math.min(end, center + 7.2f);
        }
        float width = rail ? 1.78f : 2.12f;
        float step = rail ? 1.8f : 1.69f;
        float deck = DECK + 1.1f;
        float stackEdge = width * Math.min(4, count) / 2;
        float top = deck + step * Math.ceilDiv(count, 4);
        for (float z : new float[]{start + 2.4f, end - 2.4f}) {
            box(boxes, CRADLE, -stackEdge - 0.9f, DECK, z - 0.72f, stackEdge + 0.9f, deck, z + 0.72f);
            strap(boxes, stackEdge + 0.1f, deck, top, z, 0.72f);
        }
        for (int n = 0; n < count; n++) {
            int row = n / 4;
            float x = width * (n % 4 - (Math.min(4, count - row * 4) - 1) / 2f);
            float y = deck + row * step;
            if (rail) {
                box(boxes, 0x686866, x - 0.7f, y, start, x + 0.7f, y + 0.36f, end);
                box(boxes, 0x848383, x - 0.28f, y + 0.36f, start, x + 0.28f, y + 1.37f, end);
                box(boxes, 0xb8bfba, x - 0.6f, y + 1.37f, start, x + 0.6f, y + step, end);
            } else {
                float tieStart = start + (n % 3) * 0.3f;
                float tieEnd = end - (n % 2) * 0.45f;
                box(boxes, n % 2 == 0 ? 0x62412d : 0x805439, x - 1, y, tieStart, x + 1, y + step, tieEnd);
                box(boxes, 0x956b48, x - 0.76f, y + 0.26f, tieStart - 0.05f, x + 0.76f, y + 1.41f, tieStart);
                box(boxes, 0x956b48, x - 0.76f, y + 0.26f, tieEnd, x + 0.76f, y + 1.41f, tieEnd + 0.05f);
            }
        }
    }

    private static List<VehicleModels.Box> logs(List<FreightLoad.Logs> bundles) {
        var boxes = new ArrayList<VehicleModels.Box>();
        float bottom = DECK + 0.78f;
        for (int z : new int[]{-9, 9}) {
            box(boxes, CRADLE, -5.78f, DECK, z - 1.1f, 5.78f, bottom, z + 1.1f);
        }
        int count = bundles.stream().mapToInt(FreightLoad.Logs::count).sum();
        int index = 0;
        for (var bundle : bundles) {
            for (int piece = 0; piece < bundle.count(); piece++, index++) {
                int row = index / 3;
                int inRow = Math.min(3, count - row * 3);
                float x = (index % 3 - (inRow - 1) / 2f) * 3.47f;
                float y = bottom + 1.68f + row * 3.31f;
                float end = 15.5f - 0.63f * (index % 3);
                addLog(boxes, bundle.bark(), bundle.endGrain(), x, y, end, index);
            }
        }
        float top = bottom + 3.36f + ((count - 1) / 3) * 3.31f;
        float side = 1.7f + (Math.min(3, count) - 1) * 1.735f;
        for (int z : new int[]{-9, 9}) strap(boxes, side, bottom, top, z, 0.78f);
        return boxes;
    }

    private static void addLog(List<VehicleModels.Box> boxes, int bark, int grain, float x, float y, float end, int index) {
        box(boxes, bark, x - 1.67f, y - 0.98f, -end, x + 1.67f, y + 0.98f, end);
        box(boxes, bark, x - 0.99f, y - 1.68f, -end, x + 0.99f, y + 1.68f, end);
        int barkMarks = dim(bark, 0.7f);
        float mark = index % 2 == 0 ? -0.45f : 0.38f;
        for (int z : new int[]{-11, -4, 3, 10}) {
            box(boxes, barkMarks, x + mark - 0.11f, y + 1.675f, z, x + mark + 0.11f, y + 1.71f, z + 2.7f);
        }
        int innerRing = dim(grain, 0.82f);
        for (int side : new int[]{-1, 1}) {
            float z = side * end;
            box(boxes, grain, x - 1.27f, y - 0.8f, z - 0.06f, x + 1.27f, y + 0.8f, z + 0.06f);
            box(boxes, grain, x - 0.8f, y - 1.27f, z - 0.06f, x + 0.8f, y + 1.27f, z + 0.06f);
            box(boxes, innerRing, x - 0.56f, y - 0.62f, z - 0.08f, x + 0.56f, y + 0.62f, z + 0.08f);
            box(boxes, grain, x - 0.27f, y - 0.29f, z - 0.1f, x + 0.27f, y + 0.29f, z + 0.1f);
        }
    }

    private static int dim(int rgb, float scale) {
        return (int) ((rgb >> 16 & 255) * scale) << 16
            | (int) ((rgb >> 8 & 255) * scale) << 8
            | (int) ((rgb & 255) * scale);
    }
}
