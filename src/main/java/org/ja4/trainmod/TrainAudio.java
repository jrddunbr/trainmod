package org.ja4.trainmod;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

final class TrainAudio {
    private record Key(TrainVehicle vehicle, boolean horn) {}

    private static final Map<Key, Loop> LOOPS = new HashMap<>();

    static void update(Minecraft client) {
        LOOPS.entrySet().removeIf(entry -> {
            Key key = entry.getKey();
            if (audible(client, key) && !entry.getValue().isStopped()) return false;
            entry.getValue().end();
            return true;
        });
        if (client.level == null || client.player == null) return;
        for (var entity : client.level.entitiesForRendering()) {
            if (entity instanceof TrainVehicle vehicle && vehicle.kind.powered
                && vehicle.kind != TrainVehicle.Kind.TUNNEL_BORE && vehicle.isAlive()) {
                startIfNeeded(client, new Key(vehicle, true));
                startIfNeeded(client, new Key(vehicle, false));
            }
        }
    }

    private static boolean audible(Minecraft client, Key key) {
        return client.player != null && key.vehicle.level() == client.level && key.vehicle.isAlive()
            && key.vehicle.distanceToSqr(client.player) < 40 * 40
            && (key.horn ? key.vehicle.hornActive() : key.vehicle.bellActive());
    }

    private static void startIfNeeded(Minecraft client, Key key) {
        if (!audible(client, key) || LOOPS.containsKey(key)) return;
        SoundEvent event = key.horn ? key.vehicle.kind == TrainVehicle.Kind.STEAM_LOCOMOTIVE
            ? Trainmod.WHISTLE.get() : Trainmod.HORN.get() : Trainmod.BELL.get();
        var loop = new Loop(key.vehicle, event, key.horn ? 1.8f : 1.2f);
        LOOPS.put(key, loop);
        client.getSoundManager().play(loop);
    }

    private static final class Loop extends AbstractTickableSoundInstance {
        private final TrainVehicle vehicle;

        Loop(TrainVehicle vehicle, SoundEvent event, float volume) {
            super(event, SoundSource.NEUTRAL, RandomSource.create());
            this.vehicle = vehicle;
            this.volume = volume;
            looping = true;
            tick();
        }

        @Override
        public void tick() {
            if (!vehicle.isAlive()) {
                stop();
            } else {
                x = vehicle.getX();
                y = vehicle.getY();
                z = vehicle.getZ();
            }
        }

        void end() { stop(); }
    }
}
