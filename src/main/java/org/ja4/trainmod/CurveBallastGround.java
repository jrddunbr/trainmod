package org.ja4.trainmod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

final class CurveBallastGround {
    private static final double SURFACE_GAP = 0.006;
    private final BlockAndTintGetter level;
    private final BlockPos position;
    private final BlockPos segment;
    private final TrackGeometry.Path path;
    private final int nominalGround;
    private final Map<Long, Integer> heights = new HashMap<>();

    CurveBallastGround(BlockAndTintGetter level, BlockPos position, BlockPos segment, TrackGeometry.Path path,
                        boolean raised) {
        this.level = level;
        this.position = position;
        this.segment = segment;
        this.path = path;
        nominalGround = raised ? -1 : 0;
    }

    BlockStateModelPart conform(BlockStateModelPart source) {
        List<BakedQuad> quads = source.getQuads(null).stream().map(this::conform).toList();
        return new BlockStateModelPart() {
            @Override
            public List<BakedQuad> getQuads(@Nullable Direction side) {
                return side == null ? quads : source.getQuads(side).stream().map(CurveBallastGround.this::conform).toList();
            }

            @Override
            public boolean useAmbientOcclusion() { return source.useAmbientOcclusion(); }

            @Override
            public Material.Baked particleMaterial() { return source.particleMaterial(); }

            @Override
            public int materialFlags() { return source.materialFlags(); }
        };
    }

    private BakedQuad conform(BakedQuad quad) {
        Vector3fc a = conform(quad.position0()), b = conform(quad.position1());
        Vector3fc c = conform(quad.position2()), d = conform(quad.position3());
        if (a == quad.position0() && b == quad.position1() && c == quad.position2() && d == quad.position3()) return quad;
        return new BakedQuad(a, b, c, d, quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3(),
            quad.direction(), quad.materialInfo(), quad.bakedNormals(), quad.bakedColors());
    }

    private Vector3fc conform(Vector3fc vertex) {
        double ground = ground(position.getX() + vertex.x(), position.getZ() + vertex.z());
        if (ground == nominalGround) return vertex;
        Vec3 point = new Vec3(segment.getX() + vertex.x() - 0.5, 0, segment.getZ() + vertex.z() - 0.5);
        double across = Math.sqrt(point.distanceToSqr(path.point(path.project(point))));
        double weight = Mth.clamp((across - 0.48) / (1.29 - 0.48), 0, 1);
        if (vertex.y() <= nominalGround + SURFACE_GAP + 1e-4) weight = 1;
        if (weight == 0) return vertex;
        double smooth = weight * weight * (3 - 2 * weight);
        double height = vertex.y() + (ground - nominalGround) * smooth;
        return new Vector3f(vertex.x(), (float) height, vertex.z());
    }

    private double ground(double x, double z) {
        int baseX = Mth.floor(x - 0.5);
        int baseZ = Mth.floor(z - 0.5);
        double u = x - baseX - 0.5, v = z - baseZ - 0.5;
        return Mth.lerp(v, Mth.lerp(u, top(baseX, baseZ), top(baseX + 1, baseZ)),
            Mth.lerp(u, top(baseX, baseZ + 1), top(baseX + 1, baseZ + 1)));
    }

    private int top(int x, int z) {
        long key = BlockPos.asLong(x, 0, z);
        return heights.computeIfAbsent(key, ignored -> {
            for (int y = -1; y >= -4; y--) {
                BlockPos ground = new BlockPos(x, position.getY() + y, z);
                var state = level.getBlockState(ground);
                if (!state.is(Trainmod.BALLAST.get()) && state.isFaceSturdy(level, ground, Direction.UP)) return y + 1;
            }
            return nominalGround;
        });
    }
}
