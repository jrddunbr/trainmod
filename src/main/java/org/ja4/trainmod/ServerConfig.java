package org.ja4.trainmod;

import net.neoforged.neoforge.common.ModConfigSpec;

final class ServerConfig {
    static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue RAIL_EXTRUDER_REQUIRES_POWER;

    static {
        var builder = new ModConfigSpec.Builder();
        RAIL_EXTRUDER_REQUIRES_POWER = builder.comment("Require Forge Energy to run the rail extruder.")
            .define("railExtruderRequiresPower", true);
        SPEC = builder.build();
    }

    private ServerConfig() {}

    static boolean railExtruderRequiresPower() {
        return !SPEC.isLoaded() || RAIL_EXTRUDER_REQUIRES_POWER.getAsBoolean();
    }
}
