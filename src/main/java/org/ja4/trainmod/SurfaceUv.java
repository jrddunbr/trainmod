package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Projects in block units, then clips at whole texture tiles so sampling stays inside an atlas sprite. */
final class SurfaceUv {
    private static final double EPSILON = 1e-8;

    record Vertex(Vec3 point, double u, double v) {
        double coordinate(boolean across) { return across ? u : v; }

        Vertex lerp(Vertex other, double t) {
            return new Vertex(point.lerp(other.point, t), u + (other.u - u) * t, v + (other.v - v) * t);
        }

        Vertex inTile(int uTile, int vTile) {
            return new Vertex(point, Math.clamp(u - uTile, 0, 1), Math.clamp(v - vTile, 0, 1));
        }
    }

    @FunctionalInterface
    interface Output { void quad(Vertex a, Vertex b, Vertex c, Vertex d); }

    private SurfaceUv() {}

    static void face(Vec3 a, Vec3 b, Vec3 c, Vec3 d, double uStart, double vStart, Output output) {
        Vec3 ab = b.subtract(a), ac = c.subtract(a), ad = d.subtract(a);
        Vec3 normal = ab.cross(ac);
        if (normal.lengthSqr() < 1e-20) normal = ac.cross(ad);
        if (normal.lengthSqr() < 1e-20) return;
        normal = unit(normal);
        if (Math.abs(ad.dot(normal)) > 0.004) {
            // A warped face is two planes. Unfold both triangles at the original edge.
            Vec3 diagonal = unit(ac);
            if (diagonal.lengthSqr() > 0) {
                Vec3 second = unit(ac.cross(ad));
                if (second.lengthSqr() > 0) {
                    projectWithNormal(a, b, c, c, diagonal, uStart, vStart, normal, output);
                    projectWithNormal(a, c, d, d, diagonal, uStart, vStart, second, output);
                    return;
                }
            }
        }
        projectWithNormal(a, b, c, d, ab, uStart, vStart, normal, output);
    }

    private static void projectWithNormal(Vec3 a, Vec3 b, Vec3 c, Vec3 d, Vec3 forward,
                                          double uStart, double vStart, Vec3 normal, Output output) {
        Vec3 along = unit(forward.subtract(normal.scale(forward.dot(normal))));
        if (along.lengthSqr() == 0) along = unit(c.subtract(a));
        Vec3 across = normal.cross(along);
        tile(new Vertex(a, uStart, vStart), vertex(b, a, across, along, uStart, vStart),
            vertex(c, a, across, along, uStart, vStart), vertex(d, a, across, along, uStart, vStart),
            Math.abs(d.subtract(a).dot(normal)) <= EPSILON, output);
    }

    private static Vec3 unit(Vec3 vector) {
        double length = vector.length();
        return length > 1e-10 ? vector.scale(1 / length) : Vec3.ZERO;
    }

    private static Vertex vertex(Vec3 point, Vec3 origin, Vec3 across, Vec3 along, double uStart, double vStart) {
        Vec3 offset = point.subtract(origin);
        return new Vertex(point, uStart + offset.dot(across), vStart + offset.dot(along));
    }

    private static void tile(Vertex a, Vertex b, Vertex c, Vertex d, boolean planar, Output output) {
        int u = firstTile(Math.min(Math.min(a.u, b.u), Math.min(c.u, d.u)));
        int v = firstTile(Math.min(Math.min(a.v, b.v), Math.min(c.v, d.v)));
        if (Math.max(Math.max(a.u, b.u), Math.max(c.u, d.u)) <= u + 1 + EPSILON
            && Math.max(Math.max(a.v, b.v), Math.max(c.v, d.v)) <= v + 1 + EPSILON) {
            output.quad(a.inTile(u, v), b.inTile(u, v), c.inTile(u, v), d.inTile(u, v));
            return;
        }
        if (planar && !c.point.equals(d.point)) {
            tilePolygon(List.of(a, b, c, d), output);
        } else {
            tileTriangle(a, b, c, output);
            tileTriangle(a, c, d, output);
        }
    }

    private static int firstTile(double min) { return (int) Math.floor(min + EPSILON); }
    private static int lastTile(double max) { return (int) Math.floor(max - EPSILON); }

    private static void tileTriangle(Vertex a, Vertex b, Vertex c, Output output) {
        if (b.point.subtract(a.point).cross(c.point.subtract(a.point)).lengthSqr() >= 1e-20) {
            tilePolygon(List.of(a, b, c), output);
        }
    }

    private static void tilePolygon(List<Vertex> vertices, Output output) {
        double minU = vertices.getFirst().u, maxU = minU;
        double minV = vertices.getFirst().v, maxV = minV;
        for (var vertex : vertices) {
            minU = Math.min(minU, vertex.u);
            maxU = Math.max(maxU, vertex.u);
            minV = Math.min(minV, vertex.v);
            maxV = Math.max(maxV, vertex.v);
        }
        for (int u = firstTile(minU); u <= lastTile(maxU); u++) {
            var strip = clip(clip(vertices, true, u, true), true, u + 1, false);
            for (int v = firstTile(minV); v <= lastTile(maxV); v++) {
                var polygon = clip(clip(strip, false, v, true), false, v + 1, false);
                for (int i = 1; i + 1 < polygon.size(); i += 2) {
                    output.quad(polygon.getFirst().inTile(u, v), polygon.get(i).inTile(u, v), polygon.get(i + 1).inTile(u, v),
                        polygon.get(Math.min(i + 2, polygon.size() - 1)).inTile(u, v));
                }
            }
        }
    }

    private static List<Vertex> clip(List<Vertex> vertices, boolean across, int edge, boolean greater) {
        if (vertices.isEmpty()) return vertices;
        var result = new ArrayList<Vertex>(vertices.size() + 1);
        Vertex previous = vertices.getLast();
        double old = previous.coordinate(across) - edge;
        for (Vertex vertex : vertices) {
            double next = vertex.coordinate(across) - edge;
            boolean inside = greater ? next >= 0 : next <= 0;
            if (inside != (greater ? old >= 0 : old <= 0) && next != 0 && old != 0) {
                result.add(previous.lerp(vertex, old / (old - next)));
            }
            if (inside) result.add(vertex);
            previous = vertex;
            old = next;
        }
        return result;
    }
}
