package org.ja4.trainmod;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

record CatenaryOverlay(int offset, boolean complete, List<CatenaryOverlay.Route> routes) implements CustomPacketPayload {
    static final int RADIUS = 32;
    private static final int ROUTES_PER_PACKET = 384;
    static final Type<CatenaryOverlay> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Trainmod.ID, "catenary_overlay"));

    record Route(BlockPos root, int index, List<CatenaryPower.Range> wire) {
        private static final StreamCodec<ByteBuf, CatenaryPower.Range> RANGE_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, CatenaryPower.Range::from, ByteBufCodecs.DOUBLE, CatenaryPower.Range::to,
            ByteBufCodecs.BOOL, CatenaryPower.Range::powered, CatenaryPower.Range::new);
        private static final StreamCodec<ByteBuf, Route> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, Route::root, ByteBufCodecs.VAR_INT, Route::index,
            RANGE_CODEC.apply(ByteBufCodecs.list(128)), Route::wire, Route::new);
    }

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("2").playToClient(TYPE, StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CatenaryOverlay::offset, ByteBufCodecs.BOOL, CatenaryOverlay::complete,
            Route.CODEC.apply(ByteBufCodecs.list(ROUTES_PER_PACKET)), CatenaryOverlay::routes, CatenaryOverlay::new));
    }

    @Override
    public Type<CatenaryOverlay> type() { return TYPE; }

    static boolean holdingPole(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).getItem() instanceof BlockItem item
                && (item.getBlock() instanceof CatenaryTower || item.getBlock() instanceof TransmissionExtension)) return true;
        }
        return false;
    }

    static void send(ServerLevel level) {
        for (var player : level.players()) {
            if (player.isSpectator() || !holdingPole(player) || Math.floorMod(level.getGameTime() + player.getId(), 5) != 0
                || player.connection == null || !NetworkRegistry.hasChannel(player.connection, TYPE.id())) continue;
            var routes = nearby(level, player.position());
            int from = 0;
            do {
                int end = Math.min(from + ROUTES_PER_PACKET, routes.size());
                PacketDistributor.sendToPlayer(player, new CatenaryOverlay(from, end == routes.size(), routes.subList(from, end)));
                from = end;
            } while (from < routes.size());
        }
    }

    static List<Route> nearby(ServerLevel level, Vec3 center) {
        Set<BlockPos> roots = new HashSet<>();
        List<Route> routes = new ArrayList<>();
        BlockPos low = BlockPos.containing(center.add(-RADIUS - 1, -RADIUS - 1, -RADIUS - 1));
        BlockPos high = BlockPos.containing(center.add(RADIUS + 1, RADIUS + 1, RADIUS + 1));
        for (int cx = SectionPos.blockToSectionCoord(low.getX()); cx <= SectionPos.blockToSectionCoord(high.getX()); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(low.getZ()); cz <= SectionPos.blockToSectionCoord(high.getZ()); cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                int minY = Math.max(low.getY(), level.getMinY()), maxY = Math.min(high.getY(), level.getMaxY());
                for (int cy = SectionPos.blockToSectionCoord(minY); cy <= SectionPos.blockToSectionCoord(maxY); cy++) {
                    int index = chunk.getSectionIndexFromSectionY(cy);
                    if (index < 0 || index >= chunk.getSectionsCount()) continue;
                    var section = chunk.getSection(index);
                    if (!section.maybeHas(RailNetwork::isRail)) continue;
                    for (int y = 0; y < 16; y++) {
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                var state = section.getBlockState(x, y, z);
                                if (!RailNetwork.isRail(state)) continue;
                                var cell = new BlockPos(cx * 16 + x, cy * 16 + y, cz * 16 + z);
                                if (cell.distToCenterSqr(center) > (RADIUS + 2) * (RADIUS + 2)) continue;
                                BlockPos root = cell;
                                if (state.getBlock() instanceof TrackBlock track) {
                                    var cells = track.shape(state).cells();
                                    if (track.index(state) >= cells.size()) continue;
                                    root = cell.subtract(cells.get(track.index(state)));
                                }
                                if (!roots.add(root)) continue;
                                for (var rail : RailNetwork.physicalRailsAt(level, root)) {
                                    double nearest = rail.project(center);
                                    if (rail.point(nearest).distanceToSqr(center) <= RADIUS * RADIUS) {
                                        routes.add(new Route(root, rail.route(), CatenaryPower.wireRanges(level, rail)));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return routes;
    }
}
