package org.ja4.trainmod;

final class SignalCabinGeometry {
    static final double ROOF_OVERHANG = 0.875;
    static final double ROOF_THICKNESS = 0.55;
    private static final double ROOF_SLOPE = Math.tan(Math.toRadians(30));

    private SignalCabinGeometry() {}

    static double roofUnderside(double x) {
        return 23.2 + (8 + ROOF_OVERHANG - Math.abs(x - 8)) * ROOF_SLOPE;
    }
}
