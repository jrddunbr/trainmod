package org.ja4.trainmod;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

@EventBusSubscriber(modid = Trainmod.ID, value = Dist.CLIENT)
final class CatenaryOverlayRenderer {
    static final int POWERED = 0xb0a7efab;
    static final int CONNECTED = 0xb0ffad62;
    static final int MISSING = 0xb0f3a0a5;
    private static final ContextKey<List<PlacementGhosts.Quad>> OVERLAY =
        new ContextKey<>(Identifier.fromNamespaceAndPath(Trainmod.ID, "catenary_status"));

    private record Snapshot(WeakReference<ClientLevel> level, long received, List<CatenaryOverlay.Route> routes) {}
    private static final List<CatenaryOverlay.Route> incoming = new ArrayList<>();
    private static WeakReference<ClientLevel> receivingFrom = new WeakReference<>(null);
    private static Snapshot snapshot;
    private static Snapshot painted;
    private static Vec3 paintedFrom;
    private static List<PlacementGhosts.Quad> paintedFaces = List.of();

    @SubscribeEvent
    static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(CatenaryOverlay.TYPE, (payload, context) -> {
            if (!(context.player().level() instanceof ClientLevel level)) return;
            if (payload.offset() == 0) {
                incoming.clear();
                receivingFrom = new WeakReference<>(level);
            }
            if (receivingFrom.get() != level || payload.offset() != incoming.size()) return;
            incoming.addAll(payload.routes());
            if (payload.complete()) {
                snapshot = new Snapshot(receivingFrom, level.getGameTime(), List.copyOf(incoming));
                incoming.clear();
            }
        });
    }

    @SubscribeEvent
    static void extract(ExtractLevelRenderStateEvent event) {
        if (event.getLevel() != Minecraft.getInstance().level) return;
        var faces = visibleFaces(Minecraft.getInstance());
        if (!faces.isEmpty()) event.getRenderState().setRenderData(OVERLAY, faces);
    }

    static List<PlacementGhosts.Quad> visibleFaces(Minecraft client) {
        var received = snapshot;
        if (received == null || client.player == null || client.level == null || client.player.isSpectator()
            || received.level.get() != client.level || client.level.getGameTime() - received.received > 20
            || !CatenaryOverlay.holdingPole(client.player)) return List.of();
        Vec3 center = client.player.position();
        if (painted != received || !center.equals(paintedFrom)) {
            painted = received;
            paintedFrom = center;
            paintedFaces = faces(client.level, center, received.routes);
        }
        return paintedFaces;
    }

    private static List<PlacementGhosts.Quad> faces(ClientLevel level, Vec3 center, List<CatenaryOverlay.Route> routes) {
        List<PlacementGhosts.Quad> faces = new ArrayList<>();
        for (var route : routes) {
            var state = level.getBlockState(route.root());
            for (var rail : RailNetwork.physicalRailsAt(level, route.root())) {
                if (rail.route() != route.index()) continue;
                double offset = state.getBlock() instanceof TrackBlock track && track.kind.turnout()
                    ? (route.index() % 2 == 0 ? -0.075 : 0.075) : 0;
                double from = 0;
                for (var wire : route.wire()) {
                    ribbon(faces, rail, center, from, wire.from(), offset, MISSING);
                    ribbon(faces, rail, center, wire.from(), wire.to(), offset, wire.powered() ? POWERED : CONNECTED);
                    from = wire.to();
                }
                ribbon(faces, rail, center, from, rail.path().length, offset, MISSING);
            }
        }
        return faces;
    }

    private static void ribbon(List<PlacementGhosts.Quad> faces, RailNetwork.Rail rail, Vec3 center,
                               double from, double to, double offset, int color) {
        from = Math.clamp(from, 0, rail.path().length);
        to = Math.clamp(to, 0, rail.path().length);
        if (to - from < 1e-5) return;
        var points = rail.path().section(from, to).points();
        Vec3 origin = rail.origin().add(0, 0.065, 0);
        for (int i = 1; i < points.size(); i++) {
            Vec3 start = points.get(i - 1).add(origin), end = points.get(i).add(origin);
            Vec3 delta = end.subtract(start), relative = start.subtract(center);
            double midpoint = -relative.dot(delta) / delta.lengthSqr();
            double nearest = relative.add(delta.scale(midpoint)).lengthSqr();
            double radius = CatenaryOverlay.RADIUS;
            if (nearest >= radius * radius) continue;
            double reach = Math.sqrt((radius * radius - nearest) / delta.lengthSqr());
            double a = Math.max(0, midpoint - reach), b = Math.min(1, midpoint + reach);
            if (a >= b) continue;
            Vec3 across = new Vec3(delta.z, 0, -delta.x).normalize();
            Vec3 left = across.scale(offset - 0.055), right = across.scale(offset + 0.055);
            Vec3 first = start.add(delta.scale(a)), last = start.add(delta.scale(b));
            faces.add(new PlacementGhosts.Quad(first.add(left), last.add(left), last.add(right), first.add(right), color));
        }
    }

    @SubscribeEvent
    static void submit(SubmitCustomGeometryEvent event) {
        var faces = event.getLevelRenderState().getRenderData(OVERLAY);
        if (faces == null || faces.isEmpty()) return;
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.debugQuads(),
            (pose, consumer) -> faces.forEach(face -> face.draw(pose, consumer, camera)));
    }
}
