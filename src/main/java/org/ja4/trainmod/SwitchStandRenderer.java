package org.ja4.trainmod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class SwitchStandRenderer implements BlockEntityRenderer<SwitchStand, SwitchStandRenderer.State> {
    private static final RenderType MATERIAL = RenderTypes.entityCutout(
        Identifier.fromNamespaceAndPath(Trainmod.ID, "textures/entity/paint.png"));
    private final Map<Integer, ModelPart> head = TrainClient.bakeColoredCubes(SwitchStandModel.head());

    static final class State extends BlockEntityRenderState {
        float rotation;
    }

    @Override
    public State createRenderState() { return new State(); }

    @Override
    public AABB getRenderBoundingBox(SwitchStand stand) {
        return new AABB(stand.getBlockPos()).expandTowards(0, 0.25, 0);
    }

    @Override
    public void extractRenderState(SwitchStand stand, State render, float partial, Vec3 camera,
                                   ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(stand, render, partial, camera, breakProgress);
        var level = stand.getLevel();
        if (level == null) return;
        double now = level.getGameTime() + partial;
        var connection = RailwayBlocks.Stand.connection(level, stand.getBlockPos());
        if (connection == null) {
            var facing = stand.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
            render.rotation = (float) (stand.angle(now) - 90 * facing.get2DDataValue());
            return;
        }
        double phase = level.getBlockEntity(connection.position()) instanceof SwitchPoints points
            ? points.throwPosition(now) : connection.state().getValue(BlockStateProperties.POWERED) ? 1 : 0;
        double angle = Mth.lerp(phase, connection.route(false).angle, connection.route(true).angle);
        render.rotation = (float) (angle - 90 * connection.facing().get2DDataValue());
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.rotateDegrees(Axis.YP, state.rotation);
        head.forEach((color, model) -> collector.submitModelPart(model, pose, MATERIAL, state.lightCoords,
            OverlayTexture.NO_OVERLAY, null, 0xff000000 | color, 0));
        pose.popPose();
    }
}
