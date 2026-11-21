package org.ja4.trainmod;

final class TrestleGeometry {
    static final int HEIGHT = 4;
    static final double LEG_HALF_WIDTH = 0.75;

    static double offset(int depth) {
        return LEG_HALF_WIDTH + depth * 1.625;
    }
}
