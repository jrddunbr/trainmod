package org.ja4.trainmod;

final class BallastProfile {
    static final double CORE_HALF_WIDTH = 0.5;
    static final double WIDTH = 1.5;
    static final double JOIN_WIDTH = 1.32;
    static final double FOOT = 0.006;
    static final double CREST = 0.136;
    static final double END_LENGTH = 1;
    static final int JOIN_DISTANCE = 3;
    static final int SEARCH_DISTANCE = 8;

    record Section(double across, double height) {}

    static final Section[] BED = {
        new Section(-1.5, FOOT), new Section(-1.32, FOOT), new Section(-1.12, 0.032),
        new Section(-0.91, 0.071), new Section(-0.69, 0.108), new Section(-0.5, 0.133), new Section(-0.48, CREST),
        new Section(0.48, CREST), new Section(0.5, 0.133), new Section(0.69, 0.108), new Section(0.91, 0.071),
        new Section(1.12, 0.032), new Section(1.32, FOOT), new Section(1.5, FOOT)
    };
    private static final Section[] RAISED_BED = {
        new Section(-WIDTH, FOOT), new Section(-CORE_HALF_WIDTH, CREST),
        new Section(CORE_HALF_WIDTH, CREST), new Section(WIDTH, FOOT)
    };

    private BallastProfile() {}

    static Section[] bed(boolean raised) { return raised ? RAISED_BED : BED; }

    static double foundationHeight(double x, double z) {
        double distance = Math.max(Math.abs(x), Math.abs(z));
        return Math.clamp((WIDTH - distance) / (WIDTH - CORE_HALF_WIDTH), 0, 1);
    }

    static double joinHeight(double length, double progress, double across) {
        double sag = 0.06 + 0.055 * (length - 1);
        double tapered = Math.clamp((JOIN_WIDTH - Math.abs(across)) / (JOIN_WIDTH - CORE_HALF_WIDTH), 0, 1);
        return tapered * (0.996 - 2 * sag * Math.min(progress, 1 - progress));
    }
}
