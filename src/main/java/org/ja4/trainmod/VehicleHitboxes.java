package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

final class VehicleHitboxes {
    enum Component {
        FRONT_COUPLER, REAR_COUPLER, HANDBRAKE, LEFT_DOOR, RIGHT_DOOR, TENDER_SEAT;

        int couplerEnd() { return this == FRONT_COUPLER ? 0 : this == REAR_COUPLER ? 1 : -1; }
    }

    record Target(Component component, AABB box) {}
    record Hit(@Nullable Component component, Vec3 point) {}
    record Shape(List<AABB> body, AABB envelope, List<Target> controls) {}

    record Frame(Vec3 origin, float yaw, float pitch, double bank) {
        Vec3 local(Vec3 world) {
            return world.subtract(origin).add(0, -Math.abs(bank) * CurveBanking.GAUGE / 2, 0)
                .yRot((float) Math.toRadians(yaw)).xRot((float) Math.toRadians(pitch)).zRot((float) Math.atan(bank));
        }

        Vec3 world(Vec3 local) {
            return local.zRot(-(float) Math.atan(bank)).xRot((float) Math.toRadians(-pitch))
                .yRot((float) Math.toRadians(-yaw)).add(origin).add(0, Math.abs(bank) * CurveBanking.GAUGE / 2, 0);
        }

        Vec3 corner(AABB box, int vertex) {
            return world(new Vec3((vertex & 1) == 0 ? box.minX : box.maxX,
                (vertex & 2) == 0 ? box.minY : box.maxY, (vertex & 4) == 0 ? box.minZ : box.maxZ));
        }

        AABB worldBounds(AABB local) {
            Vec3 point = corner(local, 0);
            AABB result = new AABB(point, point);
            for (int i = 1; i < 8; i++) {
                point = corner(local, i);
                result = result.minmax(new AABB(point, point));
            }
            return result;
        }
    }

    private static final EnumMap<TrainVehicle.Kind, Shape> SHAPES = new EnumMap<>(TrainVehicle.Kind.class);

    static {
        for (var kind : TrainVehicle.Kind.values()) {
            var body = new ArrayList<>(VehicleModels.bodyBoxes(kind).stream().map(VehicleHitboxes::bounds).toList());
            if (kind == TrainVehicle.Kind.STEAM_LOCOMOTIVE) {
                VehicleModels.posedSteamGear(kind).stream().map(VehicleHitboxes::bounds).forEach(body::add);
            }
            var envelope = VehicleModels.forKind(kind).stream().map(VehicleHitboxes::bounds)
                .reduce(AABB::minmax).orElseThrow();
            List<Target> controls = new ArrayList<>();
            controls.add(new Target(Component.FRONT_COUPLER, coupler(kind, 1)));
            controls.add(new Target(Component.REAR_COUPLER, coupler(kind, -1)));
            if (!kind.powered) controls.add(new Target(Component.HANDBRAKE, handbrake(kind)));
            if (kind == TrainVehicle.Kind.TENDER) {
                controls.add(new Target(Component.TENDER_SEAT, new AABB(-0.34, 0.81, -0.74, 0.34, 1.14, 0.26)));
            }
            if (kind == TrainVehicle.Kind.BOXCAR) {
                controls.add(door(kind, true));
                controls.add(door(kind, false));
            }
            SHAPES.put(kind, new Shape(List.copyOf(body), envelope, List.copyOf(controls)));
        }
    }

    private VehicleHitboxes() {}

    static Shape of(TrainVehicle.Kind kind) { return SHAPES.get(kind); }

    private static AABB bounds(VehicleModels.Box box) {
        return new AABB(box.x() / 16, box.y() / 16, box.z() / 16,
            (box.x() + box.width()) / 16, (box.y() + box.height()) / 16, (box.z() + box.length()) / 16);
    }

    private static AABB coupler(TrainVehicle.Kind kind, int sign) {
        double width = VehicleModels.COUPLER_HALF_WIDTH * kind.width / 32;
        double inner = sign * VehicleModels.COUPLER_INNER_END * kind.length / 40;
        double outer = sign * kind.length / 2;
        return new AABB(-width, VehicleModels.COUPLER_BOTTOM / 16, inner,
            width, VehicleModels.COUPLER_TOP / 16, outer).inflate(0.045);
    }

    private static AABB handbrake(TrainVehicle.Kind kind) {
        double x = kind.brakeWheelOffset();
        double y = kind.brakeWheelHeight() / 16.0;
        double z = (VehicleModels.BRAKE_WHEEL_Z + 0.3) * kind.length / 40;
        return AABB.ofSize(new Vec3(x, y, z), 5.4 * kind.width / 32 + 0.17, 3.0 / 16 + 0.18, 0.26);
    }

    private static Target door(TrainVehicle.Kind kind, boolean left) {
        AABB box = VehicleModels.doors(kind, left).stream().map(VehicleHitboxes::bounds)
            .reduce(AABB::minmax).orElseThrow().inflate(0.035);
        return new Target(left ? Component.LEFT_DOOR : Component.RIGHT_DOOR, box);
    }

    static AABB bounds(TrainVehicle car, Target target, float partial) {
        float progress = switch (target.component) {
            case LEFT_DOOR -> car.doorProgress(true, partial);
            case RIGHT_DOOR -> car.doorProgress(false, partial);
            default -> 0;
        };
        return progress == 0 ? target.box
            : target.box.move(0, 0, progress * VehicleModels.OPEN_DOOR_SHIFT * car.kind.length / 40);
    }

    static @Nullable Hit pick(TrainVehicle car, Vec3 from, Vec3 to) {
        return pick(car, from, to, 0);
    }

    static @Nullable Hit pick(TrainVehicle car, Vec3 from, Vec3 to, float partial) {
        var shape = of(car.kind);
        Hit selected = null;
        double nearest = from.distanceToSqr(to);
        for (Target target : shape.controls) {
            Vec3 point = clip(bounds(car, target, partial), from, to);
            if (point != null && from.distanceToSqr(point) < nearest) {
                selected = new Hit(target.component, point);
                nearest = from.distanceToSqr(point);
            }
        }
        for (AABB box : shape.body) {
            Vec3 point = clip(box, from, to);
            if (point != null && from.distanceToSqr(point) < nearest) {
                selected = new Hit(null, point);
                nearest = from.distanceToSqr(point);
            }
        }
        return selected;
    }

    private static @Nullable Vec3 clip(AABB box, Vec3 from, Vec3 to) {
        return box.contains(from) ? from : box.clip(from, to).orElse(null);
    }
}
