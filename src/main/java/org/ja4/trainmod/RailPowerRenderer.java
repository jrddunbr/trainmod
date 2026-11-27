package org.ja4.trainmod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

@EventBusSubscriber(modid = Trainmod.ID, value = Dist.CLIENT)
final class RailPowerRenderer {
    private static final net.minecraft.client.renderer.rendertype.RenderType MATERIAL = RenderTypes.entityCutout(
        Identifier.fromNamespaceAndPath(Trainmod.ID, "textures/entity/paint.png"));
    private static final int COPPER = 0x695141;
    private static final ContextKey<List<TransmissionCable>> TRANSMISSION =
        new ContextKey<>(Identifier.fromNamespaceAndPath(Trainmod.ID, "transmission_cables"));

    private RailPowerRenderer() {}

    static final class BankRenderer implements BlockEntityRenderer<TransformerBank.Bank, BankState> {
        private final ModelPart dial = part(-0.12f, -2.9f, -3.4f, 0.35f, 5.8f, 6.8f);
        private final ModelPart tick = part(-0.2f, 1.55f, -0.14f, 0.35f, 0.72f, 0.28f);
        private final ModelPart needle = part(-0.24f, -0.35f, -0.16f, 0.45f, 2.56f, 0.32f);
        private final ModelPart lamp = part(-0.20f, -0.62f, -0.62f, 0.4f, 1.24f, 1.24f);
        private final MetricEntityModel feeder = cable(0.57f);

        @Override
        public BankState createRenderState() { return new BankState(); }

        @Override
        public AABB getRenderBoundingBox(TransformerBank.Bank bank) {
            AABB bounds = new AABB(bank.getBlockPos()).inflate(5);
            return bank.transmission() == null ? bounds : bounds.minmax(new AABB(bank.transmission()));
        }

        @Override
        public void extractRenderState(TransformerBank.Bank bank, BankState state, float partial, Vec3 camera,
                                       ModelFeatureRenderer.CrumblingOverlay progress) {
            BlockEntityRenderer.super.extractRenderState(bank, state, partial, camera, progress);
            var facing = bank.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
            state.yaw = 180 - 90 * facing.get2DDataValue();
            state.reading = Math.clamp(bank.meterFraction(), 0, 1);
            state.live = bank.live();
            Vec3 origin = Vec3.atBottomCenterOf(bank.getBlockPos());
            state.tap = bank.tap() == null ? null : bank.tap().subtract(origin);
            state.transmission = bank.transmission() == null ? null
                : TransmissionExtension.terminal(bank.transmission()).subtract(origin);
            state.transmissionBushing = TransformerBank.transmissionTerminal(bank.getBlockPos(), facing).subtract(origin);
            state.bushing = Vec3.atLowerCornerOf(facing.getUnitVec3i()).scale(0.25).add(0, 2.91, 0);
        }

        @Override
        public void submit(BankState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
            pose.pushPose();
            pose.translate(0.5, 0, 0.5);
            if (state.tap != null) beam(feeder, state.bushing, state.tap, state.lightCoords, pose, collector);
            if (state.transmission != null) beam(feeder, state.transmissionBushing, state.transmission, state.lightCoords, pose, collector);
            pose.rotateDegrees(Axis.YP, state.yaw);
            pose.translate(-0.44, 1.58, 0);
            submitPart(dial, 0xd6d1b7, state.lightCoords, pose, collector);
            for (int angle = -126; angle <= 126; angle += 42) {
                pose.pushPose();
                pose.rotateDegrees(Axis.XP, angle);
                submitPart(tick, 0x2e3535, state.lightCoords, pose, collector);
                pose.popPose();
            }
            pose.pushPose();
            pose.rotateDegrees(Axis.XP, -126 + 252 * state.reading);
            submitPart(needle, 0x993b2d, state.lightCoords, pose, collector);
            pose.popPose();
            pose.translate(0, -0.25, 0);
            submitPart(lamp, state.live ? 0x65ff80 : 0x25432d,
                state.live ? LightCoordsUtil.FULL_BRIGHT : state.lightCoords, pose, collector);
            pose.popPose();
        }
    }

    static final class BankState extends BlockEntityRenderState {
        float yaw;
        float reading;
        boolean live;
        Vec3 bushing;
        Vec3 tap;
        Vec3 transmissionBushing;
        Vec3 transmission;
    }

    private record Span(BlockPos first, BlockPos second) {
        static Span between(BlockPos a, BlockPos b) {
            return a.compareTo(b) < 0 ? new Span(a, b) : new Span(b, a);
        }
    }

    record TransmissionCable(Vec3 from, Vec3 to, int light) {}

    private static final class TransmissionModel {
        private static final ModelPart CABLE = part(-0.225f, -0.225f, 0, 0.45f, 0.45f, 16);
    }

    @SubscribeEvent
    static void extractTransmission(ExtractLevelRenderStateEvent event) {
        double range = 16.0 * Minecraft.getInstance().options.getEffectiveRenderDistance();
        var render = event.getRenderState();
        var cables = visibleTransmission(event.getLevel(), render.cameraRenderState.pos, range);
        if (!cables.isEmpty()) render.setRenderData(TRANSMISSION, cables);
    }

    static List<TransmissionCable> visibleTransmission(Level level, Vec3 camera, double range) {
        List<TransmissionCable> cables = new ArrayList<>();
        var seen = new HashSet<Span>();
        for (BlockPos pos : CatenaryPower.trackedTips(level)) {
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof TransmissionExtension.Tip tip)) continue;
            for (BlockPos neighbor : tip.neighbors()) {
                if (!seen.add(Span.between(pos, neighbor))) continue;
                Vec3 from = TransmissionExtension.terminal(pos), to = TransmissionExtension.terminal(neighbor);
                var cable = clipTransmission(from, to, camera, range, LightCoordsUtil.getLightCoords(level, pos));
                if (cable != null) cables.add(cable);
            }
        }
        return cables;
    }

    private static TransmissionCable clipTransmission(Vec3 from, Vec3 to, Vec3 camera, double range, int light) {
        Vec3 delta = to.subtract(from), relative = from.subtract(camera).multiply(1, 0, 1);
        double horizontal = delta.horizontalDistanceSqr();
        if (horizontal < 1e-8) return null;
        double midpoint = -relative.dot(delta) / horizontal;
        double nearest = relative.add(delta.multiply(1, 0, 1).scale(midpoint)).lengthSqr();
        if (nearest >= range * range) return null;
        double reach = Math.sqrt((range * range - nearest) / horizontal);
        double start = Math.max(0, midpoint - reach), end = Math.min(1, midpoint + reach);
        return start < end ? new TransmissionCable(from.lerp(to, start), from.lerp(to, end), light) : null;
    }

    @SubscribeEvent
    static void submitTransmission(SubmitCustomGeometryEvent event) {
        var render = event.getLevelRenderState();
        var cables = render.getRenderData(TRANSMISSION);
        if (cables == null) return;
        var pose = event.getPoseStack();
        Vec3 camera = render.cameraRenderState.pos;
        for (var cable : cables) {
            Vec3 delta = cable.to.subtract(cable.from);
            pose.pushPose();
            aimBeam(pose, cable.from.subtract(camera), delta);
            pose.scale(1, 1, (float) delta.length());
            submitPart(TransmissionModel.CABLE, COPPER, cable.light, pose, event.getSubmitNodeCollector());
            pose.popPose();
        }
    }

    private static MetricEntityModel cable(float width) {
        return new MetricEntityModel(part(-width / 2, -width / 2, 0, width, width, 16), 16 / 128f);
    }

    private static ModelPart part(float x, float y, float z, float width, float height, float length) {
        return TrainClient.bakeColoredCubes(List.of(new VehicleModels.Box(COPPER, x, y, z, width, height, length))).get(COPPER);
    }

    private static void beam(MetricEntityModel cable, Vec3 start, Vec3 end, int light, PoseStack pose, SubmitNodeCollector collector) {
        Vec3 delta = end.subtract(start);
        if (delta.lengthSqr() < 1e-8) return;
        pose.pushPose();
        aimBeam(pose, start, delta);
        submitPart(cable.length(delta.length()), COPPER, light, pose, collector);
        pose.popPose();
    }

    private static void aimBeam(PoseStack pose, Vec3 start, Vec3 delta) {
        pose.translate(start.x, start.y, start.z);
        pose.rotateDegrees(Axis.YP, (float) Math.toDegrees(Math.atan2(delta.x, delta.z)));
        pose.rotateDegrees(Axis.XP, (float) -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
    }

    private static void submitPart(ModelPart part, int color, int light, PoseStack pose, SubmitNodeCollector collector) {
        collector.submitModelPart(part, pose, MATERIAL, light, OverlayTexture.NO_OVERLAY, null, 0xff000000 | color, 0);
    }
}
