package org.ja4.trainmod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

final class SwitchPointRenderer implements BlockEntityRenderer<SwitchPoints, SwitchPointRenderer.State> {
    private static final Identifier STEEL = Identifier.fromNamespaceAndPath(Trainmod.ID, "textures/entity/paint.png");
    private static final int HEAD = 0xffa9aaa5;
    private static final int WEB = 0xff595957;
    private final Map<SwitchLayout.Panel, List<List<Quad>>> stoppedPoses = new HashMap<>();

    private record Quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
        void draw(PoseStack.Pose pose, VertexConsumer buffer, int light) {
            Vec3 normal = b.subtract(a).cross(c.subtract(a)).normalize();
            vertex(pose, buffer, a, normal, 0, 0, color, light);
            vertex(pose, buffer, b, normal, 0, 1, color, light);
            vertex(pose, buffer, c, normal, 1, 1, color, light);
            vertex(pose, buffer, d, normal, 1, 0, color, light);
        }

        private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, Vec3 point, Vec3 normal,
                                   float u, float v, int color, int light) {
            buffer.addVertex(pose, (float) (point.x + 0.5), (float) point.y, (float) (point.z + 0.5))
                .setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
        }
    }

    static final class State extends BlockEntityRenderState {
        List<Quad> faces = List.of();
        BlockPos originOffset = BlockPos.ZERO;
    }

    @Override
    public State createRenderState() { return new State(); }

    @Override
    public boolean shouldRenderOffScreen() { return true; }

    @Override
    public int getViewDistance() { return 80; }

    @Override
    public void extractRenderState(SwitchPoints points, State render, float partial, Vec3 camera,
                                   ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(points, render, partial, camera, breakProgress);
        render.faces = List.of();
        var state = points.getBlockState();
        var level = points.getLevel();
        if (!(state.getBlock() instanceof TrackBlock.Turnout track) || level == null) return;
        var panel = track.panel(state);
        if (panel == null) return;
        render.originOffset = panel.control();
        double motion = points.throwPosition(level.getGameTime() + partial);
        if (track.kind.wye() && state.getValue(TrackBlock.HAND) == ChestType.RIGHT) motion = 1 - motion;
        var shape = track.shape(state);
        BlockPos root = points.getBlockPos().subtract(panel.control());
        Predicate<Vec3> damaged = point -> TrackBlock.broken(level.getBlockState(root.offset(shape.owner(point))));
        if (panel.blades().stream().anyMatch(blade -> crossesDamage(blade, damaged))) {
            render.faces = geometry(panel, motion, point ->
                damaged.test(point) ? TrackDamage.shift(shape, shape.owner(point), point) : point);
        } else if (motion == 0 || motion == 1) {
            render.faces = stoppedPoses.computeIfAbsent(panel,
                key -> List.of(geometry(key, 0, null), geometry(key, 1, null))).get((int) motion);
        } else {
            render.faces = geometry(panel, motion, null);
        }
    }

    private static boolean crossesDamage(SwitchLayout.Blade blade, Predicate<Vec3> damaged) {
        double start = Math.min(blade.distance(0), blade.distance(1));
        double end = Math.max(blade.distance(0), blade.distance(1));
        for (var span : blade.path().columns()) {
            double low = Math.max(start, span.start());
            double high = Math.min(end, span.end());
            if (high - low > 1e-7 && damaged.test(blade.path().point((low + high) / 2))) return true;
        }
        return false;
    }

    private static List<Quad> geometry(SwitchLayout.Panel panel, double motion, @Nullable UnaryOperator<Vec3> bend) {
        List<Quad> faces = new ArrayList<>();
        blade(faces, panel.first(), motion, bend);
        blade(faces, panel.second(), 1 - motion, bend);
        Vec3 first = panel.first().point(0.12, motion), second = panel.second().point(0.12, 1 - motion);
        rod(faces, bend == null ? first : bend.apply(first), bend == null ? second : bend.apply(second));
        return faces;
    }

    private static void blade(List<Quad> faces, SwitchLayout.Blade blade, double opening, @Nullable UnaryOperator<Vec3> bend) {
        int segments = (int) Math.ceil((blade.heel() - blade.toe()) / (bend == null ? 0.13 : 0.075));
        var cuts = new TreeSet<Double>();
        for (int i = 0; i <= segments; i++) cuts.add(i / (double) segments);
        if (bend != null) {
            double start = blade.distance(0);
            double length = blade.distance(1) - start;
            for (var span : blade.path().columns()) {
                double fraction = (span.end() - start) / length;
                if (fraction > 1e-7 && fraction < 1 - 1e-7) cuts.add(fraction);
            }
        }
        var fractions = new ArrayList<>(blade.reverse() ? cuts.descendingSet() : cuts);
        var profile = RailProfile.SECTIONS;
        for (int i = 1; i < fractions.size(); i++) {
            double a = fractions.get(i - 1), b = fractions.get(i);
            Vec3 start = blade.point(a, opening), end = blade.point(b, opening);
            Vec3 startSide = blade.lateral(a).scale(0.19 + 0.81 * a), endSide = blade.lateral(b).scale(0.19 + 0.81 * b);
            if (bend != null) {
                start = bend.apply(start);
                end = bend.apply(end);
            }
            for (int j = 1; j < profile.length; j++) {
                var low = profile[j - 1];
                var high = profile[j];
                faces.add(new Quad(section(start, startSide, low), section(end, endSide, low),
                    section(end, endSide, high), section(start, startSide, high),
                    Math.max(low.height(), high.height()) > 0.229 ? HEAD : WEB));
            }
        }
    }

    private static Vec3 section(Vec3 point, Vec3 lateral, RailProfile.Section profile) {
        return point.add(lateral.scale(profile.across())).add(0, profile.height(), 0);
    }

    private static void rod(List<Quad> faces, Vec3 first, Vec3 second) {
        Vec3 along = second.subtract(first).multiply(1, 0, 1).normalize();
        Vec3 width = new Vec3(-along.z, 0, along.x).scale(0.027);
        Vec3 a = first.add(width).add(0, 0.170, 0), b = second.add(width).add(0, 0.170, 0);
        Vec3 c = second.subtract(width).add(0, 0.170, 0), d = first.subtract(width).add(0, 0.170, 0);
        Vec3 down = new Vec3(0, -0.023, 0);
        faces.add(new Quad(a, b, c, d, WEB));
        faces.add(new Quad(a.add(down), b.add(down), b, a, WEB));
        faces.add(new Quad(d, c, c.add(down), d.add(down), WEB));
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.faces.isEmpty()) return;
        pose.pushPose();
        pose.translate(-state.originOffset.getX(), -state.originOffset.getY(), -state.originOffset.getZ());
        collector.submitCustomGeometry(pose, RenderTypes.entityCutout(STEEL),
            (current, consumer) -> state.faces.forEach(face -> face.draw(current, consumer, state.lightCoords)));
        pose.popPose();
    }
}
