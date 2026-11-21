package org.ja4.trainmod;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

record TrainControls(int vehicleId, byte keys) implements CustomPacketPayload {
    static final byte FORWARD = 1;
    static final byte REVERSE = 2;
    static final byte WORK = 4;
    static final Type<TrainControls> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Trainmod.ID, "train_controls"));

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE,
            StreamCodec.composite(ByteBufCodecs.VAR_INT, TrainControls::vehicleId,
                ByteBufCodecs.BYTE, TrainControls::keys, TrainControls::new),
            (packet, context) -> {
                if (context.player() instanceof ServerPlayer player
                    && player.getVehicle() instanceof TrainVehicle vehicle && vehicle.getId() == packet.vehicleId) {
                    vehicle.receiveControls(player, packet.keys);
                }
            });
    }

    @Override
    public Type<TrainControls> type() { return TYPE; }
}
