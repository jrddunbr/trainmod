package org.ja4.trainmod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

@EventBusSubscriber(modid = Trainmod.ID, value = Dist.CLIENT)
final class VehicleControlOverlay {
    private static final ContextKey<List<Line>> OUTLINES =
        new ContextKey<>(Identifier.fromNamespaceAndPath(Trainmod.ID, "vehicle_control_outlines"));

    private record Line(Vec3 start, Vec3 end, int color) {
        void draw(PoseStack.Pose pose, VertexConsumer buffer, Vec3 camera) {
            Vec3 direction = end.subtract(start).normalize();
            for (Vec3 point : List.of(start, end)) {
                buffer.addVertex(pose, (float) (point.x - camera.x), (float) (point.y - camera.y), (float) (point.z - camera.z))
                    .setColor(color).setNormal(pose, (float) direction.x, (float) direction.y, (float) direction.z)
                    .setLineWidth(2);
            }
        }
    }

    @SubscribeEvent
    static void extract(ExtractLevelRenderStateEvent event) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.player.isSpectator() || client.gui.screen() != null || client.gui.hud.isHidden()
            || !(client.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof TrainVehicle car)
            || !car.isAlive() || car.level() != event.getLevel()) return;
        float partial = event.getDeltaTracker().getGameTimeDeltaPartialTick(!event.getLevel().tickRateManager().isEntityFrozen(car));
        var frame = car.renderFrame(partial);
        var lines = new ArrayList<Line>();
        for (var target : VehicleHitboxes.of(car.kind).controls()) {
            var box = VehicleHitboxes.bounds(car, target, partial);
            int color = TrainClient.controlColor(target.component());
            for (int vertex = 0; vertex < 8; vertex++) {
                Vec3 corner = frame.corner(box, vertex);
                for (int axis = 1; axis <= 4; axis <<= 1) {
                    if ((vertex & axis) == 0) lines.add(new Line(corner, frame.corner(box, vertex | axis), color));
                }
            }
        }
        event.getRenderState().setRenderData(OUTLINES, lines);
    }

    @SubscribeEvent
    static void submit(SubmitCustomGeometryEvent event) {
        var lines = event.getLevelRenderState().getRenderData(OUTLINES);
        if (lines == null) return;
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.linesDepthBias(),
            (pose, buffer) -> lines.forEach(line -> line.draw(pose, buffer, camera)));
    }
}
