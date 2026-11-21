package org.ja4.trainmod;

import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

record TieVariation(double length, double yaw) {
    static final int PATTERNS = 5;

    static TieVariation forTie(int pattern, int route, int tie) {
        if (pattern == 0) return new TieVariation(1, 0);
        var random = new SplittableRandom(((long) pattern << 48) ^ ((long) route << 32) ^ tie);
        double length = random.nextDouble(0.95, 1.05);
        double yaw = random.nextInt(4) == 0 ? random.nextDouble(1, 4) * (random.nextBoolean() ? 1 : -1) : 0;
        return new TieVariation(length, yaw);
    }

    static int patternAt(BlockPos pos) {
        return 1 + Math.floorMod(Long.hashCode(Mth.getSeed(pos)), PATTERNS - 1);
    }
}
