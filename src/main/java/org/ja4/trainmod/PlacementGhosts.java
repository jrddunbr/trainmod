package org.ja4.trainmod;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

@EventBusSubscriber(modid = Trainmod.ID, value = Dist.CLIENT)
final class PlacementGhosts {
    private static final ContextKey<Preview> PREVIEW =
        new ContextKey<>(Identifier.fromNamespaceAndPath(Trainmod.ID, "placement_preview"));
    private static final int RAIL = 0x84cbdceb;
    private static final int TIE = 0x758a94a5;
    private static final int STAND = 0x8ed2dfea;
    private static final int STAND_CONNECTED = 0x8e9fdfad;
    private static final int MISSING = 0x6cef454d;
    static final int TRANSFORMER = 0xbf96f2a3;
    private static Preview pending;

    private record Preview(Level level, List<Quad> faces) {}

    record Quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
        void draw(PoseStack.Pose pose, VertexConsumer vertices, Vec3 camera) {
            for (Vec3 vertex : List.of(a, b, c, d)) {
                vertices.addVertex(pose, (float) (vertex.x - camera.x), (float) (vertex.y - camera.y),
                    (float) (vertex.z - camera.z)).setColor(color);
            }
        }
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        pending = null;
        if (client.player == null || client.level == null || client.gui.screen() != null || client.player.isSpectator()
            || !(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        for (InteractionHand hand : InteractionHand.values()) {
            var stack = client.player.getItemInHand(hand);
            if (stack.getItem() instanceof TrackItem track) {
                var placement = track.preview(client.level, new UseOnContext(client.player, hand, hit), client.player);
                if (placement != null) {
                    var faces = new ArrayList<Quad>();
                    rails(client.level, faces, track.kind(), placement);
                    placement.missingSupports().forEach(pos -> support(faces, pos));
                    pending = new Preview(client.level, faces);
                }
                return;
            }
            if (stack.getItem() instanceof TransformerBank.Kit transformer) {
                var context = new BlockPlaceContext(client.player, hand, stack, hit);
                var faces = transformerPreview(transformer, context);
                if (!faces.isEmpty()) pending = new Preview(client.level, faces);
                return;
            }
            if (stack.getItem() instanceof BlockItem item && item.getBlock() instanceof RailwayBlocks.Stand stand) {
                var context = new BlockPlaceContext(client.player, hand, stack, hit);
                var pos = context.getClickedPos();
                if (!context.canPlace() || !client.level.isInWorldBounds(pos) || !client.level.mayInteract(client.player, pos)) return;
                boolean supported = TrackBlock.supported(client.level, pos);
                boolean connected = RailwayBlocks.Stand.connection(client.level, pos) != null;
                var state = stand.getStateForPlacement(context);
                var faces = new ArrayList<Quad>();
                double facing = Math.toRadians(-90 * state.getValue(HorizontalDirectionalBlock.FACING).get2DDataValue());
                double target = Math.toRadians(state.getValue(RailwayBlocks.Stand.TARGET).angle);
                Vec3 origin = Vec3.atBottomCenterOf(pos);
                int color = supported && connected ? STAND_CONNECTED : STAND;
                for (var part : SwitchStandModel.base()) cuboid(faces, part, origin, facing, color);
                for (var part : SwitchStandModel.head()) cuboid(faces, part, origin, facing + target, color);
                if (!supported) support(faces, pos.below());
                pending = new Preview(client.level, faces);
                return;
            }
        }
    }

    static List<Quad> transformerPreview(TransformerBank.Kit kit, BlockPlaceContext context) {
        var bounds = kit.connectedPreview(context).keySet().stream().map(AABB::new).reduce(AABB::minmax);
        if (bounds.isEmpty()) return List.of();
        var outline = bounds.get().inflate(0.012);
        List<Quad> faces = new ArrayList<>();
        Vec3 min = new Vec3(outline.minX, outline.minY, outline.minZ);
        Vec3 max = new Vec3(outline.maxX, outline.maxY, outline.maxZ);
        box(faces, min, max, Function.identity(), 0x2296f2a3);
        double edge = 0.035;
        for (double x : new double[]{min.x, max.x - edge}) {
            for (double z : new double[]{min.z, max.z - edge}) {
                box(faces, new Vec3(x, min.y, z), new Vec3(x + edge, max.y, z + edge), Function.identity(), TRANSFORMER);
            }
        }
        for (double y : new double[]{min.y, max.y - edge}) {
            for (double x : new double[]{min.x, max.x - edge}) {
                box(faces, new Vec3(x, y, min.z), new Vec3(x + edge, y + edge, max.z), Function.identity(), TRANSFORMER);
            }
            for (double z : new double[]{min.z, max.z - edge}) {
                box(faces, new Vec3(min.x, y, z), new Vec3(max.x, y + edge, z + edge), Function.identity(), TRANSFORMER);
            }
        }
        return faces;
    }

    @SubscribeEvent
    static void extract(ExtractLevelRenderStateEvent event) {
        var preview = pending;
        if (preview != null && preview.level == event.getLevel()) event.getRenderState().setRenderData(PREVIEW, preview);
    }

    @SubscribeEvent
    static void submit(SubmitCustomGeometryEvent event) {
        var preview = event.getLevelRenderState().getRenderData(PREVIEW);
        if (preview == null) return;
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.debugQuads(),
            (pose, consumer) -> preview.faces.forEach(face -> face.draw(pose, consumer, camera)));
    }

    private static void rails(Level level, List<Quad> faces, TrackGeometry.Kind kind, TrackItem.Placement placement) {
        Vec3 origin = Vec3.atBottomCenterOf(placement.root());
        for (var path : placement.shape().routes()) {
            int bank = kind.bankedCurve()
                ? CurveBanking.connections(level, placement.root(), kind, path, CurveBanking.START | CurveBanking.END) : 0;
            for (double side : new double[]{-SwitchLayout.GAUGE, SwitchLayout.GAUGE}) {
                int steps = Math.max(1, (int) Math.ceil(path.length / 0.20));
                for (int i = 0; i < steps; i++) {
                    double a = path.length * i / steps, b = path.length * (i + 1) / steps;
                    faces.add(new Quad(point(origin, kind, path, bank, a, side - 0.03, 0.253),
                        point(origin, kind, path, bank, b, side - 0.03, 0.253),
                        point(origin, kind, path, bank, b, side + 0.03, 0.253),
                        point(origin, kind, path, bank, a, side + 0.03, 0.253), RAIL));
                }
            }
            if (!kind.hasBallast()) {
                int ties = (int) Math.ceil(path.length / 0.36);
                for (int i = 0; i < ties; i++) {
                    double at = path.length * (i + 0.5) / ties;
                    Vec3 center = origin.add(path.point(at));
                    Vec3 forward = path.tangent(at);
                    Vec3 across = new Vec3(forward.z, 0, -forward.x);
                    box(faces, new Vec3(-0.44, 0, -0.064), new Vec3(0.44, 0.174, 0.064),
                        v -> center.add(across.scale(v.x)).add(0, v.y, 0).add(forward.scale(v.z)), TIE);
                }
                continue;
            }
            for (double at = 0.19; at < path.length - 0.06; at += 0.36) {
                double a = at - 0.065, b = at + 0.065;
                faces.add(new Quad(point(origin, kind, path, bank, a, -0.44, 0.174),
                    point(origin, kind, path, bank, b, -0.44, 0.174),
                    point(origin, kind, path, bank, b, 0.44, 0.174),
                    point(origin, kind, path, bank, a, 0.44, 0.174), TIE));
            }
        }
    }

    private static Vec3 point(Vec3 origin, TrackGeometry.Kind kind, TrackGeometry.Path path, int bank,
                              double at, double left, double height) {
        double cant = kind.bankedCurve() ? CurveBanking.lift(kind, path, at, bank, left) : 0;
        return origin.add(SwitchLayout.railPoint(path, at, left)).add(0, height + cant, 0);
    }

    private static void support(List<Quad> faces, BlockPos pos) {
        Vec3 min = Vec3.atLowerCornerOf(pos).add(0.01, 0.01, 0.01);
        box(faces, min, min.add(0.98, 0.98, 0.98), Function.identity(), MISSING);
    }

    private static void cuboid(List<Quad> faces, VehicleModels.Box part, Vec3 origin, double rotation, int color) {
        Vec3 min = new Vec3(part.x(), part.y(), part.z());
        Vec3 max = min.add(part.width(), part.height(), part.length());
        box(faces, min, max, v -> v.scale(1.0 / 16).yRot((float) rotation).add(origin), color);
    }

    private static void box(List<Quad> faces, Vec3 min, Vec3 max, Function<Vec3, Vec3> transform, int color) {
        Vec3 a = transform.apply(min), b = transform.apply(new Vec3(max.x, min.y, min.z));
        Vec3 c = transform.apply(new Vec3(max.x, min.y, max.z)), d = transform.apply(new Vec3(min.x, min.y, max.z));
        Vec3 e = transform.apply(new Vec3(min.x, max.y, min.z)), f = transform.apply(new Vec3(max.x, max.y, min.z));
        Vec3 g = transform.apply(max), h = transform.apply(new Vec3(min.x, max.y, max.z));
        faces.add(new Quad(a, b, c, d, color));
        faces.add(new Quad(e, h, g, f, color));
        faces.add(new Quad(a, e, f, b, color));
        faces.add(new Quad(b, f, g, c, color));
        faces.add(new Quad(c, g, h, d, color));
        faces.add(new Quad(d, h, e, a, color));
    }
}
