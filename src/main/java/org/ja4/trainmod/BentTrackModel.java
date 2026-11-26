package org.ja4.trainmod;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterBlockStateModels;
import net.neoforged.neoforge.client.model.DelegateBlockStateModel;
import net.neoforged.neoforge.client.model.block.CustomUnbakedBlockStateModel;
import org.jspecify.annotations.Nullable;

final class BentTrackModel extends DelegateBlockStateModel {
    private BentTrackModel(BlockStateModel base) { super(base); }

    static void register(RegisterBlockStateModels event) {
        event.registerModel(Identifier.fromNamespaceAndPath(Trainmod.ID, "bent_track"), Unbaked.CODEC);
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
                             List<BlockStateModelPart> parts) {
        int first = parts.size();
        delegate.collectParts(level, pos, state, random, parts);
        deformParts(state, parts, first, true);
    }

    @Override
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        return TrackBlock.broken(state) ? null : delegate.createGeometryKey(level, pos, state, random);
    }

    static void deformParts(BlockState state, List<BlockStateModelPart> parts, int first, boolean split) {
        if (!TrackBlock.broken(state) || !(state.getBlock() instanceof TrackBlock track)) return;
        var shape = track.shape(state);
        int index = track.index(state);
        if (index >= shape.cells().size()) return;
        BlockPos cell = shape.cells().get(index);
        for (int i = first; i < parts.size(); i++) {
            var original = parts.get(i);
            var quads = new ArrayList<BakedQuad>();
            for (BakedQuad quad : original.getQuads(null)) deform(shape, cell, quad, split, quads);
            for (Direction face : Direction.values()) {
                for (BakedQuad quad : original.getQuads(face)) deform(shape, cell, quad, split, quads);
            }
            parts.set(i, new BentPart(original, quads));
        }
    }

    private static void deform(TrackGeometry.Shape shape, BlockPos cell, BakedQuad quad, boolean split, List<BakedQuad> out) {
        Vec3 base = Vec3.atLowerCornerOf(cell).subtract(0.5, 0, 0.5);
        Vec3 a = local(quad, 0, base), b = local(quad, 1, base), c = local(quad, 2, base), d = local(quad, 3, base);
        // Subdivide the rail length so the deformation remains smooth between the original mesh cuts.
        int columns = split ? subdivisions(Math.max(a.distanceTo(b), d.distanceTo(c))) : 1;
        int rows = split ? subdivisions(Math.max(a.distanceTo(d), b.distanceTo(c))) : 1;
        for (int y = 0; y < rows; y++) {
            double v0 = (double) y / rows, v1 = (double) (y + 1) / rows;
            for (int x = 0; x < columns; x++) {
                double u0 = (double) x / columns, u1 = (double) (x + 1) / columns;
                Vec3 p = TrackDamage.shift(shape, cell, a.lerp(b, u0).lerp(d.lerp(c, u0), v0));
                Vec3 q = TrackDamage.shift(shape, cell, a.lerp(b, u1).lerp(d.lerp(c, u1), v0));
                Vec3 r = TrackDamage.shift(shape, cell, a.lerp(b, u1).lerp(d.lerp(c, u1), v1));
                Vec3 s = TrackDamage.shift(shape, cell, a.lerp(b, u0).lerp(d.lerp(c, u0), v1));
                BakedSurface.remap(out, quad, uv(quad, u0, v0), p, q, r, s, base);
            }
        }
    }

    private static int subdivisions(double length) { return Math.clamp((int) Math.ceil(length / 0.075), 1, 12); }

    private static Vec3 local(BakedQuad quad, int index, Vec3 base) {
        var vertex = quad.position(index);
        return base.add(vertex.x(), vertex.y(), vertex.z());
    }

    private static long uv(BakedQuad quad, double u, double v) {
        long a = quad.packedUV0(), b = quad.packedUV1(), c = quad.packedUV2(), d = quad.packedUV3();
        return UVPair.pack((float) blend(UVPair.unpackU(a), UVPair.unpackU(b), UVPair.unpackU(c), UVPair.unpackU(d), u, v),
            (float) blend(UVPair.unpackV(a), UVPair.unpackV(b), UVPair.unpackV(c), UVPair.unpackV(d), u, v));
    }

    private static double blend(float a, float b, float c, float d, double u, double v) {
        return net.minecraft.util.Mth.lerp(v, net.minecraft.util.Mth.lerp(u, a, b), net.minecraft.util.Mth.lerp(u, d, c));
    }

    private record BentPart(BlockStateModelPart source, List<BakedQuad> quads) implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(@Nullable Direction direction) { return direction == null ? quads : List.of(); }

        @Override
        public boolean useAmbientOcclusion() { return source.useAmbientOcclusion(); }

        @Override
        public Material.Baked particleMaterial() { return source.particleMaterial(); }

        @Override
        public int materialFlags() { return source.materialFlags(); }
    }

    private record Unbaked(List<SingleVariant.Unbaked> models) implements CustomUnbakedBlockStateModel {
        private static final MapCodec<Unbaked> CODEC = SingleVariant.Unbaked.CODEC.listOf(1, CurveBanking.VARIANTS)
            .validate(models -> models.size() == 1 || models.size() == CurveBanking.VARIANTS
                ? DataResult.success(models) : DataResult.error(() -> "Track needs one model or a model for every curve bank"))
            .fieldOf("models").xmap(Unbaked::new, Unbaked::models);

        @Override
        public MapCodec<Unbaked> codec() { return CODEC; }

        @Override
        public BlockStateModel bake(ModelBaker baker) { return new BentTrackModel(BankedCurveModel.bakeVariants(models, baker)); }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            models.forEach(model -> model.resolveDependencies(resolver));
        }
    }
}
