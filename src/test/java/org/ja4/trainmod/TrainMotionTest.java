package org.ja4.trainmod;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TrainMotionTest {
    @Test
    void steadyMotionContinuesWhenPositionUpdatesArriveInBursts() {
        var motion = new TrainMotion();
        Vec3 position = Vec3.ZERO;
        Vec3 velocity = new Vec3(0, 0, 0.24);
        Vec3 authority = Vec3.ZERO;
        for (int tick = 0; tick < 60; tick++) {
            authority = authority.add(velocity);
            if (tick % 6 != 2 && tick % 6 != 3) motion.sample(authority);
            Vec3 next = motion.step(position, velocity);
            if (tick > 12) assertEquals(0.24, next.z - position.z, 0.003, "A short packet gap cannot pause the train");
            position = next;
        }
        assertEquals(authority.z, position.z, 0.003);
    }

    @Test
    void aCorrectedPositionConvergesWithoutJumpingOrChangingTheReportedSpeed() {
        var motion = new TrainMotion();
        Vec3 position = Vec3.ZERO;
        Vec3 authority = Vec3.ZERO;
        Vec3 velocity = new Vec3(0, 0, 0.24);
        for (int tick = 0; tick < 60; tick++) {
            authority = authority.add(velocity);
            if (tick == 20) authority = authority.add(0, 0, 0.85);
            motion.sample(authority);
            Vec3 next = motion.step(position, velocity);
            assertTrue(next.z - position.z < 0.32, "A network correction stays within the movement adjustment limit");
            position = next;
        }
        assertEquals(authority.z, position.z, 0.003);
        motion.sample(authority);
        for (int tick = 0; tick < 20; tick++) position = motion.step(position, Vec3.ZERO);
        assertEquals(authority.z, position.z, 0.003, "Stopped trains settle at their server position");
    }

    @Test
    void predictionIsLimitedDuringAnExtendedGapAndResumesInReverse() {
        var motion = new TrainMotion();
        Vec3 position = Vec3.ZERO;
        Vec3 velocity = new Vec3(0, 0, 0.24);
        motion.sample(position);
        for (int tick = 0; tick < 25; tick++) position = motion.step(position, velocity);
        assertTrue(position.z < 0.8, "Stale velocity cannot keep the train moving indefinitely");

        Vec3 authority = position;
        Vec3 reverse = new Vec3(0, 0, -0.14);
        for (int tick = 0; tick < 40; tick++) {
            authority = authority.add(reverse);
            motion.sample(authority);
            position = motion.step(position, reverse);
        }
        assertEquals(authority.z, position.z, 0.003);
    }
}
