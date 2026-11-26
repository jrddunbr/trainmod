package org.ja4.trainmod;

import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.quad.BakedColors;
import net.neoforged.neoforge.client.model.quad.BakedNormals;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

final class BakedSurface {
    private BakedSurface() {}

    static BakedQuad quad(SurfaceUv.Vertex a, SurfaceUv.Vertex b, SurfaceUv.Vertex c, SurfaceUv.Vertex d,
                         Vec3 origin, BakedQuad.MaterialInfo material, @Nullable Direction facing) {
        return quad(a, b, c, d, origin, material, facing, BakedColors.DEFAULT);
    }

    private static BakedQuad quad(SurfaceUv.Vertex a, SurfaceUv.Vertex b, SurfaceUv.Vertex c, SurfaceUv.Vertex d,
                                  Vec3 origin, BakedQuad.MaterialInfo material, @Nullable Direction facing, BakedColors colors) {
        Vec3 normal = b.point().subtract(a.point()).cross(c.point().subtract(a.point()));
        if (normal.lengthSqr() < 1e-20) normal = c.point().subtract(a.point()).cross(d.point().subtract(a.point()));
        double length = normal.length();
        if (length > 0) normal = normal.scale(1 / length);
        var direction = facing == null ? Direction.getApproximateNearest(normal.x, normal.y, normal.z) : facing;
        var normals = BakedNormals.of(BakedNormals.pack((float) normal.x, (float) normal.y, (float) normal.z));
        var sprite = material.sprite();
        return new BakedQuad(local(a.point(), origin), local(b.point(), origin), local(c.point(), origin), local(d.point(), origin),
            uv(sprite, a), uv(sprite, b), uv(sprite, c), uv(sprite, d), direction, material, normals, colors);
    }

    static long uv(TextureAtlasSprite sprite, SurfaceUv.Vertex vertex) {
        return UVPair.pack(sprite.getU((float) vertex.u()), sprite.getV(1 - (float) vertex.v()));
    }

    static void remap(List<BakedQuad> quads, BakedQuad source, long startUv, Vec3 a, Vec3 b, Vec3 c, Vec3 d, Vec3 origin) {
        var sprite = source.materialInfo().sprite();
        double u = (UVPair.unpackU(startUv) - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
        double v = 1 - (UVPair.unpackV(startUv) - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
        SurfaceUv.face(a, b, c, d, u, v, (p, q, r, s) ->
            quads.add(quad(p, q, r, s, origin, source.materialInfo(), source.direction(), source.bakedColors())));
    }

    private static Vector3f local(Vec3 point, Vec3 origin) {
        return new Vector3f((float) (point.x - origin.x), (float) (point.y - origin.y), (float) (point.z - origin.z));
    }
}
