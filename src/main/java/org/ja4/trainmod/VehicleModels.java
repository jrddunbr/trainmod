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
    record Wheel(float y, float z, float radius, List<Box> boxes) {}

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
        model.underframe(kind.freight() || kind == TrainVehicle.Kind.BALLAST_CAR,
            kind != TrainVehicle.Kind.STEAM_LOCOMOTIVE && kind != TrainVehicle.Kind.TUNNEL_BORE);
        switch (kind) {
            case LOCOMOTIVE -> model.locomotive();
            case FLATCAR -> model.flatcar();
            case GONDOLA -> model.gondola(PRR, RIB, PRR_LIGHT);
            case BOXCAR -> model.boxcar();
            case TUNNEL_BORE -> model.bore();
            case BALLAST_CAR -> model.gondola(0xd6ac38, 0xa27b26, 0xe5be4f);
            case STEAM_LOCOMOTIVE -> model.steam();
            case TENDER -> model.tender();
            case ELECTRIC_SHUNTER -> model.shunter();
        }
        return model;
    }

    static double rearAxle(TrainVehicle.Kind kind) {
        double axle = switch (kind) {
            case STEAM_LOCOMOTIVE -> -13;
            case TUNNEL_BORE -> -15;
            default -> -10.5;
        };
        return axle * kind.length / 40;
    }

    static double frontAxle(TrainVehicle.Kind kind) {
        return (kind == TrainVehicle.Kind.STEAM_LOCOMOTIVE ? 14 : 13.5) * kind.length / 40;
    }

    static double boreInnerRearAxle() {
        return -10 * TrainVehicle.Kind.TUNNEL_BORE.length / 40;
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

    private void underframe(boolean lowDeck, boolean standardWheels) {
        box(STEEL, -14, 4, -18, 28, lowDeck ? 1.5f : 4, 36);
        box(STEEL, -COUPLER_HALF_WIDTH, COUPLER_BOTTOM, -20, 2 * COUPLER_HALF_WIDTH,
            COUPLER_HEIGHT, 20 - COUPLER_INNER_END);
        box(STEEL, -COUPLER_HALF_WIDTH, COUPLER_BOTTOM, COUPLER_INNER_END, 2 * COUPLER_HALF_WIDTH,
            COUPLER_HEIGHT, 20 - COUPLER_INNER_END);
        if (!standardWheels) return;
        for (int z : new int[]{-12, 7}) {
            box(STEEL, -10, 2, z, 20, lowDeck ? 2 : 4, 5);
        }
        for (int z : new int[]{-13, -7, 5, 11}) {
            box(WHEEL, -12, 0, z, 4, lowDeck ? 4 : 6, 5);
            box(WHEEL, 8, 0, z, 4, lowDeck ? 4 : 6, 5);
        }
    }

    private void locomotive() {
        box(GOLD, -15, 7, -18, 30, 1.6f, 36);
        box(DARK_GREEN, -14, 8.6f, -17.5f, 28, 1, 35);
        box(GREEN, -10, 9.6f, 4, 20, 8.4f, 13);
        box(DARK_GREEN, -9, 18, 4, 18, 0.7f, 12.5f);
        box(GREEN, -10, 9.6f, -17, 20, 7.1f, 12);
        box(DARK_GREEN, -9, 16.7f, -17, 18, 0.7f, 12);
        box(STEEL, -2, 18.7f, 7, 4, 0.7f, 2);
        box(STEEL, -4.7f, 9.6f, -3.5f, 9.4f, 1.6f, 5.2f);
        box(STEEL, -4.7f, 11.2f, -3.5f, 9.4f, 1.4f, 1);
        for (int side : new int[]{-1, 1}) {
            float x = side < 0 ? -14 : 12.6f;
            box(GREEN, x, 9.6f, -5.3f, 1.4f, 4, 9.3f);
            for (int end : new int[]{-17, 16}) {
                box(GOLD, x, 9.6f, end, 1, 4.4f, 1);
            }
            for (int z = 5; z < 15; z += 2) {
                box(DARK_GREEN, side < 0 ? -10.35f : 10, 12, z, 0.35f, 4.4f, 0.55f);
            }
        }
        box(STEEL, -3, 14.1f, 16.9f, 6, 3.2f, 0.7f);
        box(STEEL, -3, 13.5f, -17.6f, 6, 3, 0.7f);
    }

    static List<Box> headlight(TrainVehicle.Kind kind, int end, boolean lit) {
        var model = new VehicleModels();
        model.box(lit ? 0xffe7a0 : 0x817e60, -2.3f, end > 0 ? 14.5f : 13.9f,
            end > 0 ? 17.6f : -17.75f, 4.6f, 2.25f, 0.15f);
        return model.scaled(kind);
    }

    private void steam() {
        int boiler = 0x313439;
        int smokeBox = 0x41474a;
        box(0x552c2b, -15, 7, -16, 30, 1.25f, 33);
        box(boiler, -11.5f, 9, -10, 23, 8, 22.4f);
        box(boiler, -9, 17, -10, 18, 3, 22.4f);
        box(boiler, -6, 20, -10, 12, 0.6f, 22.4f);
        box(smokeBox, -11.5f, 9, 12.4f, 23, 8, 2);
        box(smokeBox, -9, 17, 12.4f, 18, 3, 2);
        box(STEEL, -5.3f, 18.7f, 8.2f, 10.6f, 4, 3.4f);
        box(WHEEL, -6.6f, 22, 7.8f, 13.2f, 0.8f, 4.2f);
        box(boiler, -5, 20.4f, -2, 10, 1.85f, 4);
        box(GOLD, -2.2f, 20.4f, -8, 4.4f, 1.35f, 1.4f);
        box(LETTERS, -2.9f, 15.25f, 14.4f, 5.8f, 2.2f, 0.85f);
        box(0x552c2b, -14, 8, -17, 28, 5, 8);
        box(boiler, -15, 20.7f, -17.4f, 30, 1.25f, 9.1f);
        for (int side : new int[]{-1, 1}) {
            float x = side < 0 ? -14 : 11;
            box(boiler, x, 13, -16.7f, 3, 7.7f, 1.2f);
            box(boiler, x, 13, -10, 3, 7.7f, 1.3f);
            box(boiler, x, 19.2f, -15.5f, 3, 1.5f, 5.6f);
            box(smokeBox, x, 3, 6, 4, 4.5f, 4.8f);
        }
        for (int i = 0; i < 5; i++) box(WHEEL, -13 + i * 5.4f, 3, 16, 4.4f, 4.25f, 2.3f);
    }

    static List<Wheel> steamWheels(TrainVehicle.Kind kind) {
        List<Wheel> wheels = new ArrayList<>();
        for (float z : new float[]{-13, -5, 1, 9, 14}) {
            float radius = z == -5 || z == 1 ? (float) (16 * TrainVehicle.DRIVER_WHEEL_RADIUS) : 2;
            var model = new VehicleModels();
            for (int side : new int[]{-1, 1}) {
                float x = side < 0 ? -12.6f : 8.4f;
                model.box(WHEEL, x, -radius, -radius * 0.61f, 4.2f, 2 * radius, 1.22f * radius);
                model.box(WHEEL, x, -radius * 0.61f, -radius, 4.2f, 1.22f * radius, 2 * radius);
                float face = side < 0 ? x - 0.3f : x + 4.2f;
                model.box(0x787674, face, -0.33f, -radius * 0.9f, 0.3f, 0.66f, 1.8f * radius);
                model.box(0x787674, face, -radius * 0.9f, -0.33f, 0.3f, 1.8f * radius, 0.66f);
            }
            // Wheel diameters are in world units so they remain circular when the chassis length changes.
            var boxes = model.boxes.stream().map(box -> new Box(box.color(), box.x() * kind.width / 2, box.y(),
                box.z(), box.width() * kind.width / 2, box.height(), box.length())).toList();
            wheels.add(new Wheel(radius, z * kind.length / 2.5f, radius, boxes));
        }
        return List.copyOf(wheels);
    }

    static List<Box> steamRods(TrainVehicle.Kind kind) {
        var model = new VehicleModels();
        float length = 6 * kind.length / 2.5f;
        for (int side : new int[]{-1, 1}) {
            float x = side * kind.width * 6.6f;
            model.box(0xa9a7a1, x - 0.27f, -0.37f, -0.47f, 0.54f, 0.74f, length + 0.94f);
        }
        return model.boxes;
    }

    static List<Box> posedSteamGear(TrainVehicle.Kind kind) {
        var boxes = new ArrayList<Box>();
        for (var wheel : steamWheels(kind)) {
            for (var box : wheel.boxes()) boxes.add(offset(box, wheel.y(), wheel.z()));
        }
        var driver = steamWheels(kind).get(1);
        for (var box : steamRods(kind)) boxes.add(offset(box, driver.y(), driver.z() + 1.15f));
        return boxes;
    }

    private static Box offset(Box box, float y, float z) {
        return new Box(box.color(), box.x(), box.y() + y, box.z() + z, box.width(), box.height(), box.length());
    }

    private void tender() {
        box(0x393738, -15.1f, 6.8f, -17.7f, 30.2f, 7.7f, 35.4f);
        box(0x492e2d, -15.4f, 7, -16.9f, 30.8f, 3.25f, 33.8f);
        box(0x252428, -13, 14.5f, 2, 26, 1.7f, 14.4f);
        for (int z = 3; z < 15; z += 3) box(0x333237, -11 + (z % 2), 16.2f, z, 22, 0.45f, 2.1f);
        box(STEEL, -5.6f, 14.5f, -8, 11.2f, 1.85f, 6);
        box(0x552c2b, -5.6f, 16.35f, -8, 11.2f, 0.45f, 6);
        box(WHEEL, 8, 14.5f, -14.4f, 4.5f, 0.85f, 4.5f);
    }

    private void shunter() {
        box(GOLD, -15.5f, 7.7f, -17.7f, 31, 1.25f, 35.4f);
        box(0x435f73, -12, 9, -17, 24, 6.4f, 10);
        box(0x435f73, -12, 9, 5, 24, 7.5f, 12);
        box(0x2c4450, -10, 16.4f, 7, 20, 1.1f, 9);
        box(STEEL, -4, 9, -3, 8, 1.8f, 5);
        box(WHEEL, -7, 17.5f, 7.5f, 14, 0.7f, 6);
        box(STEEL, -6, 18.2f, 8.5f, 1.2f, 2.1f, 4);
        box(STEEL, 4.8f, 18.2f, 8.5f, 1.2f, 2.1f, 4);
        box(WHEEL, -6, 20.3f, 9.4f, 12, 0.5f, 1.15f);
    }

    private void flatcar() {
        flatDeck(PRR, RIB);
    }

    private void flatDeck(int paint, int ribs) {
        box(paint, -15, 4.6f, -18, 30, 1.5f, 36);
        for (int z = -18; z < 18; z += 3) {
            box(z % 2 == 0 ? FLOOR : 0x815d42, -14, 6.1f, z, 28, 0.9f, 2.9f);
        }
        for (int z : new int[]{-14, -4, 5, 14}) {
            box(ribs, -15.25f, 4.4f, z, 0.6f, 1.7f, 2);
            box(ribs, 14.65f, 4.4f, z, 0.6f, 1.7f, 2);
        }
    }

    private void gondola(int paint, int ribs, int trim) {
        flatDeck(paint, ribs);
        box(paint, -15, 6.2f, -17, 1.25f, 6.3f, 34);
        box(paint, 13.75f, 6.2f, -17, 1.25f, 6.3f, 34);
        box(paint, -15, 6.2f, -18, 30, 6.3f, 1);
        box(paint, -15, 6.2f, 17, 30, 6.3f, 1);
        for (int x : new int[]{-15, 14}) {
            box(trim, x, 12.5f, -18, 1, 0.5f, 36);
            for (int z = -15; z <= 15; z += 5) {
                box(ribs, x - 0.3f, 6.2f, z, 1.6f, 6.3f, 0.9f);
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
    }

    private void handbrake(float y) {
        box(STEEL, BRAKE_X - 0.8f, 5.25f, -19, 1.6f, y - 5.25f, 1);
        box(GOLD, BRAKE_X - 2.7f, y - 1.5f, BRAKE_WHEEL_Z, 0.7f, 3, 0.6f);
        box(GOLD, BRAKE_X + 2, y - 1.5f, BRAKE_WHEEL_Z, 0.7f, 3, 0.6f);
        box(GOLD, BRAKE_X - 2, y + 0.9f, BRAKE_WHEEL_Z, 4, 0.6f, 0.6f);
        box(GOLD, BRAKE_X - 2, y - 1.5f, BRAKE_WHEEL_Z, 4, 0.6f, 0.6f);
    }

    private void bore() {
        for (float axle : new float[]{-15, -10, 7.5f, 13.5f}) {
            box(STEEL, -10, 2, axle - 1.6f, 20, 4, 3.2f);
            box(WHEEL, -12, 0, axle - 1.9f, 4, 5, 3.8f);
            box(WHEEL, 8, 0, axle - 1.9f, 4, 5, 3.8f);
        }
        for (int side : new int[]{-1, 1}) {
            box(STEEL, side * 12.8f - 1.25f, 6, 12, 2.5f, 10, 3);
        }
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

    static List<Box> boreRoadGear() {
        var model = new VehicleModels();
        for (int side : new int[]{-1, 1}) {
            float hub = side * 12.8f;
            float bottom = (float) (BoreDrive.RETRACTED_TIRE_CLEARANCE * 16);
            model.box(WHEEL, hub - 2.6f, bottom, 11.5f, 5.2f, 9.6f, 4);
            model.box(WHEEL, hub - 2.5f, bottom + 2.0f, 10.65f, 5, 5.6f, 5.7f);
            model.box(STEEL, hub + side * 2.4f - 0.7f, bottom + 2.4f, 12.3f, 1.4f, 4.8f, 2.4f);
            model.box(0xa9a7a1, hub - 0.65f, bottom + 6, 12.68f, 1.3f, 16, 1.64f);
        }
        return model.scaled(TrainVehicle.Kind.TUNNEL_BORE);
    }

    private void box(int color, float x, float y, float z, float width, float height, float length) {
        boxes.add(new Box(color, x, y, z, width, height, length));
    }
}
