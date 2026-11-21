package org.ja4.trainmod;

final class RailProfile {
    record Section(double across, double height) {}

    static final Section[] SECTIONS = {
        new Section(-0.039, 0.175), new Section(-0.039, 0.189), new Section(-0.014, 0.189),
        new Section(-0.014, 0.223), new Section(-0.031, 0.223), new Section(-0.031, 0.245),
        new Section(-0.025, TrackGeometry.RAIL_HEIGHT), new Section(0.025, TrackGeometry.RAIL_HEIGHT),
        new Section(0.031, 0.245), new Section(0.031, 0.223), new Section(0.014, 0.223),
        new Section(0.014, 0.189), new Section(0.039, 0.189), new Section(0.039, 0.175)
    };

    private RailProfile() {}
}
