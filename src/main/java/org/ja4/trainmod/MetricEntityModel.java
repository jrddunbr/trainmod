package org.ja4.trainmod;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Stretches an animated cuboid's geometry and reapplies the material at its original texel density. */
final class MetricEntityModel {
    private record Scale(double y, double z) {}

    private final ModelPart source;
    private final float tileSize;
    private final LoadingCache<Scale, ModelPart> scaled = CacheBuilder.newBuilder().maximumSize(128)
        .build(CacheLoader.from(this::bake));

    MetricEntityModel(ModelPart source, float tileSize) {
        this.source = source;
        this.tileSize = tileSize;
    }

    ModelPart vertical(double height) { return scaled.getUnchecked(new Scale(height, 1)); }
    ModelPart length(double length) { return scaled.getUnchecked(new Scale(1, length)); }

    ModelPart animatedLength(double length) {
        // Moving joints share cached lengths at a finer spacing than one texture pixel.
        return length(Math.max(1, Math.round(length * 512)) / 512.0);
    }

    private ModelPart bake(Scale scale) {
        var cubes = new ArrayList<ModelPart.Cube>();
        source.visit(new PoseStack(), (pose, path, index, sourceCube) -> {
            for (var polygon : sourceCube.polygons) {
                var corners = polygon.vertices();
                Vec3 a = position(pose, corners[0], scale), b = position(pose, corners[1], scale);
                Vec3 c = position(pose, corners[2], scale), d = position(pose, corners[3], scale);
                var face = b.subtract(a).cross(c.subtract(a)).normalize();
                var normal = new Vector3f((float) face.x, (float) face.y, (float) face.z);
                SurfaceUv.face(a, b, c, d, 0, 0, (p, q, r, s) -> {
                    var cube = new ModelPart.Cube(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false,
                        16, 16, Set.of(Direction.UP));
                    cube.polygons[0] = new ModelPart.Polygon(
                        new ModelPart.Vertex[]{vertex(p), vertex(q), vertex(r), vertex(s)}, normal);
                    cubes.add(cube);
                });
            }
        });
        return new ModelPart(cubes, Map.of());
    }

    private static Vec3 position(PoseStack.Pose pose, ModelPart.Vertex vertex, Scale scale) {
        var point = pose.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new Vector3f());
        return new Vec3(point.x, point.y * scale.y, point.z * scale.z);
    }

    private ModelPart.Vertex vertex(SurfaceUv.Vertex vertex) {
        Vec3 p = vertex.point();
        return new ModelPart.Vertex((float) (16 * p.x), (float) (16 * p.y), (float) (16 * p.z),
            (float) vertex.u() * tileSize, (1 - (float) vertex.v()) * tileSize);
    }
}
