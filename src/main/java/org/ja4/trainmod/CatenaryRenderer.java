package org.ja4.trainmod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class CatenaryRenderer implements BlockEntityRenderer<CatenaryTower.Anchor, CatenaryRenderer.State> {
    private static final int STEEL = 0x4c5b62;
    private static final int CONCRETE = 0xb2b0a8;
    private static final int INSULATOR = 0xe1d8b2;
    private static final int WIRE = 0x635647;
    private static final RenderType MATERIAL = RenderTypes.entityCutout(
        Identifier.fromNamespaceAndPath(Trainmod.ID, "textures/entity/paint.png"));
    private final Map<CatenaryTower.Anchor, Assembly> cache = new WeakHashMap<>();
    private final ModelPart foot = part(CONCRETE, -3.5f, 0, -3.5f, 7,
        (float) (16 * TrackGeometry.RAIL_HEIGHT), 7);
    private final ModelPart wire = part(WIRE, -0.27f, -0.27f, 0, 0.54f, 0.54f, 16);
    private final ModelPart post = TrainClient.bakeColoredCubes(List.of(
        new VehicleModels.Box(STEEL, -1.85f, 0, -1.55f, 3.7f, 41, 0.55f),
        new VehicleModels.Box(STEEL, -1.85f, 0, 1, 3.7f, 41, 0.55f),
        new VehicleModels.Box(STEEL, -0.42f, 0, -1, 0.84f, 41, 2))).get(STEEL);
    private final ModelPart arm = TrainClient.bakeColoredCubes(List.of(
        new VehicleModels.Box(STEEL, -1.15f, -1, 0, 2.3f, 0.48f, 16),
        new VehicleModels.Box(STEEL, -1.15f, 0.52f, 0, 2.3f, 0.48f, 16),
        new VehicleModels.Box(STEEL, -0.39f, -0.52f, 0, 0.78f, 1.04f, 16))).get(STEEL);
    private final ModelPart ceilingHanger = part(STEEL, -1f, 0, -1f, 2, 16, 2);
    private final ModelPart hanger = part(STEEL, -0.52f, 0, -0.52f, 1.04f, 16, 1.04f);
    private final ModelPart insulator = part(INSULATOR, -1.25f, 0, -1.25f, 2.5f, 3, 2.5f);

    private record Assembly(long tick, CatenaryTower.Kind kind, BlockPos step, boolean diagonal, List<Direction> neighbors,
                            int ceiling, List<Vec3> contacts, List<List<Vec3>> spans) {}

    static final class State extends BlockEntityRenderState {
        Assembly assembly;
    }

    @Override
    public State createRenderState() { return new State(); }

    @Override
    public AABB getRenderBoundingBox(CatenaryTower.Anchor anchor) {
        return new AABB(anchor.getBlockPos()).inflate(Catenary.SPAN_LIMIT, CatenaryTower.Kind.GANTRY.height + 1, Catenary.SPAN_LIMIT);
    }

    @Override
    public void extractRenderState(CatenaryTower.Anchor anchor, State render, float partial, Vec3 camera,
                                   ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(anchor, render, partial, camera, breakProgress);
        var level = anchor.getLevel();
        render.assembly = null;
        if (level == null || !(anchor.getBlockState().getBlock() instanceof CatenaryTower tower)) return;
        Assembly old = cache.get(anchor);
        if (old != null && old.tick() == level.getGameTime()) {
            render.assembly = old;
            return;
        }
        BlockPos pos = anchor.getBlockPos();
        Vec3 origin = Vec3.atLowerCornerOf(pos).add(0.5, 0, 0.5);
        var spans = new java.util.LinkedHashSet<List<Vec3>>();
        var attachments = Catenary.attachments(level, pos, anchor.getBlockState());
        for (var attachment : attachments) {
            for (int direction : new int[]{-1, 1}) {
                for (var span : Catenary.spansFrom(level, attachment, direction)) {
                    if (pos.compareTo(span.end().tower()) < 0) {
                        spans.add(span.wire().stream().map(point -> point.subtract(origin)).toList());
                    }
                }
            }
        }
        List<Direction> neighbors = new ArrayList<>();
        int ceiling = 0;
        if (tower.kind == CatenaryTower.Kind.GANTRY) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (level.getBlockState(pos.relative(direction)).is(Trainmod.CATENARY_GANTRY.get())) neighbors.add(direction);
            }
            for (int height = 1; height <= 2; height++) {
                BlockPos above = pos.above(height);
                BlockState state = level.getBlockState(above);
                if (state.isFaceSturdy(level, above, Direction.DOWN)) {
                    ceiling = height;
                    break;
                }
                if (!state.isAir()) break;
            }
        }
        render.assembly = new Assembly(level.getGameTime(), tower.kind, CatenaryTower.armStep(anchor.getBlockState()),
            anchor.getBlockState().getValue(CatenaryTower.DIAGONAL), List.copyOf(neighbors), ceiling,
            attachments.stream().map(attachment -> attachment.contact().subtract(origin)).distinct().toList(), List.copyOf(spans));
        cache.put(anchor, render.assembly);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        Assembly assembly = state.assembly;
        if (assembly == null) return;
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        if (assembly.kind() == CatenaryTower.Kind.GANTRY) {
            double beamHeight = CatenaryTower.GANTRY_BEAM;
            if (assembly.ceiling() > 0) {
                vertical(ceilingHanger, new Vec3(0, beamHeight, 0), assembly.ceiling() - beamHeight, state, pose, collector);
            }
            var contacts = assembly.contacts().isEmpty()
                ? List.of(new Vec3(0, CatenaryTower.GANTRY_CONTACT, 0)) : assembly.contacts();
            for (Vec3 contact : contacts) {
                submitPart(insulator, INSULATOR, contact, state, pose, collector);
                Vec3 base = new Vec3(contact.x, beamHeight, contact.z);
                beam(arm, STEEL, new Vec3(0, beamHeight, 0), base, state, pose, collector);
                submitHanger(contact, base.y, state, pose, collector);
            }
            for (Direction direction : assembly.neighbors()) {
                Vec3 start = new Vec3(0, beamHeight, 0);
                beam(arm, STEEL, start, start.add(direction.getStepX() * 0.5, 0, direction.getStepZ() * 0.5),
                    state, pose, collector);
            }
        } else {
            Vec3 step = Vec3.atLowerCornerOf(assembly.step());
            Vec3 across = step.normalize();
            double stepLength = step.horizontalDistance();
            boolean doubleTrack = assembly.kind() == CatenaryTower.Kind.DOUBLE;
            List<Vec3> contacts = assembly.contacts();
            if (contacts.isEmpty()) {
                contacts = java.util.stream.IntStream.range(0, assembly.kind().reaches.length)
                    .mapToObj(track -> step.scale(assembly.kind().reach(track, assembly.diagonal()))
                        .add(0, assembly.kind().height + TrackGeometry.RAIL_HEIGHT, 0)).toList();
            }
            double farthest = contacts.stream().mapToDouble(across::dot).max().orElse(1);
            double reach = doubleTrack ? Math.max(assembly.kind().maxReach(assembly.diagonal()) * stepLength, farthest + stepLength)
                : Math.max(stepLength + 0.15, farthest + 0.15);
            double beamHeight = contacts.stream().mapToDouble(contact -> contact.y + 0.25).max().orElse(assembly.kind().height + 0.5);
            submitPost(Vec3.ZERO, across, beamHeight, state, pose, collector);
            if (doubleTrack) submitPost(across.scale(reach), across, beamHeight, state, pose, collector);
            Vec3 head = new Vec3(0, beamHeight, 0);
            beam(arm, STEEL, head, head.add(across.scale(reach)), state, pose, collector);
            for (Vec3 contact : contacts) {
                submitPart(insulator, INSULATOR, contact, state, pose, collector);
                Vec3 bracket = new Vec3(contact.x, head.y, contact.z);
                beam(arm, STEEL, head.add(across.scale(across.dot(contact))), bracket, state, pose, collector);
                submitHanger(contact, beamHeight, state, pose, collector);
            }
        }
        for (List<Vec3> span : assembly.spans()) {
            for (int i = 1; i < span.size(); i++) beam(wire, WIRE, span.get(i - 1), span.get(i), state, pose, collector);
        }
        pose.popPose();
    }

    private void submitPost(Vec3 location, Vec3 across, double beamHeight, State state, PoseStack pose,
                            SubmitNodeCollector collector) {
        pose.pushPose();
        pose.translate(location.x, location.y, location.z);
        pose.rotateDegrees(Axis.YP, (float) Math.toDegrees(Math.atan2(across.x, across.z)));
        submitPart(foot, CONCRETE, Vec3.ZERO, state, pose, collector);
        pose.scale(1, (float) ((beamHeight + 1 / 16.0) / (41 / 16.0)), 1);
        submitPart(post, STEEL, Vec3.ZERO, state, pose, collector);
        pose.popPose();
    }

    private void submitHanger(Vec3 contact, double beamHeight, State state, PoseStack pose, SubmitNodeCollector collector) {
        double top = contact.y + 3 / 16.0;
        double length = Math.abs(top - beamHeight);
        if (length < 0.04) return;
        vertical(hanger, new Vec3(contact.x, Math.min(top, beamHeight), contact.z), length, state, pose, collector);
    }

    private static void vertical(ModelPart part, Vec3 start, double length, State state, PoseStack pose,
                                 SubmitNodeCollector collector) {
        pose.pushPose();
        pose.translate(start.x, start.y, start.z);
        pose.scale(1, (float) length, 1);
        submitPart(part, STEEL, Vec3.ZERO, state, pose, collector);
        pose.popPose();
    }

    private static ModelPart part(int color, float x, float y, float z, float width, float height, float length) {
        return TrainClient.bakeColoredCubes(List.of(new VehicleModels.Box(color, x, y, z, width, height, length))).get(color);
    }

    private static void beam(ModelPart part, int color, Vec3 start, Vec3 end, State state, PoseStack pose, SubmitNodeCollector collector) {
        Vec3 delta = end.subtract(start);
        if (delta.lengthSqr() < 1e-8) return;
        pose.pushPose();
        pose.translate(start.x, start.y, start.z);
        pose.rotateDegrees(Axis.YP, (float) Math.toDegrees(Math.atan2(delta.x, delta.z)));
        pose.rotateDegrees(Axis.XP, (float) -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
        pose.scale(1, 1, (float) delta.length());
        submitPart(part, color, Vec3.ZERO, state, pose, collector);
        pose.popPose();
    }

    private static void submitPart(ModelPart part, int color, Vec3 location, State state, PoseStack pose,
                                   SubmitNodeCollector collector) {
        pose.pushPose();
        pose.translate(location.x, location.y, location.z);
        collector.submitModelPart(part, pose, MATERIAL, state.lightCoords, OverlayTexture.NO_OVERLAY,
            null, 0xff000000 | color, 0);
        pose.popPose();
    }

}
