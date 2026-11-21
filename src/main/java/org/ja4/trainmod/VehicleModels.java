package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;

/** Cuboids in sixteenths of a block, with the deck centered over the rail. */
final class VehicleModels {
    static final float BRAKE_X = 8;
    static final float BRAKE_WHEEL_Z = -19.75f;
    static final float COUPLER_HALF_WIDTH = 2;
    static final float COUPLER_BOTTOM = 3;
    static final float COUPLER_HEIGHT = 3;
    static final float COUPLER_TOP = COUPLER_BOTTOM + COUPLER_HEIGHT;
    static final float COUPLER_INNER_END = 18;
    static final float OPEN_DOOR_SHIFT = 11.9f;
    static final float DECK_HEIGHT = 7.02f / 16;
    record Box(int color, float x, float y, float z, float width, float height, float length) {}
    record Face(Box bounds, Direction direction) {}

    private record Rectangle(float u0, float v0, float u1, float v1) {
        void subtract(Rectangle cover, List<Rectangle> visible) {
            float left = Math.max(u0, cover.u0), right = Math.min(u1, cover.u1);
            float bottom = Math.max(v0, cover.v0), top = Math.min(v1, cover.v1);
            if (right - left <= EPSILON || top - bottom <= EPSILON) {
                visible.add(this);
                return;
            }
            add(visible, u0, v0, left, v1);
            add(visible, right, v0, u1, v1);
            add(visible, left, v0, right, bottom);
            add(visible, left, top, right, v1);
        }

        private static void add(List<Rectangle> out, float u0, float v0, float u1, float v1) {
            if (u1 - u0 > EPSILON && v1 - v0 > EPSILON) out.add(new Rectangle(u0, v0, u1, v1));
        }
    }

    private record Bounds(float[] min, float[] max) {
        Bounds(Box box) {
            this(new float[]{box.x, box.y, box.z},
                new float[]{box.x + box.width, box.y + box.height, box.z + box.length});
        }

        Rectangle project(int u, int v) {
            return new Rectangle(min[u], min[v], max[u], max[v]);
        }
    }

    private static final float EPSILON = 0.00001f;

    static List<Face> visibleFaces(List<Box> boxes) {
        List<Bounds> volumes = boxes.stream().map(Bounds::new).toList();
        List<Face> faces = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            Bounds box = volumes.get(i);
            for (Direction face : Direction.values()) {
                int axis = face.getAxis().ordinal();
                int u = (axis + 1) % 3, v = (axis + 2) % 3;
                boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                float plane = (positive ? box.max : box.min)[axis];
                List<Rectangle> visible = List.of(box.project(u, v));
                for (int j = 0; j < volumes.size() && !visible.isEmpty(); j++) {
                    Bounds other = volumes.get(j);
                    if (j == i || other.min[axis] > plane + EPSILON || other.max[axis] < plane - EPSILON) continue;
                    float outer = (positive ? other.max : other.min)[axis];
                    float beyond = positive ? outer - plane : plane - outer;
                    // Later details own any shared exterior face. Buried and abutting faces are discarded.
                    if (beyond <= EPSILON && (j < i || beyond < -EPSILON)) continue;
                    List<Rectangle> uncovered = new ArrayList<>();
                    Rectangle cover = other.project(u, v);
                    for (Rectangle fragment : visible) fragment.subtract(cover, uncovered);
                    visible = uncovered;
                }
                for (Rectangle fragment : visible) {
                    float[] origin = new float[3], size = new float[3];
                    origin[axis] = plane;
                    origin[u] = fragment.u0;
                    origin[v] = fragment.v0;
                    size[u] = fragment.u1 - fragment.u0;
                    size[v] = fragment.v1 - fragment.v0;
                    faces.add(new Face(new Box(boxes.get(i).color, origin[0], origin[1], origin[2],
                        size[0], size[1], size[2]), face));
                }
            }
        }
        return faces;
    }

    private static final int STEEL = 0x292c32;
    private static final int WHEEL = 0x444950;
    private static final int FLOOR = 0x70513b;
    private static final int PRR = 0x8e4436;
    private static final int RIB = 0x693127;
    private static final int PRR_LIGHT = 0xb3674c;
    private static final int LETTERS = 0xead5a2;
    private static final int GREEN = 0x344d3b;
    private static final int DARK_GREEN = 0x25382b;
    private static final int GOLD = 0xd4ac4c;
    private static final int WORK = 0xc78627;

    private final List<Box> boxes = new ArrayList<>();

    static List<Box> forKind(TrainVehicle.Kind kind) {
        VehicleModels model = body(kind);
        if (!kind.powered) model.handbrake(kind.brakeWheelHeight());
        return model.scaled(kind);
    }

    static List<Box> bodyBoxes(TrainVehicle.Kind kind) { return body(kind).scaled(kind); }

    static List<Box> brakeCenter(TrainVehicle.Kind kind) {
        var model = new VehicleModels();
        model.box(GOLD, -0.38f, -0.8f, BRAKE_WHEEL_Z, 0.76f, 1.6f, 0.6f);
        return model.scaled(kind);
    }

    private static VehicleModels body(TrainVehicle.Kind kind) {
        VehicleModels model = new VehicleModels();
        model.underframe(kind.freight());
        switch (kind) {
            case LOCOMOTIVE -> model.locomotive();
            case FLATCAR -> model.flatcar();
            case GONDOLA -> model.gondola();
            case BOXCAR -> model.boxcar();
            case TUNNEL_BORE -> model.bore();
            case BALLAST_CAR -> model.ballast();
        }
        return model;
    }

    private List<Box> scaled(TrainVehicle.Kind kind) {
        float widthScale = kind.width / 2;
        float lengthScale = kind.length / 2.5f;
        return boxes.stream().map(box -> new Box(box.color, box.x * widthScale, box.y,
            box.z * lengthScale, box.width * widthScale, box.height, box.length * lengthScale)).toList();
    }

    static List<Box> doors(TrainVehicle.Kind kind, boolean left) {
        var model = new VehicleModels();
        float x = left ? 15.03f : -15.83f;
        model.box(PRR, x, 6.95f, -5.95f, 0.8f, 9.45f, 11.9f);
        for (int z = -5; z <= 5; z += 2) model.box(RIB, x - 0.065f, 7.15f, z, 0.93f, 9.05f, 0.32f);
        model.box(STEEL, x - 0.145f, 10.9f, -4.6f, 1.09f, 1.14f, 0.95f);
        return model.scaled(kind);
    }

    static List<Box> railCargo(TrainVehicle.Kind kind, int layers) {
        var model = new VehicleModels();
        for (int layer = 0; layer < layers; layer++) {
            float y = 7.025f + layer * 1.26f;
            for (int z = -15; z <= 15; z += 3) model.box(FLOOR, -11.9f, y, z, 23.8f, 0.36f, 1.25f);
            for (int x : new int[]{-7, 7}) {
                model.box(STEEL, x - 0.65f, y + 0.36f, -16.1f, 1.3f, 0.22f, 32.2f);
                model.box(WHEEL, x - 0.28f, y + 0.58f, -16.1f, 0.56f, 0.40f, 32.2f);
                model.box(0x9b9d98, x - 0.54f, y + 0.98f, -16.1f, 1.08f, 0.28f, 32.2f);
            }
        }
        float top = layers * 1.26f;
        for (int z : new int[]{-10, 9}) {
            model.box(0x655941, -12.15f, 7.025f + top, z, 24.3f, 0.12f, 1.05f);
            model.box(0x655941, -12.15f, 7.03f, z, 0.26f, top, 1.05f);
            model.box(0x655941, 11.89f, 7.03f, z, 0.26f, top, 1.05f);
        }
        return model.scaled(kind);
    }

    static List<Box> buffer() {
        var model = new VehicleModels();
        for (int side : new int[]{-5, 2}) {
            model.box(FLOOR, side - 0.5f, -0.5f, -6, 4, 2, 12);
            for (int step = 0; step < 3; step++) {
                model.box(WHEEL, side, 1.5f, -5 + step * 2, 3, 0.75f + step, 2);
            }
            model.box(STEEL, side, 1.5f, 1, 3, COUPLER_TOP - 2, 2);
            model.box(STEEL, side, COUPLER_BOTTOM + 0.25f, 6.5f, 3, COUPLER_HEIGHT - 0.5f, 0.75f);
        }
        model.box(0xbe2f2b, -6.25f, COUPLER_BOTTOM, 3, 12.5f, COUPLER_HEIGHT, 3.5f);
        model.box(0xe3dcc5, -2, COUPLER_BOTTOM + 0.5f, 6.5f, 4, COUPLER_HEIGHT - 1, 0.25f);
        return model.boxes;
    }

    private void underframe(boolean lowDeck) {
        box(STEEL, -14, 4, -18, 28, lowDeck ? 1.5f : 4, 36);
        box(STEEL, -COUPLER_HALF_WIDTH, COUPLER_BOTTOM, -20, 2 * COUPLER_HALF_WIDTH,
            COUPLER_HEIGHT, 20 - COUPLER_INNER_END);
        box(STEEL, -COUPLER_HALF_WIDTH, COUPLER_BOTTOM, COUPLER_INNER_END, 2 * COUPLER_HALF_WIDTH,
            COUPLER_HEIGHT, 20 - COUPLER_INNER_END);
        for (int z : new int[]{-12, 7}) {
            box(STEEL, -10, 2, z, 20, lowDeck ? 2 : 4, 5);
        }
        for (int z : new int[]{-13, -7, 5, 11}) {
            box(WHEEL, -12, 0, z, 4, lowDeck ? 4 : 6, 5);
            box(WHEEL, 8, 0, z, 4, lowDeck ? 4 : 6, 5);
        }
    }

    private void locomotive() {
        box(GOLD, -14, 7, -18, 28, 2, 36);
        box(DARK_GREEN, -14, 9, -17, 28, 2, 34);
        box(GREEN, -8, 11, 3, 16, 16, 14);
        box(DARK_GREEN, -7, 27, 3, 14, 2, 13);
        box(GREEN, -8, 11, -17, 16, 13, 7);
        box(DARK_GREEN, -7, 24, -17, 14, 2, 7);
        box(STEEL, -2, 29, 7, 4, 3, 4);
        box(LETTERS, -2, 20, 17, 4, 3, 1);
        box(LETTERS, -2, 19, -18, 4, 3, 1);
        for (int x : new int[]{-13, 11}) {
            for (int z : new int[]{-9, 1}) {
                box(GREEN, x, 11, z, 2, 26, 2);
            }
            box(DARK_GREEN, x, 11, -9, 2, 6, 12);
        }
        box(GREEN, -14, 37, -10, 28, 3, 14);
        box(DARK_GREEN, -12, 40, -9, 24, 1, 12);
        box(STEEL, -3, 11, -5, 6, 4, 6);
        for (int z = 4; z < 15; z += 3) {
            box(DARK_GREEN, -8.4f, 14, z, 0.5f, 9, 1);
            box(DARK_GREEN, 7.9f, 14, z, 0.5f, 9, 1);
        }
    }

    private void flatcar() {
        box(PRR, -15, 4.6f, -18, 30, 1.5f, 36);
        for (int z = -18; z < 18; z += 3) {
            box(z % 2 == 0 ? FLOOR : 0x815d42, -14, 6.1f, z, 28, 0.9f, 2.9f);
        }
        for (int z : new int[]{-14, -4, 5, 14}) {
            box(RIB, -15.25f, 4.4f, z, 0.6f, 1.7f, 2);
            box(RIB, 14.65f, 4.4f, z, 0.6f, 1.7f, 2);
        }
    }

    private void gondola() {
        flatcar();
        box(PRR, -15, 6.2f, -17, 1.25f, 6.3f, 34);
        box(PRR, 13.75f, 6.2f, -17, 1.25f, 6.3f, 34);
        box(PRR, -15, 6.2f, -18, 30, 6.3f, 1);
        box(PRR, -15, 6.2f, 17, 30, 6.3f, 1);
        for (int x : new int[]{-15, 14}) {
            box(PRR_LIGHT, x, 12.5f, -18, 1, 0.5f, 36);
            for (int z = -15; z <= 15; z += 5) {
                box(RIB, x - 0.3f, 6.2f, z, 1.6f, 6.3f, 0.9f);
            }
        }
    }

    private void boxcar() {
        box(FLOOR, -15, 4.6f, -18, 30, 1.5f, 36);
        box(PRR, -15, 6.1f, -18, 30, 11.4f, 1);
        box(PRR, -15, 6.1f, 17, 30, 11.4f, 1);
        for (int x : new int[]{-15, 14}) {
            box(PRR, x, 6.1f, -17, 1, 11.4f, 11);
            box(PRR, x, 6.1f, 6, 1, 11.4f, 11);
            box(RIB, x, 16, -6, 1, 1.5f, 12);
            box(RIB, x, 6.1f, -6, 1, 0.8f, 12);
            float side = x < 0 ? -15.45f : 14.65f;
            for (int z : new int[]{-16, -11, 10, 15}) {
                box(PRR_LIGHT, side, 6.7f, z, 0.8f, 10.4f, 0.75f);
            }
        }
        box(RIB, -15.5f, 17.5f, -18.5f, 31, 0.8f, 37);
        box(PRR_LIGHT, -10, 18.3f, -18, 20, 0.7f, 36);
        lettering(-15.6f, -15.5f);
        lettering(15.25f, -15.5f);
    }

    private void lettering(float x, float z) {
        String[] pixels = {"110110110", "101101101", "110110110", "100101101", "100101101"};
        for (int row = 0; row < pixels.length; row++) {
            for (int column = 0; column < pixels[row].length(); column++) {
                if (pixels[row].charAt(column) == '1') {
                    box(LETTERS, x, 13.9f - row * 0.45f, z + column * 0.8f, 0.25f, 0.43f, 0.75f);
                }
            }
        }
    }

    private void handbrake(float y) {
        box(STEEL, BRAKE_X - 0.8f, 5.25f, -19, 1.6f, y - 5.25f, 1);
        box(GOLD, BRAKE_X - 2.7f, y - 1.5f, BRAKE_WHEEL_Z, 0.7f, 3, 0.6f);
        box(GOLD, BRAKE_X + 2, y - 1.5f, BRAKE_WHEEL_Z, 0.7f, 3, 0.6f);
        box(GOLD, BRAKE_X - 2, y + 0.9f, BRAKE_WHEEL_Z, 4, 0.6f, 0.6f);
        box(GOLD, BRAKE_X - 2, y - 1.5f, BRAKE_WHEEL_Z, 4, 0.6f, 0.6f);
    }

    private void bore() {
        box(WORK, -14, 8, -17, 28, 4, 34);
        box(WORK, -13, 12, -16, 26, 4, 14);
        box(STEEL, -12, 37, -16, 24, 3, 14);
        box(WHEEL, -3, 15, -13, 6, 3, 5);
        box(WHEEL, -10, 12, -2, 20, 11, 17);
        box(STEEL, -12, 11, 12, 24, 27, 5);
        box(WHEEL, -15, 16, 15, 30, 17, 4);
        box(WHEEL, -10, 10, 15, 20, 29, 4);
        box(STEEL, -5, 19, 19, 10, 10, 1.5f);
        for (int x : new int[]{-11, 8}) {
            for (int y : new int[]{15, 30}) {
                box(LETTERS, x, y, 19, 3, 3, 1.7f);
            }
        }
        for (int x : new int[]{-13, 11}) {
            box(WORK, x, 16, -16, 2, 21, 2);
            box(WORK, x, 16, -4, 2, 21, 2);
            box(WORK, x, 16, -15, 2, 5, 12);
            box(LETTERS, x, 26, 2, 2, 2, 3);
        }
    }

    private void ballast() {
        box(WORK, -14, 8, -16, 28, 4, 32);
        box(STEEL, -11, 12, -12, 22, 6, 24);
        box(WORK, -14, 18, -14, 28, 8, 28);
        box(WHEEL, -11, 26, -11, 22, 2, 22);
        box(WORK, -12, 4, 15, 24, 6, 4);
    }

    private void box(int color, float x, float y, float z, float width, float height, float length) {
        boxes.add(new Box(color, x, y, z, width, height, length));
    }
}
