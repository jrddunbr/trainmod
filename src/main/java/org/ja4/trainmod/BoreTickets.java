package org.ja4.trainmod;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = Trainmod.ID)
final class BoreTickets {
    private static final TicketController CONTROLLER = new TicketController(
        Identifier.fromNamespaceAndPath(Trainmod.ID, "tunnel_bore"), BoreTickets::restore);
    private static final Map<ServerLevel, Map<UUID, Set<Long>>> LOADED = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<UUID, Set<Long>>> RESTORING = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<UUID, WeakReference<TrainVehicle>>> JOINING = new WeakHashMap<>();

    static void register(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    private static void restore(ServerLevel level, TicketHelper helper) {
        var owners = RESTORING.computeIfAbsent(level, ignored -> new HashMap<>());
        helper.getEntityTickets().forEach((owner, tickets) -> {
            tickets.naturalSpawning().forEach(chunk -> helper.removeTicket(owner, chunk, true));
            if (!tickets.normal().isEmpty()) {
                var chunks = new HashSet<Long>(tickets.normal());
                owners.put(owner, chunks);
                active(level).put(owner, new HashSet<>(chunks));
            }
        });
    }

    @SubscribeEvent
    static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level
            && event.getEntity() instanceof TrainVehicle bore && bore.kind == TrainVehicle.Kind.TUNNEL_BORE) {
            JOINING.computeIfAbsent(level, ignored -> new HashMap<>()).put(bore.getUUID(), new WeakReference<>(bore));
        }
    }

    @SubscribeEvent
    static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var restoring = RESTORING.get(level);
        if (restoring != null) {
            var iterator = restoring.entrySet().iterator();
            while (iterator.hasNext()) {
                var owner = iterator.next();
                // Tickets are activated before the asynchronous entity loads complete.
                if (!owner.getValue().stream().allMatch(level::areEntitiesLoaded)) continue;
                iterator.remove();
                if (!(level.getEntity(owner.getKey()) instanceof TrainVehicle bore)
                    || bore.kind != TrainVehicle.Kind.TUNNEL_BORE) release(level, owner.getKey());
            }
            if (restoring.isEmpty()) RESTORING.remove(level);
        }
        var active = LOADED.get(level);
        var joining = JOINING.remove(level);
        if ((active == null || active.isEmpty()) && joining == null) return;
        Set<UUID> owners = active == null ? new HashSet<>() : new HashSet<>(active.keySet());
        if (joining != null) {
            joining.forEach((owner, reference) -> {
                TrainVehicle bore = reference.get();
                // An entity placed into a loaded, non-ticking chunk is absent from the UUID lookup.
                if (bore != null && bore.isAddedToLevel() && !bore.isRemoved()) {
                    update(level, bore);
                    owners.remove(owner);
                }
            });
        }
        for (UUID owner : owners) {
            if (level.getEntity(owner) instanceof TrainVehicle bore && bore.kind == TrainVehicle.Kind.TUNNEL_BORE) update(level, bore);
        }
    }

    private static void update(ServerLevel level, TrainVehicle bore) {
        Set<Long> next = new HashSet<>();
        addOccupiedChunks(next, bore);
        next.add(ahead(bore.chunkPosition(), bore.position(), Vec3.directionFromRotation(0, bore.getYRot())).pack());
        for (TrainVehicle car : bore.trailingConsist(level)) addOccupiedChunks(next, car);
        var pending = RESTORING.get(level);
        if (pending != null && pending.containsKey(bore.getUUID())) next.addAll(pending.get(bore.getUUID()));
        Set<Long> old = active(level).put(bore.getUUID(), next);
        for (long chunk : next) {
            if (old == null || !old.contains(chunk)) force(level, bore.getUUID(), chunk, true);
        }
        if (old != null) {
            for (long chunk : old) {
                if (!next.contains(chunk)) force(level, bore.getUUID(), chunk, false);
            }
        }
    }

    static void release(ServerLevel level, UUID owner) {
        var pending = RESTORING.get(level);
        if (pending != null) pending.remove(owner);
        Set<Long> old = active(level).remove(owner);
        if (old != null) old.forEach(chunk -> force(level, owner, chunk, false));
    }

    static Set<Long> chunks(ServerLevel level, UUID owner) {
        return Set.copyOf(active(level).getOrDefault(owner, Set.of()));
    }

    private static Map<UUID, Set<Long>> active(ServerLevel level) {
        return LOADED.computeIfAbsent(level, ignored -> new HashMap<>());
    }

    private static void force(ServerLevel level, UUID owner, long packed, boolean add) {
        ChunkPos chunk = ChunkPos.unpack(packed);
        CONTROLLER.forceChunk(level, owner, chunk.x(), chunk.z(), add, false);
    }

    private static void addOccupiedChunks(Set<Long> chunks, TrainVehicle car) {
        var box = car.getBoundingBox();
        int minX = net.minecraft.core.SectionPos.posToSectionCoord(box.minX);
        int minZ = net.minecraft.core.SectionPos.posToSectionCoord(box.minZ);
        int maxX = net.minecraft.core.SectionPos.posToSectionCoord(box.maxX - 1e-6);
        int maxZ = net.minecraft.core.SectionPos.posToSectionCoord(box.maxZ - 1e-6);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) chunks.add(ChunkPos.pack(x, z));
        }
    }

    private static ChunkPos ahead(ChunkPos chunk, Vec3 point, Vec3 heading) {
        double x = exitDistance(point.x, heading.x, chunk.getMinBlockX());
        double z = exitDistance(point.z, heading.z, chunk.getMinBlockZ());
        return new ChunkPos(chunk.x() + (x <= z + 1e-6 ? (int) Math.signum(heading.x) : 0),
            chunk.z() + (z <= x + 1e-6 ? (int) Math.signum(heading.z) : 0));
    }

    private static double exitDistance(double coordinate, double motion, int min) {
        return Math.abs(motion) < 1e-6 ? Double.POSITIVE_INFINITY
            : ((motion > 0 ? min + 16 : min) - coordinate) / motion;
    }
}
