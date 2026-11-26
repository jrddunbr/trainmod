package org.ja4.trainmod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class SurfaceUvTest {
    @Test
    void diagonalSurfacesUseTheirFullLength() {
        Vec3 a = Vec3.ZERO, b = new Vec3(1, 0, 1);
        Vec3 across = new Vec3(-0.4, -0.4, 0.4);
        Vec3 c = b.add(across), d = a.add(across);
        var tiles = new ArrayList<List<SurfaceUv.Vertex>>();
        SurfaceUv.face(a, b, c, d, 0, 0, (p, q, r, s) -> tiles.add(List.of(p, q, r, s)));
        assertTrue(tiles.size() > 1);
        checkTiles(tiles, 0.4 * Math.sqrt(6));
    }

    @Test
    void bankingTransitionUnfoldsBothTrianglesWithoutSkew() {
        Vec3 a = Vec3.ZERO, b = new Vec3(0, 0, 0.8);
        Vec3 c = new Vec3(0.7, 0.13, 0.8), d = new Vec3(0.7, 0.02, 0);
        var tiles = new ArrayList<List<SurfaceUv.Vertex>>();
        SurfaceUv.face(a, b, c, d, 0.41, 0.85, (p, q, r, s) -> tiles.add(List.of(p, q, r, s)));
        checkTiles(tiles, area(a, b, c) + area(a, c, d));
    }

    private static void checkTiles(List<List<SurfaceUv.Vertex>> tiles, double expectedArea) {
        double area = 0;
        for (var tile : tiles) {
            for (var a : tile) {
                assertTrue(a.u() >= 0 && a.u() <= 1 && a.v() >= 0 && a.v() <= 1);
                for (var b : tile) {
                    assertEquals(a.point().distanceTo(b.point()), Math.hypot(a.u() - b.u(), a.v() - b.v()), 1e-8);
                }
            }
            var a = tile.get(0).point();
            var b = tile.get(1).point();
            var c = tile.get(2).point();
            var d = tile.get(3).point();
            area += area(a, b, c) + area(a, c, d);
        }
        assertEquals(expectedArea, area, 1e-8);
    }

    private static double area(Vec3 a, Vec3 b, Vec3 c) {
        return b.subtract(a).cross(c.subtract(a)).length() / 2;
    }
}
