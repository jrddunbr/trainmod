package org.ja4.trainmod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

@EventBusSubscriber(modid = Trainmod.ID, value = Dist.CLIENT)
public final class TrainClient {
    public static EntityHitResult pickVehicles(Entity viewer, Vec3 from, Vec3 to, AABB search,
                                               Predicate<Entity> filter, double rangeSqr, float partial) {
        EntityHitResult result = ProjectileUtil.getEntityHitResult(viewer, from, to, search,
            entity -> !(entity instanceof TrainVehicle) && filter.test(entity), rangeSqr);
        double nearest = result == null ? rangeSqr : result.getLocation().distanceToSqr(from);
        for (var car : viewer.level().getEntitiesOfClass(TrainVehicle.class, search, filter::test)) {
            if (car == viewer || car.getRootVehicle() == viewer.getRootVehicle() && !car.canRiderInteract()) continue;
            var frame = car.renderFrame(partial);
            var hit = VehicleHitboxes.pick(car, frame.local(from), frame.local(to), partial);
            if (hit == null) continue;
            Vec3 point = frame.world(hit.point());
            double distance = point.distanceToSqr(from);
            if (distance < nearest) {
                nearest = distance;
                result = new EntityHitResult(car, point);
            }
        }
        return result;
    }

    public static boolean showVehicleHitboxes(Entity entity, float partial) {
        if (!(entity instanceof TrainVehicle car)) return false;
        var frame = car.renderFrame(partial);
        var shape = VehicleHitboxes.of(car.kind);
        outline(frame, shape.envelope(), 0xffdddddd, false);
        for (var target : shape.controls()) {
            outline(frame, VehicleHitboxes.bounds(car, target, partial), controlColor(target.component()), true);
        }
        return true;
    }

    static int controlColor(VehicleHitboxes.Component component) {
        return switch (component) {
            case FRONT_COUPLER, REAR_COUPLER -> 0xff56d5ff;
            case HANDBRAKE -> 0xffffb942;
            case LEFT_DOOR, RIGHT_DOOR -> 0xff68e580;
        };
    }

    private static void outline(VehicleHitboxes.Frame frame, AABB box, int color, boolean highlight) {
        for (int vertex = 0; vertex < 8; vertex++) {
            Vec3 corner = frame.corner(box, vertex);
            for (int axis = 1; axis <= 4; axis <<= 1) {
                if ((vertex & axis) != 0) continue;
                var line = Gizmos.line(corner, frame.corner(box, vertex | axis), color, highlight ? 2 : 1);
                if (highlight) line.setAlwaysOnTop();
            }
        }
    }

    public static void refreshBallast(LevelExtractor extractor, BlockPos pos, BlockState oldState, BlockState newState) {
        boolean ballast = oldState.is(Trainmod.BALLAST.get()) || newState.is(Trainmod.BALLAST.get());
        if (!ballast && BallastSurface.covered(oldState) == BallastSurface.covered(newState)) return;
        // A ballast gap can straddle a section boundary even when neither block is on the boundary.
        int radius = BallastProfile.SEARCH_DISTANCE;
        extractor.setSectionRangeDirty(SectionPos.blockToSectionCoord(pos.getX() - radius),
            SectionPos.blockToSectionCoord(pos.getY() - 2), SectionPos.blockToSectionCoord(pos.getZ() - radius),
            SectionPos.blockToSectionCoord(pos.getX() + radius), SectionPos.blockToSectionCoord(pos.getY() + 1),
            SectionPos.blockToSectionCoord(pos.getZ() + radius));
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        Trainmod.VEHICLES.forEach((kind, type) ->
            event.registerEntityRenderer(type.get(), context -> new VehicleRenderer<>(context, VehicleModels.forKind(kind), kind)));
        event.registerEntityRenderer(Trainmod.MOUNTED_BUFFER.get(),
            context -> new VehicleRenderer<>(context, VehicleModels.buffer(), null));
        event.registerBlockEntityRenderer(Trainmod.SWITCH_POINTS.get(), context -> new SwitchPointRenderer());
        event.registerBlockEntityRenderer(Trainmod.SWITCH_STAND.get(), context -> new SwitchStandRenderer());
    }

    @SubscribeEvent
    static void restoreSwitchRenderers(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        var chunk = event.getChunk();
        if (!chunk.getLevel().isClientSide()) return;
        // Old worlds contain the same switch blocks without a renderer block entity.
        var sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            var section = sections[index];
            if (!section.maybeHas(TrainClient::hasSwitchRenderer)) continue;
            int baseY = chunk.getSectionYFromSectionIndex(index) * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (hasSwitchRenderer(section.getBlockState(x, y, z))) {
                            chunk.getBlockEntity(new net.minecraft.core.BlockPos(chunk.getPos().getMinBlockX() + x, baseY + y,
                                chunk.getPos().getMinBlockZ() + z), net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.IMMEDIATE);
                        }
                    }
                }
            }
        }
    }

    private static boolean hasSwitchRenderer(net.minecraft.world.level.block.state.BlockState state) {
        return state.getBlock() instanceof RailwayBlocks.Stand
            || state.getBlock() instanceof TrackBlock.Turnout turnout && turnout.panel(state) != null;
    }

    @SubscribeEvent
    static void registerBallastModels(net.neoforged.neoforge.client.event.RegisterBlockStateModels event) {
        AdjacentBallastModel.register(event);
        BankedCurveModel.register(event);
        RailEndModel.register(event);
        TieModel.register(event);
    }

    @SubscribeEvent
    static void reloadTieConfig(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != ClientConfig.SPEC) return;
        Minecraft client = Minecraft.getInstance();
        client.execute(client.levelExtractor::allChanged);
    }

    private static int lastVehicle = -1;
    private static byte lastKeys;

    @SubscribeEvent
    static void sendControls(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !(client.player.getVehicle() instanceof TrainVehicle vehicle) || vehicle.getControllingPassenger() != client.player) {
            lastVehicle = -1;
            return;
        }
        byte keys = 0;
        if (client.gui.screen() == null) {
            if (client.options.keyUp.isDown()) keys |= TrainControls.FORWARD;
            if (client.options.keyDown.isDown()) keys |= TrainControls.REVERSE;
            if (client.options.keyJump.isDown()) keys |= TrainControls.WORK;
        }
        if (vehicle.getId() != lastVehicle || keys != lastKeys || client.player.tickCount % 4 == 0) {
            ClientPacketDistributor.sendToServer(new TrainControls(vehicle.getId(), keys));
            lastVehicle = vehicle.getId();
            lastKeys = keys;
        }
    }

    static final class VehicleState extends EntityRenderState {
        float yaw;
        float pitch;
        float bank;
        int railLayers;
        float gravelFill;
        float leftDoorProgress;
        float rightDoorProgress;
        float handbrakeAngle;
        final Vec3[] couplerTips = new Vec3[2];
        final Vec3[] couplerVectors = new Vec3[2];
    }

    static Map<Integer, CubeListBuilder> coloredCubes(List<VehicleModels.Box> boxes) {
        Map<Integer, CubeListBuilder> meshes = new LinkedHashMap<>();
        for (var face : VehicleModels.visibleFaces(boxes)) {
            var box = face.bounds();
            meshes.computeIfAbsent(box.color(), color -> CubeListBuilder.create()).texOffs(0, 0)
                .addBox(box.x(), box.y(), box.z(), box.width(), box.height(), box.length(), java.util.Set.of(face.direction()));
        }
        return meshes;
    }

    static Map<Integer, ModelPart> bakeColoredCubes(List<VehicleModels.Box> boxes) {
        var colors = coloredCubes(boxes);
        var mesh = new MeshDefinition();
        colors.forEach((color, cubes) -> mesh.getRoot().addOrReplaceChild(color.toString(), cubes, PartPose.ZERO));
        var baked = LayerDefinition.create(mesh, 128, 128).bakeRoot();
        Map<Integer, ModelPart> parts = new LinkedHashMap<>();
        colors.keySet().forEach(color -> parts.put(color, baked.getChild(color.toString())));
        return parts;
    }

    private static final class VehicleRenderer<T extends Entity> extends EntityRenderer<T, VehicleState> {
        private static final RenderType MATERIAL = RenderTypes.entityCutout(
            Identifier.fromNamespaceAndPath(Trainmod.ID, "textures/entity/paint.png"));
        private static final RenderType GRAVEL = RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/block/gravel.png"));
        private final Map<Integer, ModelPart> groups = new LinkedHashMap<>();
        private final List<Map<Integer, ModelPart>> railLoads;
        private final Map<Integer, ModelPart> leftDoor;
        private final Map<Integer, ModelPart> rightDoor;
        private final Map<Integer, ModelPart> brakeCenter;
        private final double brakeX;
        private final float brakeY;
        private final float doorTravel;
        private final ModelPart gravel;
        private final ModelPart drawbar;

        VehicleRenderer(EntityRendererProvider.Context context, List<VehicleModels.Box> boxes, TrainVehicle.Kind kind) {
            super(context);
            shadowRadius = 0.9f;
            var meshes = coloredCubes(boxes);
            MeshDefinition model = new MeshDefinition();
            meshes.forEach((color, cubes) -> model.getRoot().addOrReplaceChild(color.toString(), cubes, PartPose.ZERO));
            model.getRoot().addOrReplaceChild("drawbar", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-1, -0.58f, -0.04f, 2, 1.16f, 16.08f), PartPose.ZERO);
            ModelPart root = LayerDefinition.create(model, 128, 128).bakeRoot();
            drawbar = root.getChild("drawbar");
            meshes.keySet().forEach(color -> groups.put(color, root.getChild(color.toString())));
            railLoads = kind != null && kind.showsLoad()
                ? java.util.stream.IntStream.rangeClosed(1, 4).mapToObj(layers -> bakeColoredCubes(VehicleModels.railCargo(kind, layers))).toList()
                : List.of();
            leftDoor = kind == TrainVehicle.Kind.BOXCAR ? bakeColoredCubes(VehicleModels.doors(kind, true)) : Map.of();
            rightDoor = kind == TrainVehicle.Kind.BOXCAR ? bakeColoredCubes(VehicleModels.doors(kind, false)) : Map.of();
            brakeCenter = kind != null && !kind.powered ? bakeColoredCubes(VehicleModels.brakeCenter(kind)) : Map.of();
            brakeX = kind != null ? kind.brakeWheelOffset() : 0;
            brakeY = kind != null ? kind.brakeWheelHeight() / 16 : 0;
            doorTravel = kind == TrainVehicle.Kind.BOXCAR ? VehicleModels.OPEN_DOOR_SHIFT * kind.length / 40 : 0;
            gravel = kind == TrainVehicle.Kind.GONDOLA ? gravel(kind) : null;
        }

        private static ModelPart gravel(TrainVehicle.Kind kind) {
            float halfWidth = kind.width * 6.925f;
            float halfLength = kind.length * 6.82f;
            var mesh = new MeshDefinition();
            mesh.getRoot().addOrReplaceChild("gravel", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-halfWidth, 0, -halfLength, 2 * halfWidth, 5.38f, 2 * halfLength), PartPose.ZERO);
            return LayerDefinition.create(mesh, 16, 16).bakeRoot().getChild("gravel");
        }

        @Override
        public VehicleState createRenderState() { return new VehicleState(); }

        @Override
        public void extractRenderState(T entity, VehicleState state, float partial) {
            super.extractRenderState(entity, state, partial);
            state.yaw = Mth.rotLerp(partial, entity.yRotO, entity.getYRot());
            state.pitch = Mth.lerp(partial, entity.xRotO, entity.getXRot());
            if (entity instanceof TrainVehicle train) {
                state.bank = train.renderBank(partial);
                state.railLayers = train.railLayers();
                state.gravelFill = train.gravelFill();
                state.leftDoorProgress = train.doorProgress(true, partial);
                state.rightDoorProgress = train.doorProgress(false, partial);
                state.handbrakeAngle = train.handbrakeAngle(partial);
            }
            for (int end = 0; end < 2; end++) {
                state.couplerVectors[end] = null;
                if (!(entity instanceof TrainVehicle train)
                    || !(entity.level().getEntity(train.coupledId(end)) instanceof TrainVehicle other)) continue;
                int otherEnd = other.coupledId(0) == train.getId() ? 0 : 1;
                if (other.coupledId(otherEnd) != train.getId()) continue;
                Vec3 tip = couplerTip(train, end, partial);
                Vec3 span = couplerTip(other, otherEnd, partial).subtract(tip);
                if (span.lengthSqr() > 0.0001 && span.lengthSqr() < 4) {
                    state.couplerTips[end] = tip.subtract(state.x, state.y, state.z);
                    state.couplerVectors[end] = span.scale(0.5);
                }
            }
        }

        private static Vec3 couplerTip(TrainVehicle vehicle, int end, float partial) {
            float yaw = Mth.rotLerp(partial, vehicle.yRotO, vehicle.getYRot());
            float pitch = Mth.lerp(partial, vehicle.xRotO, vehicle.getXRot());
            return vehicle.getPosition(partial).add(vehicle.couplerOffset(end, yaw, pitch, vehicle.renderBank(partial)));
        }

        @Override
        protected AABB getBoundingBoxForCulling(T entity, float partial) {
            return super.getBoundingBoxForCulling(entity, partial).inflate(0.5);
        }

        @Override
        public void submit(VehicleState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
            pose.pushPose();
            pose.translate(0, Math.abs(state.bank) * CurveBanking.GAUGE / 2, 0);
            pose.rotateDegrees(Axis.YP, -state.yaw);
            pose.rotateDegrees(Axis.XP, state.pitch);
            pose.rotateDegrees(Axis.ZP, (float) Math.toDegrees(Math.atan(state.bank)));
            submitParts(groups, state, pose, collector);
            if (state.railLayers > 0 && !railLoads.isEmpty()) {
                submitParts(railLoads.get(state.railLayers - 1), state, pose, collector);
            }
            if (gravel != null && state.gravelFill > 0) {
                pose.pushPose();
                pose.translate(0, VehicleModels.DECK_HEIGHT, 0);
                pose.scale(1, state.gravelFill, 1);
                collector.submitModelPart(gravel, pose, GRAVEL, state.lightCoords, OverlayTexture.NO_OVERLAY,
                    null, 0xffffffff, state.outlineColor);
                pose.popPose();
            }
            submitDoor(leftDoor, state.leftDoorProgress, state, pose, collector);
            submitDoor(rightDoor, state.rightDoorProgress, state, pose, collector);
            if (!brakeCenter.isEmpty()) {
                pose.pushPose();
                pose.translate(brakeX, brakeY, 0);
                pose.rotateDegrees(Axis.ZP, state.handbrakeAngle);
                submitParts(brakeCenter, state, pose, collector);
                pose.popPose();
            }
            pose.popPose();
            for (int end = 0; end < 2; end++) {
                Vec3 span = state.couplerVectors[end];
                if (span == null) continue;
                Vec3 tip = state.couplerTips[end];
                pose.pushPose();
                pose.translate(tip.x, tip.y, tip.z);
                pose.rotateDegrees(Axis.YP, (float) Math.toDegrees(Math.atan2(span.x, span.z)));
                pose.rotateDegrees(Axis.XP, (float) -Math.toDegrees(Math.atan2(span.y, span.horizontalDistance())));
                pose.scale(1, 1, (float) span.length());
                collector.submitModelPart(drawbar, pose, MATERIAL, state.lightCoords, OverlayTexture.NO_OVERLAY,
                    null, 0xff292c32, state.outlineColor);
                pose.popPose();
            }
            super.submit(state, pose, collector, camera);
        }

        private void submitDoor(Map<Integer, ModelPart> parts, float progress, VehicleState state, PoseStack pose,
                                SubmitNodeCollector collector) {
            if (parts.isEmpty()) return;
            pose.pushPose();
            pose.translate(0, 0, progress * doorTravel);
            submitParts(parts, state, pose, collector);
            pose.popPose();
        }

        private static void submitParts(Map<Integer, ModelPart> parts, VehicleState state, PoseStack pose,
                                        SubmitNodeCollector collector) {
            parts.forEach((color, model) -> collector.submitModelPart(model, pose, MATERIAL, state.lightCoords,
                OverlayTexture.NO_OVERLAY, null, 0xff000000 | color, state.outlineColor));
        }
    }
}
