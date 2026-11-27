package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

final class Pantographs {
    static final double HALF_HEAD = 0.41;
    private static final double NOMINAL = 3.15;
    private static final double MAXIMUM = 4.55;
    private static final double CONTACT_TOLERANCE = 0.075;
    private static final double[] ROOF = {15.4 / 16, 17.5 / 16};
    private static final double[] LONGITUDINAL = {-0.68, 0.66};

    private Pantographs() {}

    static double roof(int head) { return ROOF[head]; }
    static double longitudinal(int head) { return LONGITUDINAL[head]; }
    static float folded(int head) { return (float) (roof(head) + 0.19); }

    record Pickup(double height, CatenaryPower.Circuit circuit) {
        boolean draw(ServerLevel level, TransactionContext parent) {
            if (circuit == null || circuit.available(level) < CatenaryPower.DRAW) return false;
            try (var transaction = Transaction.open(parent)) {
                if (!circuit.draw(level, transaction)) return false;
                transaction.commit();
                return true;
            }
        }
    }

    static Pickup pickup(ServerLevel level, RailNetwork.Position cursor, VehicleHitboxes.Frame frame, int head) {
        if (cursor == null) return new Pickup(NOMINAL, null);
        double z = longitudinal(head);
        var routes = new LinkedHashSet<RailNetwork.Rail>();
        for (double offset : new double[]{0, -0.3, 0.3}) {
            var near = RailNetwork.advance(level, cursor, z + offset);
            if (near != null) routes.add(near.rail());
        }
        routes.add(cursor.rail());
        List<Pickup> candidates = new ArrayList<>();
        for (var route : routes) {
            for (var contact : CatenaryPower.headContacts(level, route, frame, z)) {
                Vec3 local = frame.local(contact.point());
                if (Math.abs(local.x) <= HALF_HEAD && local.y >= folded(head) && local.y <= MAXIMUM) {
                    candidates.add(new Pickup(local.y, contact.circuit()));
                }
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(pickup -> Math.abs(pickup.height - NOMINAL)))
            .orElseGet(() -> new Pickup(NOMINAL, null));
    }

    static float height(int head, float raised, Pickup pickup) {
        return (float) net.minecraft.util.Mth.lerp(raised, folded(head), pickup.height);
    }

    static boolean touches(int head, float raised, Pickup pickup) {
        return Math.abs(height(head, raised, pickup) - pickup.height) <= CONTACT_TOLERANCE;
    }
}
