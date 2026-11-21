package org.ja4.trainmod;

import net.neoforged.neoforge.common.ModConfigSpec;

final class ClientConfig {
    static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue VARIED_RAIL_TIES;

    static {
        var builder = new ModConfigSpec.Builder();
        VARIED_RAIL_TIES = builder.comment("Vary the length and angle of wooden track ties.")
            .define("variedRailTies", true);
        SPEC = builder.build();
    }

    private ClientConfig() {}

    static boolean variedRailTies() {
        return !SPEC.isLoaded() || VARIED_RAIL_TIES.getAsBoolean();
    }
}
