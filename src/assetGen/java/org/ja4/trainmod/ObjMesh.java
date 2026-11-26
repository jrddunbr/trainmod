package org.ja4.trainmod;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class ObjMesh {
    private final BlockPos owner;
    private final boolean shadeQuads;
    private final StringBuilder text;
    private final Map<String, Integer> vertices = new HashMap<>();
    private final Map<String, Integer> textureCoordinates = new HashMap<>();
    private String currentMaterial;

    ObjMesh(BlockPos owner, boolean shadeQuads, String materialLibrary) {
        this.owner = owner;
        this.shadeQuads = shadeQuads;
        text = new StringBuilder("mtllib trainmod:models/block/" + materialLibrary + ".mtl\no " + materialLibrary + "\n");
    }

    boolean shadeQuads() { return shadeQuads; }

    void face(String material, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        SurfaceUv.face(a, b, c, d, 0, 0, (p, q, r, s) -> face(material, p, q, r, s));
    }

    private void face(String material, SurfaceUv.Vertex a, SurfaceUv.Vertex b, SurfaceUv.Vertex c, SurfaceUv.Vertex d) {
        if (!material.equals(currentMaterial)) {
            text.append("usemtl ").append(material).append('\n');
            currentMaterial = material;
        }
        int first = vertex(a.point()), second = vertex(b.point()), third = vertex(c.point()), fourth = vertex(d.point());
        int uva = uv(a.u(), a.v()), uvb = uv(b.u(), b.v()), uvc = uv(c.u(), c.v()), uvd = uv(d.u(), d.v());
        text.append("f ").append(first).append('/').append(uva)
            .append(' ').append(second).append('/').append(uvb)
            .append(' ').append(third).append('/').append(uvc)
            .append(' ').append(fourth).append('/').append(uvd).append('\n');
    }

    private int vertex(Vec3 point) {
        String coordinates = decimal(point.x - owner.getX() + 0.5) + " " + decimal(point.y - owner.getY())
            + " " + decimal(point.z - owner.getZ() + 0.5);
        return vertexIndex(vertices, "v ", coordinates);
    }

    private int uv(double u, double v) {
        return vertexIndex(textureCoordinates, "vt ", decimal(u) + " " + decimal(v));
    }

    private int vertexIndex(Map<String, Integer> indices, String record, String coordinates) {
        return indices.computeIfAbsent(coordinates, key -> {
            text.append(record).append(key).append('\n');
            return indices.size() + 1;
        });
    }

    private static String decimal(double value) {
        return Double.toString(Math.round(value * 100_000) / 100_000.0);
    }

    String contents() { return text.toString(); }
}
