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
        String object = materialLibrary.equals("railway") ? "rail" : materialLibrary;
        text = new StringBuilder("mtllib trainmod:models/block/" + materialLibrary + ".mtl\no " + object + "\n");
        uv(0, 0);
        uv(0, 1);
        uv(1, 1);
        uv(1, 0);
    }

    boolean shadeQuads() { return shadeQuads; }

    void face(String material, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        face(material, a, b, c, d, 1, 2, 3, 4);
    }

    private void face(String material, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int uva, int uvb, int uvc, int uvd) {
        if (!material.equals(currentMaterial)) {
            text.append("usemtl ").append(material).append('\n');
            currentMaterial = material;
        }
        int first = vertex(a), second = vertex(b), third = vertex(c), fourth = vertex(d);
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
