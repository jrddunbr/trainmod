package org.ja4.trainmod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
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
        boolean holdingCoupler = viewer instanceof net.minecraft.world.entity.player.Player player
            && (player.getMainHandItem().is(Trainmod.COUPLER.get()) || player.getOffhandItem().is(Trainmod.COUPLER.get()));
        for (var car : viewer.level().getEntitiesOfClass(TrainVehicle.class, search, filter::test)) {
            if (car == viewer || car.getRootVehicle() == viewer.getRootVehicle() && !car.canRiderInteract()) continue;
            var frame = car.renderFrame(partial);
            Vec3 localFrom = frame.local(from);
            Vec3 localTo = frame.local(to);
            var hit = VehicleHitboxes.pick(car, localFrom, localTo, partial);
            Vec3 local = hit != null ? hit.point() : holdingCoupler
                ? VehicleHitboxes.of(car.kind).envelope().clip(localFrom, localTo).orElse(null) : null;
            if (local == null) continue;
            Vec3 point = frame.world(local);
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
            case LEFT_DOOR, RIGHT_DOOR, TENDER_SEAT, TREECUTTERS_LANTERN, CRAFTING_TABLE -> 0xff68e580;
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
        if (oldState.getBlock() instanceof BridgeBlock || newState.getBlock() instanceof BridgeBlock) {
            extractor.setSectionRangeDirty(SectionPos.blockToSectionCoord(pos.getX() - 16),
                SectionPos.blockToSectionCoord(pos.getY() - 4), SectionPos.blockToSectionCoord(pos.getZ() - 16),
                SectionPos.blockToSectionCoord(pos.getX() + 16), SectionPos.blockToSectionCoord(pos.getY() + 1),
                SectionPos.blockToSectionCoord(pos.getZ() + 16));
        }
        boolean ballast = oldState.is(Trainmod.BALLAST.get()) || newState.is(Trainmod.BALLAST.get());
        if (!ballast && BallastSurface.covered(oldState) == BallastSurface.covered(newState)
            && TrackBlock.broken(oldState) == TrackBlock.broken(newState)) return;
        // A ballast gap can straddle a section boundary even when neither block is on the boundary.
        int radius = BallastProfile.SEARCH_DISTANCE;
        extractor.setSectionRangeDirty(SectionPos.blockToSectionCoord(pos.getX() - radius),
            SectionPos.blockToSectionCoord(pos.getY() - 2), SectionPos.blockToSectionCoord(pos.getZ() - radius),
            SectionPos.blockToSectionCoord(pos.getX() + radius), SectionPos.blockToSectionCoord(pos.getY() + 1),
            SectionPos.blockToSectionCoord(pos.getZ() + radius));
    }

    @SubscribeEvent
    static void registerScreens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
        event.register(Trainmod.RAIL_EXTRUDER_MENU.get(), RailExtruderScreen::new);
        event.register(Trainmod.FUEL_GENERATOR_MENU.get(), FuelGeneratorScreen::new);
        event.register(Trainmod.BALLAST_REGULATOR_MENU.get(), BallastRegulatorScreen::new);
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        Trainmod.VEHICLES.forEach((kind, type) ->
            event.registerEntityRenderer(type.get(), context -> new VehicleRenderer<>(context, VehicleModels.withBrakeMount(kind), kind)));
        event.registerEntityRenderer(Trainmod.MOUNTED_BUFFER.get(),
            context -> new VehicleRenderer<>(context, VehicleModels.buffer(), null));
        event.registerEntityRenderer(Trainmod.TREECUTTERS_WORKER.get(), net.minecraft.client.renderer.entity.VillagerRenderer::new);
        event.registerBlockEntityRenderer(Trainmod.SWITCH_POINTS.get(), context -> new SwitchPointRenderer());
        event.registerBlockEntityRenderer(Trainmod.SWITCH_STAND.get(), context -> new SwitchStandRenderer());
        event.registerBlockEntityRenderer(Trainmod.CATENARY_ANCHOR.get(), context -> new CatenaryRenderer());
        event.registerBlockEntityRenderer(Trainmod.TRANSFORMER_BANK_ENTITY.get(), context -> new RailPowerRenderer.BankRenderer());
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
        BentTrackModel.register(event);
        TieModel.register(event);
        TrackRenderModel.register(event);
        BridgeModel.register(event);
    }

    @SubscribeEvent
    static void reloadTieConfig(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != ClientConfig.SPEC) return;
        Minecraft client = Minecraft.getInstance();
        client.execute(client.levelExtractor::allChanged);
    }

    private static final net.minecraft.client.KeyMapping.Category CONTROLS = new net.minecraft.client.KeyMapping.Category(
        Identifier.fromNamespaceAndPath(Trainmod.ID, "railway"));
    private static final net.minecraft.client.KeyMapping HORN = new net.minecraft.client.KeyMapping(
        "key.trainmod.horn", com.mojang.blaze3d.platform.InputConstants.KEY_H, CONTROLS);
    private static final net.minecraft.client.KeyMapping BELL = new net.minecraft.client.KeyMapping(
        "key.trainmod.bell", com.mojang.blaze3d.platform.InputConstants.KEY_B, CONTROLS);

    @SubscribeEvent
    static void registerKeys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.registerCategory(CONTROLS);
        event.register(HORN);
        event.register(BELL);
    }

    private static int lastVehicle = -1;
    private static byte lastKeys;

    @SubscribeEvent
    static void sendControls(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        TrainAudio.update(client);
        boolean bellPressed = false;
        while (BELL.consumeClick()) bellPressed = true;
        if (client.player == null || !(client.player.getVehicle() instanceof TrainVehicle vehicle) || vehicle.getControllingPassenger() != client.player) {
            lastVehicle = -1;
            return;
        }
        byte keys = 0;
        if (client.gui.screen() == null) {
            if (client.options.keyUp.isDown()) keys |= TrainControls.FORWARD;
            if (client.options.keyDown.isDown()) keys |= TrainControls.REVERSE;
            if (client.options.keyJump.isDown()) keys |= TrainControls.WORK;
            if (HORN.isDown()) keys |= TrainControls.HORN;
            if (BELL.isDown() || bellPressed) keys |= TrainControls.BELL;
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
        FreightLoad freight = FreightLoad.EMPTY;
        final ItemStackRenderState[] crateItems = {new ItemStackRenderState(), new ItemStackRenderState(), new ItemStackRenderState()};
        FreightLoad.Bulk bulk = FreightLoad.Bulk.NONE;
        float bulkFill;
        float leftDoorProgress;
        float rightDoorProgress;
        float handbrakeAngle;
        float wheelPhase;
        final float[] pantographHeights = new float[2];
        float boreTireExtension;
        int headlight;
        boolean treecuttersActive;
        final Vec3[] couplerTips = new Vec3[2];
        final Vec3[] couplerVectors = new Vec3[2];
    }

    static Map<Integer, ModelPart> bakeColoredCubes(List<VehicleModels.Box> boxes) {
        return bakeColoredCubes(boxes, 128, 128);
    }

    private static Map<Integer, ModelPart> bakeColoredCubes(List<VehicleModels.Box> boxes, int textureWidth, int textureHeight) {
        Map<Integer, List<ModelPart.Cube>> meshes = new LinkedHashMap<>();
        for (var face : VehicleModels.visibleFaces(boxes)) {
            var box = face.bounds();
            var cube = new ModelPart.Cube(0, 0, box.x(), box.y(), box.z(), box.width(), box.height(), box.length(),
                0, 0, 0, false, textureWidth, textureHeight, Set.of(face.direction()));
            if (face.panel() != null) {
                var region = VehiclePaint.REGIONS.get(VehiclePaint.Pattern.of(face.source()));
                var vertices = cube.polygons[0].vertices();
                for (int i = 0; i < vertices.length; i++) {
                    var vertex = vertices[i];
                    float u = region.u(face.panelU(vertex.x(), vertex.z()));
                    float v = region.v(face.panelV(vertex.y(), vertex.z()));
                    vertices[i] = vertex.remap(u, v);
                }
            }
            meshes.computeIfAbsent(box.color(), color -> new ArrayList<>()).add(cube);
        }
        Map<Integer, ModelPart> parts = new LinkedHashMap<>();
        meshes.forEach((color, cubes) -> parts.put(color, new ModelPart(cubes, Map.of())));
        return parts;
    }

    private static final class VehicleRenderer<T extends Entity> extends EntityRenderer<T, VehicleState> {
        private static final RenderType MATERIAL = RenderTypes.entityCutout(
            Identifier.fromNamespaceAndPath(Trainmod.ID, "textures/entity/vehicle_paint.png"));
        private static final RenderType GRAVEL = RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/block/gravel.png"));
        private static final RenderType COBBLESTONE = RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/block/cobblestone.png"));
        private static final RenderType DIRT = RenderTypes.entityCutout(Identifier.withDefaultNamespace("textures/block/dirt.png"));
        private static final float BULK_HEIGHT = 5.38f;
        private final Map<Integer, ModelPart> groups;
        private final ItemModelResolver itemModels;
        private final boolean flatcar;
        private final Map<FreightLoad, Map<Integer, ModelPart>> freightModels = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<FreightLoad, Map<Integer, ModelPart>> eldest) {
                return size() > 64;
            }
        };
        private final Map<Integer, ModelPart> leftDoor;
        private final Map<Integer, ModelPart> rightDoor;
        private final Map<Integer, ModelPart> brakeWheel;
        private final double brakeX;
        private final float brakeY;
        private final float doorTravel;
        private final ModelPart bulkSurface;
        private final MetricEntityModel drawbar;
        private final List<VehicleModels.Wheel> wheels;
        private final Map<Integer, ModelPart> boreRoadGear;
        private final MetricEntityModel pantographArm;
        private final Map<Integer, ModelPart> pantographHead;
        private final List<Map<Integer, ModelPart>> wheelParts;
        private final Map<Integer, ModelPart> rods;
        private final MetricEntityModel piston;
        private final List<Map<Integer, ModelPart>> lights;
        private final List<Map<Integer, ModelPart>> treecuttersLantern;
        private final BlockModelRenderState craftingTable;

        VehicleRenderer(EntityRendererProvider.Context context, List<VehicleModels.Box> boxes, TrainVehicle.Kind kind) {
            super(context);
            shadowRadius = 0.9f;
            groups = bakeVehicleCubes(boxes);
            MeshDefinition model = new MeshDefinition();
            model.getRoot().addOrReplaceChild("drawbar", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-1, -0.58f, -0.04f, 2, 1.16f, 16.08f), PartPose.ZERO);
            drawbar = metric(LayerDefinition.create(model, VehiclePaint.ATLAS_WIDTH, VehiclePaint.ATLAS_HEIGHT).bakeRoot().getChild("drawbar"));
            itemModels = context.getItemModelResolver();
            flatcar = kind == TrainVehicle.Kind.FLATCAR;
            leftDoor = kind == TrainVehicle.Kind.BOXCAR ? bakeVehicleCubes(VehicleModels.doors(kind, true)) : Map.of();
            rightDoor = kind == TrainVehicle.Kind.BOXCAR ? bakeVehicleCubes(VehicleModels.doors(kind, false)) : Map.of();
            brakeWheel = kind != null && !kind.powered ? bakeVehicleCubes(VehicleModels.brakeWheel(kind)) : Map.of();
            brakeX = kind != null ? kind.brakeWheelOffset() : 0;
            brakeY = kind != null ? kind.brakeWheelHeight() / 16 : 0;
            doorTravel = kind == TrainVehicle.Kind.BOXCAR ? VehicleModels.OPEN_DOOR_SHIFT * kind.length / 40 : 0;
            bulkSurface = kind == TrainVehicle.Kind.GONDOLA || kind == TrainVehicle.Kind.BALLAST_CAR ? bulkSurface(kind) : null;
            wheels = kind == TrainVehicle.Kind.STEAM_LOCOMOTIVE ? VehicleModels.steamWheels(kind) : List.of();
            pantographArm = kind == TrainVehicle.Kind.ELECTRIC_SHUNTER ? metric(bakeVehicleCubes(List.of(
                new VehicleModels.Box(0x9b9891, -0.36f, -0.36f, 0, 0.72f, 0.72f, 16))).get(0x9b9891)) : null;
            pantographHead = kind == TrainVehicle.Kind.ELECTRIC_SHUNTER ? bakeVehicleCubes(List.of(
                new VehicleModels.Box(0x3c3531, (float) (-16 * Pantographs.HALF_HEAD), -0.48f, -0.55f,
                    (float) (32 * Pantographs.HALF_HEAD), 0.48f, 1.1f),
                new VehicleModels.Box(0x76736e, -3.7f, -0.95f, -1.15f, 7.4f, 0.47f, 2.3f))) : Map.of();
            boreRoadGear = kind == TrainVehicle.Kind.TUNNEL_BORE ? bakeVehicleCubes(VehicleModels.boreRoadGear()) : Map.of();
            wheelParts = wheels.stream().map(wheel -> bakeVehicleCubes(wheel.boxes())).toList();
            rods = wheels.isEmpty() ? Map.of() : bakeVehicleCubes(VehicleModels.steamRods(kind));
            piston = wheels.isEmpty() ? null : metric(bakeVehicleCubes(List.of(
                new VehicleModels.Box(0xa9a7a1, -0.31f, -0.34f, 0, 0.62f, 0.68f, 16))).get(0xa9a7a1));
            lights = kind == TrainVehicle.Kind.LOCOMOTIVE
                ? List.of(bakeVehicleCubes(VehicleModels.headlight(kind, -1, false)),
                    bakeVehicleCubes(VehicleModels.headlight(kind, 1, false)),
                    bakeVehicleCubes(VehicleModels.headlight(kind, -1, true)),
                    bakeVehicleCubes(VehicleModels.headlight(kind, 1, true))) : List.of();
            treecuttersLantern = kind == TrainVehicle.Kind.TREECUTTERS_PASSENGER_CAR
                ? List.of(bakeVehicleCubes(VehicleModels.treecuttersLantern(kind, false)),
                    bakeVehicleCubes(VehicleModels.treecuttersLantern(kind, true))) : List.of();
            craftingTable = treecuttersLantern.isEmpty() ? null : new BlockModelRenderState();
            if (craftingTable != null) {
                context.getBlockModelResolver().update(craftingTable, Blocks.CRAFTING_TABLE.defaultBlockState(),
                    DisplayRenderer.BLOCK_DISPLAY_CONTEXT);
            }
        }

        private static Map<Integer, ModelPart> bakeVehicleCubes(List<VehicleModels.Box> boxes) {
            return bakeColoredCubes(boxes, VehiclePaint.ATLAS_WIDTH, VehiclePaint.ATLAS_HEIGHT);
        }

        private static MetricEntityModel metric(ModelPart part) { return new MetricEntityModel(part, 16f / VehiclePaint.ATLAS_WIDTH); }

        private static ModelPart bulkSurface(TrainVehicle.Kind kind) {
            float halfWidth = VehicleModels.GONDOLA_FLOOR_HALF_WIDTH * kind.width / 2 - 0.04f;
            float halfLength = VehicleModels.GONDOLA_FLOOR_HALF_LENGTH * kind.length / 2.5f - 0.04f;
            var mesh = new MeshDefinition();
            mesh.getRoot().addOrReplaceChild("bulk", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-halfWidth, 0, -halfLength, 2 * halfWidth, BULK_HEIGHT, 2 * halfLength), PartPose.ZERO);
            return LayerDefinition.create(mesh, 16, 16).bakeRoot().getChild("bulk");
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
                state.freight = train.freightLoad();
                for (int i = 0; i < state.freight.crateItems().size(); i++) {
                    var item = BuiltInRegistries.ITEM.getValue(state.freight.crateItems().get(i));
                    itemModels.updateForNonLiving(state.crateItems[i], new ItemStack(item), ItemDisplayContext.FIXED, entity);
                }
                state.bulk = train.kind == TrainVehicle.Kind.BALLAST_CAR ? FreightLoad.Bulk.GRAVEL : state.freight.bulk();
                state.bulkFill = train.kind == TrainVehicle.Kind.BALLAST_CAR ? train.gravelFill() : state.freight.fillPercent() / 100f;
                state.leftDoorProgress = train.doorProgress(true, partial);
                state.rightDoorProgress = train.doorProgress(false, partial);
                state.handbrakeAngle = train.handbrakeAngle(partial);
                state.wheelPhase = train.wheelPhase(partial);
                for (int head = 0; pantographArm != null && head < 2; head++) {
                    state.pantographHeights[head] = train.pantographHeight(head, partial);
                }
                state.boreTireExtension = train.boreTireExtension(partial);
                state.headlight = train.headlight();
                state.treecuttersActive = train.treecuttersActive();
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
            AABB bounds = super.getBoundingBoxForCulling(entity, partial).inflate(0.5);
            if (pantographArm != null) return bounds.expandTowards(0, 3.4, 0);
            if (entity instanceof TrainVehicle train && train.freightLoad().hasModel()) {
                return bounds.expandTowards(0, 1.45, 0);
            }
            return bounds;
        }

        @Override
        public void submit(VehicleState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
            pose.pushPose();
            pose.translate(0, Math.abs(state.bank) * CurveBanking.GAUGE / 2, 0);
            pose.rotateDegrees(Axis.YP, -state.yaw);
            pose.rotateDegrees(Axis.XP, state.pitch);
            pose.rotateDegrees(Axis.ZP, (float) Math.toDegrees(Math.atan(state.bank)));
            submitParts(groups, state, pose, collector);
            if (pantographArm != null) submitPantographs(state, pose, collector);
            if (!wheels.isEmpty()) submitSteamGear(state, pose, collector);
            if (!boreRoadGear.isEmpty()) {
                pose.pushPose();
                pose.translate(0, -state.boreTireExtension, 0);
                submitParts(boreRoadGear, state, pose, collector);
                pose.popPose();
            }
            if (!lights.isEmpty()) {
                for (int end : new int[]{-1, 1}) {
                    boolean leading = state.headlight == end;
                    var parts = lights.get((end + 1) / 2 + (leading ? 2 : 0));
                    int light = leading ? net.minecraft.util.LightCoordsUtil.FULL_BRIGHT : state.lightCoords;
                    parts.forEach((color, model) -> collector.submitModelPart(model, pose, MATERIAL, light,
                        OverlayTexture.NO_OVERLAY, null, 0xff000000 | color, state.outlineColor));
                }
            }
            if (!treecuttersLantern.isEmpty()) {
                treecuttersLantern.get(state.treecuttersActive ? 1 : 0).forEach((color, model) -> {
                    int light = color == VehicleModels.LANTERN_GLOW ? net.minecraft.util.LightCoordsUtil.FULL_BRIGHT : state.lightCoords;
                    collector.submitModelPart(model, pose, MATERIAL, light, OverlayTexture.NO_OVERLAY,
                        null, 0xff000000 | color, state.outlineColor);
                });
            }
            if (craftingTable != null) {
                Vec3 corner = VehicleModels.CRAFTING_TABLE_CORNER;
                float size = (float) VehicleModels.CRAFTING_TABLE_SIZE;
                pose.pushPose();
                pose.translate(corner.x, corner.y, corner.z);
                pose.scale(size, size, size);
                craftingTable.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
                pose.popPose();
            }
            submitFreight(state, pose, collector);
            submitDoor(leftDoor, state.leftDoorProgress, state, pose, collector);
            submitDoor(rightDoor, state.rightDoorProgress, state, pose, collector);
            if (!brakeWheel.isEmpty()) {
                pose.pushPose();
                pose.translate(brakeX, brakeY, 0);
                pose.rotateDegrees(Axis.ZP, state.handbrakeAngle);
                submitParts(brakeWheel, state, pose, collector);
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
                collector.submitModelPart(drawbar.animatedLength(span.length()), pose, MATERIAL, state.lightCoords, OverlayTexture.NO_OVERLAY,
                    null, 0xff292c32, state.outlineColor);
                pose.popPose();
            }
            super.submit(state, pose, collector, camera);
        }

        private void submitSteamGear(VehicleState state, PoseStack pose, SubmitNodeCollector collector) {
            for (int i = 0; i < wheels.size(); i++) {
                var wheel = wheels.get(i);
                pose.pushPose();
                pose.translate(0, wheel.y() / 16, wheel.z() / 16);
                pose.rotateDegrees(Axis.XP, -state.wheelPhase * (float) (16 * TrainVehicle.DRIVER_WHEEL_RADIUS / wheel.radius()));
                submitParts(wheelParts.get(i), state, pose, collector);
                pose.popPose();
            }

            double angle = Math.toRadians(state.wheelPhase);
            double crankY = Math.sin(angle) * 1.15 / 16;
            double crankZ = Math.cos(angle) * 1.15 / 16;
            var rearDriver = wheels.get(1);
            var frontDriver = wheels.get(2);
            pose.pushPose();
            pose.translate(0, rearDriver.y() / 16 + crankY, rearDriver.z() / 16 + crankZ);
            submitParts(rods, state, pose, collector);
            pose.popPose();

            var leadingTruck = wheels.get(3);
            double run = (leadingTruck.z() - frontDriver.z()) / 16 - crankZ;
            for (int side : new int[]{-1, 1}) {
                pose.pushPose();
                pose.translate(side * 0.37, frontDriver.y() / 16 + crankY, frontDriver.z() / 16 + crankZ);
                pose.rotateDegrees(Axis.XP, (float) Math.toDegrees(Math.atan2(crankY, run)));
                collector.submitModelPart(piston.animatedLength(Math.hypot(run, crankY)), pose, MATERIAL, state.lightCoords, OverlayTexture.NO_OVERLAY,
                    null, 0xffa9a7a1, state.outlineColor);
                pose.popPose();
            }
        }

        private void submitDoor(Map<Integer, ModelPart> parts, float progress, VehicleState state, PoseStack pose,
                                SubmitNodeCollector collector) {
            if (parts.isEmpty()) return;
            pose.pushPose();
            pose.translate(0, 0, progress * doorTravel);
            submitParts(parts, state, pose, collector);
            pose.popPose();
        }

        private void submitPantographs(VehicleState state, PoseStack pose, SubmitNodeCollector collector) {
            for (int head = 0; head < 2; head++) {
                double roof = Pantographs.roof(head) + 0.07;
                double height = state.pantographHeights[head];
                double z = Pantographs.longitudinal(head);
                double fold = (head == 0 ? 1 : -1) * Math.clamp(0.42 - (height - roof) * 0.12, 0.08, 0.39);
                for (double x : new double[]{-0.21, 0.21}) {
                    Vec3 base = new Vec3(x, roof, z);
                    Vec3 knee = new Vec3(x, (roof + height) / 2, z + fold);
                    Vec3 shoe = new Vec3(x, height - 0.05, z);
                    submitPantographArm(base, knee, state, pose, collector);
                    submitPantographArm(knee, shoe, state, pose, collector);
                }
                pose.pushPose();
                pose.translate(0, height, z);
                submitParts(pantographHead, state, pose, collector);
                pose.popPose();
            }
        }

        private void submitPantographArm(Vec3 from, Vec3 to, VehicleState state, PoseStack pose, SubmitNodeCollector collector) {
            Vec3 delta = to.subtract(from);
            pose.pushPose();
            pose.translate(from.x, from.y, from.z);
            pose.rotateDegrees(Axis.YP, delta.z < 0 ? 180 : 0);
            pose.rotateDegrees(Axis.XP, (float) -Math.toDegrees(Math.atan2(delta.y, Math.abs(delta.z))));
            collector.submitModelPart(pantographArm.animatedLength(delta.length()), pose, MATERIAL, state.lightCoords,
                OverlayTexture.NO_OVERLAY, null, 0xff9b9891, state.outlineColor);
            pose.popPose();
        }

        private static void submitParts(Map<Integer, ModelPart> parts, VehicleState state, PoseStack pose,
                                        SubmitNodeCollector collector) {
            parts.forEach((color, model) -> collector.submitModelPart(model, pose, MATERIAL, state.lightCoords,
                OverlayTexture.NO_OVERLAY, null, 0xff000000 | color, state.outlineColor));
        }

        private void submitFreight(VehicleState state, PoseStack pose, SubmitNodeCollector collector) {
            if (bulkSurface != null && state.bulkFill > 0) {
                RenderType texture = switch (state.bulk) {
                    case COBBLESTONE -> COBBLESTONE;
                    case DIRT -> DIRT;
                    default -> GRAVEL;
                };
                pose.pushPose();
                pose.translate(0, VehicleModels.DECK_HEIGHT, 0);
                pose.scale(1, state.bulkFill, 1);
                collector.submitModelPart(bulkSurface, pose, texture, state.lightCoords, OverlayTexture.NO_OVERLAY,
                    null, 0xffffffff, state.outlineColor);
                pose.popPose();
            }
            if (state.freight.hasModel()) {
                var parts = freightModels.computeIfAbsent(state.freight, load -> bakeVehicleCubes(FreightModels.cargo(load, flatcar)));
                pose.pushPose();
                pose.translate(0, state.bulkFill * BULK_HEIGHT / 16, 0);
                submitParts(parts, state, pose, collector);
                submitCrateItems(state, pose, collector);
                pose.popPose();
            }
        }

        private static void submitCrateItems(VehicleState state, PoseStack pose, SubmitNodeCollector collector) {
            for (int i = 0; i < state.freight.crateItems().size(); i++) {
                var item = state.crateItems[i];
                if (item.isEmpty()) continue;
                AABB bounds = item.getModelBoundingBox();
                Vec3 center = bounds.getCenter();
                float size = (float) (0.15 / Math.max(0.01, Math.max(bounds.getXsize(), bounds.getYsize())));
                float width = size * FreightModels.crateItemWidth(state.freight);
                float depth = (float) Math.min(size, 0.036 / Math.max(0.01, bounds.getZsize()));
                for (int crate = 0; crate < state.freight.crateCount(); crate++) {
                    for (int side : new int[]{-1, 1}) {
                        float z = FreightModels.crateItemZ(state.freight, crate, i, side);
                        pose.pushPose();
                        pose.translate(side * (FreightModels.CRATE_SIDE / 16 + 0.02), FreightModels.CRATE_ITEM_HEIGHT / 16, z / 16);
                        pose.rotateDegrees(Axis.YP, side * 90);
                        pose.scale(width, size, depth);
                        pose.translate(-center.x, -center.y, -center.z);
                        item.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
                        pose.popPose();
                    }
                }
            }
        }
    }
}
