package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.List;

/** Shared geometry for the decorations and the way they are stowed on cars. */
final class FestiveModels {
    static final int MAX_VISIBLE_PRESENTS = 3;
    static final int TREE_HEIGHT = 3;
    static final float DECK = 16 * VehicleModels.DECK_HEIGHT;
    private static final float PRESENT_SCALE = 0.72f;
    private static final float PRESENT_STEP = 11.6f;
    private static final int TRUNK = 0x714730;
    private static final int FIR_DARK = 0x184635;
    private static final int FIR = 0x236643;
    private static final int FIR_LIGHT = 0x308453;
    private static final int GOLD = 0xf4bc45;
    private static final int GOLD_LIGHT = 0xffdf78;
    private static final int STRAP = 0x34302c;
    private static final int BUCKLE = 0xb3aba1;
    static final int CRADLE = 0x704831;

    private FestiveModels() {}

    static List<VehicleModels.Box> present() { return present(0); }

    static List<VehicleModels.Box> sleigh() {
        int red = 0xaa263b, lacquer = 0xd63649, iron = 0x534a42;
        var boxes = new ArrayList<VehicleModels.Box>();
        for (int side : new int[]{-1, 1}) {
            float runner = side * 4.9f;
            box(boxes, iron, runner - 0.55f, 0, -5.2f, runner + 0.55f, 0.68f, 7.6f);
            box(boxes, GOLD, runner - 0.56f, 0.66f, -5.2f, runner + 0.56f, 1.04f, 7.6f);
            box(boxes, GOLD, runner - 0.56f, 0.68f, -6.4f, runner + 0.56f, 1.58f, -5.2f);
            box(boxes, GOLD, runner - 0.56f, 1.42f, -7.25f, runner + 0.56f, 2.48f, -6.35f);
            box(boxes, GOLD_LIGHT, runner - 0.57f, 2.3f, -7.72f, runner + 0.57f, 3.6f, -6.96f);
            box(boxes, GOLD, runner - 0.56f, 3.36f, -7.65f, runner + 0.56f, 3.95f, -6.62f);
            for (float z : new float[]{-3.5f, 4.15f}) {
                box(boxes, iron, runner - 0.41f, 0.95f, z, runner + 0.41f, 3.18f, z + 0.74f);
            }
            float edge = side * 6;
            sidepanel(boxes, red, lacquer, edge, -5.45f, -3.4f, 6.9f);
            sidepanel(boxes, red, lacquer, edge, -3.4f, -0.1f, 6.1f);
            sidepanel(boxes, red, lacquer, edge, -0.1f, 2.2f, 7.45f);
            sidepanel(boxes, red, lacquer, edge, 2.2f, 4.55f, 8.85f);
            sidepanel(boxes, red, lacquer, edge, 4.55f, 6.3f, 10.7f);
        }
        box(boxes, red, -6.28f, 3, -5.45f, 6.28f, 4.15f, 6.15f);
        box(boxes, CRADLE, -5.4f, 4.1f, -4.9f, 5.4f, 4.53f, 5.7f);
        box(boxes, red, -6.28f, 4.1f, -5.45f, 6.28f, 6.95f, -4.72f);
        box(boxes, GOLD, -6.33f, 6.95f, -5.55f, 6.33f, 7.3f, -4.62f);
        box(boxes, red, -6.25f, 4.1f, 5.48f, 6.25f, 11.7f, 6.32f);
        box(boxes, lacquer, -5.25f, 11.7f, 5.47f, 5.25f, 12.38f, 6.34f);
        box(boxes, GOLD, -4.5f, 12.38f, 5.36f, 4.5f, 12.8f, 6.44f);
        box(boxes, FIR_DARK, -5.32f, 4.5f, 2.75f, 5.32f, 6.1f, 5.5f);
        box(boxes, FIR_LIGHT, -5.34f, 6.1f, 2.62f, 5.34f, 6.82f, 5.51f);
        box(boxes, FIR, -5.25f, 6.65f, 4.83f, 5.25f, 10.72f, 5.51f);
        box(boxes, GOLD_LIGHT, -0.42f, 8.6f, 6.32f, 0.42f, 9.49f, 6.53f);
        return List.copyOf(boxes);
    }

    private static void sidepanel(List<VehicleModels.Box> boxes, int paint, int trim, float x, float front, float back, float top) {
        box(boxes, paint, x - 0.34f, 4, front, x + 0.34f, top, back);
        box(boxes, trim, x - 0.46f, 4.17f, front, x + 0.46f, 4.64f, back);
        box(boxes, GOLD, x - 0.42f, top, front, x + 0.42f, top + 0.36f, back);
    }

    static List<VehicleModels.Box> wreath() {
        var boxes = new ArrayList<VehicleModels.Box>();
        int[] shades = {FIR_DARK, FIR, FIR_LIGHT, 0x286e48};
        for (int i = 0; i < 20; i++) {
            double angle = i * Math.PI / 10;
            float x = 5.06f * (float) Math.cos(angle), y = 8.65f + 5.06f * (float) Math.sin(angle);
            float radius = i % 2 == 0 ? 1.32f : 1.08f;
            float front = -1.05f - 0.24f * (i % 3);
            box(boxes, shades[i % shades.length], x - radius, y - radius, front, x + radius, y + radius, 1);
            if (i % 4 == 1) {
                box(boxes, i % 8 == 1 ? 0xb7253b : GOLD, x - 0.42f, y - 0.38f, front - 0.38f,
                    x + 0.42f, y + 0.38f, front + 0.12f);
            }
        }
        box(boxes, 0xb9253b, -0.7f, 2.58f, -1.86f, 0.7f, 4.15f, -1.12f);
        for (int side : new int[]{-1, 1}) {
            float x = side * 1.45f;
            box(boxes, 0xd82e49, x - 0.91f, 2.98f, -1.71f, x + 0.91f, 4.4f, -1.2f);
            box(boxes, 0xbc2c40, x - 0.45f, 1.08f, -1.7f, x + 0.45f, 3.15f, -1.17f);
        }
        return List.copyOf(boxes);
    }

    static List<VehicleModels.Box> wallWreath() {
        return wreath().stream().map(box -> scale(box, 1, 0, 0, 7)).toList();
    }

    static List<VehicleModels.Box> wreathCrate() {
        var boxes = new ArrayList<VehicleModels.Box>();
        box(boxes, CRADLE, -7.15f, 0, -7.15f, 7.15f, 1.3f, 7.15f);
        for (int side : new int[]{-1, 1}) {
            float edge = side * 6.77f;
            for (int course = 0; course < 3; course++) {
                float y = 1.3f + 2.35f * course;
                int wood = course % 2 == 0 ? 0x9e754c : 0x8d633e;
                box(boxes, wood, edge - 0.46f, y, -7.18f, edge + 0.46f, y + 1.82f, 7.18f);
                box(boxes, wood, -7.18f, y, edge - 0.46f, 7.18f, y + 1.82f, edge + 0.46f);
            }
            for (int end : new int[]{-1, 1}) {
                float z = end * 6.77f;
                box(boxes, 0xc29a64, edge - 0.66f, 0, z - 0.66f, edge + 0.66f, 8.35f, z + 0.66f);
            }
        }
        // Wreaths lie face up in the slatted crate.
        for (var item : wreath()) {
            float factor = 0.81f;
            boxes.add(new VehicleModels.Box(item.color(), item.x() * factor, 6.95f - (item.z() + item.length()) * factor,
                (item.y() - 8.65f) * factor, item.width() * factor, item.length() * factor, item.height() * factor));
        }
        box(boxes, FIR_DARK, -4.92f, 1.3f, -4.92f, 4.92f, 6.13f, 4.92f);
        return List.copyOf(boxes);
    }

    private static List<VehicleModels.Box> present(int style) {
        int paper = switch (style) {
            case 1 -> 0x267459;
            case 2 -> 0x355d9a;
            default -> 0xb62836;
        };
        int lid = switch (style) {
            case 1 -> 0x358c69;
            case 2 -> 0x4678b9;
            default -> 0xce3946;
        };
        int ribbon = style == 1 ? 0xe9d9bc : GOLD;
        int bow = style == 1 ? 0xfff2d4 : GOLD_LIGHT;
        var boxes = new ArrayList<VehicleModels.Box>();
        box(boxes, paper, -7.45f, 0, -7.45f, 7.45f, 12, 7.45f);
        box(boxes, lid, -7.7f, 12, -7.7f, 7.7f, 14, 7.7f);
        for (int side : new int[]{-1, 1}) {
            float edge = side * 7.58f;
            box(boxes, ribbon, -1.3f, 0, edge - 0.24f, 1.3f, 14, edge + 0.24f);
            box(boxes, ribbon, edge - 0.24f, 0, -1.3f, edge + 0.24f, 14, 1.3f);
            float loop = side * 2.35f;
            box(boxes, bow, loop - 1.55f, 14.25f, -0.9f, loop + 1.55f, 15.1f, 0.9f);
            box(boxes, ribbon, loop - 1.45f, 15.1f, -0.6f, loop + 1.45f, 15.7f, 0.6f);
        }
        box(boxes, ribbon, -1.3f, 14, -7.82f, 1.3f, 14.31f, 7.82f);
        box(boxes, ribbon, -7.82f, 14, -1.3f, 7.82f, 14.31f, 1.3f);
        box(boxes, bow, -0.85f, 14.3f, -0.85f, 0.85f, 15.5f, 0.85f);
        return List.copyOf(boxes);
    }

    static List<VehicleModels.Box> standingTree() {
        var boxes = new ArrayList<VehicleModels.Box>();
        box(boxes, TRUNK, -1.5f, 0, -1.5f, 1.5f, 42, 1.5f);
        bough(boxes, 4, 7.8f);
        bough(boxes, 14, 6.5f);
        bough(boxes, 24, 5.15f);
        bough(boxes, 33, 3.65f);
        box(boxes, FIR, -1.1f, 41, -1.1f, 1.1f, 44.5f, 1.1f);
        box(boxes, GOLD, -0.57f, 43, -0.57f, 0.57f, 48, 0.57f);
        box(boxes, GOLD_LIGHT, -2.42f, 44.8f, -0.48f, 2.42f, 46.16f, 0.48f);
        box(boxes, GOLD_LIGHT, -0.48f, 44.8f, -2.42f, 0.48f, 46.16f, 2.42f);
        return List.copyOf(boxes);
    }

    private static void bough(List<VehicleModels.Box> boxes, float y, float reach) {
        square(boxes, FIR_DARK, reach, y, y + 1.8f);
        square(boxes, FIR, reach - 0.7f, y + 1.8f, y + 4.7f);
        square(boxes, FIR_LIGHT, reach - 1.75f, y + 4.7f, y + 7.5f);
        square(boxes, FIR, Math.max(1, reach - 2.65f), y + 7.5f, y + 10.2f);
        float edge = reach - 0.75f;
        ornament(boxes, 0xdf4853, -reach * 0.38f, y + 1.6f, -edge);
        ornament(boxes, GOLD, edge, y + 1.9f, -reach * 0.27f);
        ornament(boxes, 0x75cbe4, reach * 0.35f, y + 1.5f, edge);
        ornament(boxes, 0xdf4853, -edge, y + 1.8f, reach * 0.31f);
    }

    private static void ornament(List<VehicleModels.Box> boxes, int color, float x, float y, float z) {
        box(boxes, color, x - 0.57f, y - 0.63f, z - 0.57f, x + 0.57f, y + 0.63f, z + 0.57f);
    }

    private static void square(List<VehicleModels.Box> boxes, int color, float radius, float bottom, float top) {
        box(boxes, color, -radius, bottom, -radius, radius, top, radius);
    }

    static List<VehicleModels.Box> treeSection(int part) {
        var section = new ArrayList<VehicleModels.Box>();
        float base = 16 * part;
        for (var box : standingTree()) {
            float bottom = Math.max(box.y(), base);
            float top = Math.min(box.y() + box.height(), base + 16);
            if (bottom < top) {
                section.add(new VehicleModels.Box(box.color(), box.x(), bottom - base, box.z(), box.width(), top - bottom, box.length()));
            }
        }
        return List.copyOf(section);
    }

    static List<VehicleModels.Box> loadedPresents(int count, boolean strapped) {
        var boxes = new ArrayList<VehicleModels.Box>();
        for (int index = 0; index < count; index++) {
            float z = (index - (count - 1) / 2f) * PRESENT_STEP;
            addPresent(boxes, index, strapped, z, 1);
        }
        return List.copyOf(boxes);
    }

    static void addPresent(List<VehicleModels.Box> boxes, int index, boolean strapped, float z, float scale) {
        float size = PRESENT_SCALE * scale;
        for (var box : present(index)) {
            boxes.add(scale(box, size, 0, DECK, z));
        }
        if (strapped) strap(boxes, 7.7f * size, DECK, DECK + 14 * size, z + 3.25f * scale, 0.85f * scale);
    }

    static List<VehicleModels.Box> loadedTree(boolean abovePresents) {
        var boxes = new ArrayList<VehicleModels.Box>();
        float base = DECK + (abovePresents ? 14.5f : 0);
        if (abovePresents) {
            float front = 17.55f, rear = -front;
            for (float end : new float[]{rear, front}) {
                box(boxes, CRADLE, -5.82f, DECK, end - 0.32f, 5.82f, DECK + 0.7f, end + 0.32f);
            }
            for (int side : new int[]{-1, 1}) {
                float edge = side * 5.52f;
                for (float end : new float[]{rear, front}) {
                    box(boxes, CRADLE, edge - 0.3f, DECK, end - 0.32f, edge + 0.3f, base, end + 0.32f);
                }
                box(boxes, CRADLE, edge - 0.3f, base - 0.82f, rear - 0.32f, edge + 0.3f, base, front + 0.32f);
            }
        }
        for (float z : new float[]{-12, 8}) {
            box(boxes, CRADLE, -5.82f, base, z - 1.15f, 5.82f, base + 1.5f, z + 1.15f);
        }
        box(boxes, CRADLE, -3.16f, base + 1.5f, 7, 3.16f, base + 2.9f, 9);
        float axis = base + 6.05f;
        box(boxes, TRUNK, -1.18f, axis - 1.18f, -17, 1.18f, axis + 1.18f, -11);
        nettedBough(boxes, axis, -14, -6, 4.8f, FIR_DARK);
        nettedBough(boxes, axis, -6, 2, 4.05f, FIR);
        nettedBough(boxes, axis, 2, 10, 3.15f, FIR_DARK);
        nettedBough(boxes, axis, 10, 16.5f, 2.2f, FIR);
        box(boxes, FIR_LIGHT, -1.1f, axis - 1.1f, 16.5f, 1.1f, axis + 1.1f, 18);
        strap(boxes, 5.14f, base + 1.5f, axis + 5.14f, -12, 0.72f);
        strap(boxes, 3.49f, base + 1.5f, axis + 3.49f, 8, 0.72f);
        return List.copyOf(boxes);
    }

    private static void nettedBough(List<VehicleModels.Box> boxes, float y, float start, float end, float radius, int foliage) {
        box(boxes, foliage, -radius, y - radius, start, radius, y + radius, end);
        int steps = 16;
        float step = (end - start) / steps;
        float thread = 0.33f;
        for (int row = 0; row < steps; row++) {
            float z = start + row * step;
            float next = z + step;
            for (int direction : new int[]{-1, 1}) {
                int net = direction == 1 ? 0x64b641 : 0x468f35;
                float a = direction * radius * (2f * row / steps - 1);
                float b = direction * radius * (2f * (row + 1) / steps - 1);
                float low = Math.max(-radius, Math.min(a, b) - thread / 2);
                float high = Math.min(radius, Math.max(a, b) + thread / 2);
                box(boxes, net, low, y + radius, z, high, y + radius + thread, next);
                box(boxes, net, radius, y + low, z, radius + thread, y + high, next);
                box(boxes, net, -radius - thread, y + low, z, -radius, y + high, next);
            }
        }
    }

    static void strap(List<VehicleModels.Box> boxes, float side, float bottom, float top, float z, float width) {
        float outer = side + 0.32f;
        box(boxes, STRAP, -outer, top, z - width / 2, outer, top + 0.24f, z + width / 2);
        for (int direction : new int[]{-1, 1}) {
            float x = direction * (side + 0.12f);
            box(boxes, STRAP, x - 0.2f, bottom, z - width / 2, x + 0.2f, top, z + width / 2);
            box(boxes, BUCKLE, x - 0.66f, bottom, z - width, x + 0.66f, bottom + 0.45f, z + width);
        }
        box(boxes, BUCKLE, side * 0.53f, top + 0.24f, z - width * 0.66f,
            side * 0.53f + 1.1f, top + 0.48f, z + width * 0.66f);
    }

    static VehicleModels.Box scale(VehicleModels.Box box, float scale, float x, float y, float z) {
        return new VehicleModels.Box(box.color(), x + box.x() * scale, y + box.y() * scale, z + box.z() * scale,
            box.width() * scale, box.height() * scale, box.length() * scale);
    }

    static void box(List<VehicleModels.Box> boxes, int color, float x0, float y0, float z0, float x1, float y1, float z1) {
        boxes.add(new VehicleModels.Box(color, x0, y0, z0, x1 - x0, y1 - y0, z1 - z0));
    }
}
