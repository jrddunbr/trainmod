package org.ja4.trainmod;

import com.mojang.blaze3d.platform.Transparency;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
import net.neoforged.neoforge.client.model.quad.BakedColors;
import net.neoforged.neoforge.client.model.quad.BakedNormals;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

final class RailEndModel extends DelegateBlockStateModel {
    private RailEndModel(BlockStateModel base) { super(base); }

    static void register(RegisterBlockStateModels event) {
        event.registerModel(Identifier.fromNamespaceAndPath(Trainmod.ID, "rail_ends"), Unbaked.CODEC);
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
                             List<BlockStateModelPart> parts) {
        addEnds(level, pos, state, delegate.particleMaterial(level, pos, state), parts);
    }

    static void addEnds(BlockAndTintGetter level, BlockPos pos, BlockState state, Material.Baked steel,
                        List<BlockStateModelPart> parts) {
        if (!(state.getBlock() instanceof TrackBlock block)) return;
        var shape = block.shape(state);
        int index = block.index(state);
        if (index >= shape.cells().size()) return;
        BlockPos cell = shape.cells().get(index);
        BlockPos root = pos.subtract(cell);
        var sections = TrackEnds.sections(shape, cell);
        if (sections.isEmpty()) return;
        var layout = SwitchLayout.of(block.kind, shape);
        var quads = new ArrayList<BakedQuad>();
        Vec3 modelOrigin = Vec3.atBottomCenterOf(root).subtract(Vec3.atLowerCornerOf(pos));
        for (var section : sections) {
            var path = shape.routes().get(section.route());
            boolean capStart = section.previous() < 0 && exposed(level, root, path, section.start(), false);
            boolean capEnd = section.next() < 0 && exposed(level, root, path, section.end(), true);
            if (!capStart && !capEnd) continue;
            int bank = block.kind.bankedCurve()
                ? CurveBanking.connections(level, root, block.kind, path, CurveBanking.START | CurveBanking.END) : 0;
            for (double side : new double[]{-SwitchLayout.GAUGE, SwitchLayout.GAUGE}) {
                double start = layout.fixedStart(section.route(), side);
                double end = layout.fixedEnd(section.route(), side, path.length);
                if (capStart && section.start() >= start - 1e-7 && section.start() < end - 1e-7) {
                    cap(quads, steel, profile(modelOrigin, block.kind, path, bank, section.start(), side), false);
                }
                if (capEnd && section.end() > start + 1e-7 && section.end() <= end + 1e-7) {
                    cap(quads, steel, profile(modelOrigin, block.kind, path, bank, section.end(), side), true);
                }
            }
        }
        if (!quads.isEmpty()) parts.add(new EndPart(quads, steel));
    }

    @Override
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        return null;
    }

    private static boolean exposed(BlockAndTintGetter level, BlockPos root, TrackGeometry.Path path, double at, boolean forward) {
        Vec3 out = path.tangent(at).multiply(1, 0, 1).normalize().scale(forward ? 1 : -1);
        return TrackEnds.connection(level, root, new TrackEnds.Port(Vec3.atBottomCenterOf(root).add(path.point(at)), out))
            == TrackEnds.Connection.OPEN;
    }

    private record Profile(Vec3 center, Vec3 lateral, double slope) {
        Vec3 vertex(RailProfile.Section edge) {
            return center.add(lateral.scale(edge.across())).add(0, edge.height() + slope * edge.across(), 0);
        }
    }

    private static Profile profile(Vec3 origin, TrackGeometry.Kind kind, TrackGeometry.Path path, int bank,
                                   double at, double side) {
        double slope = kind.bankedCurve() ? CurveBanking.slope(kind, path, at, bank) : 0;
        Vec3 lateral = SwitchLayout.lateral(path.tangent(at));
        Vec3 center = origin.add(path.point(at)).add(lateral.scale(side))
            .add(0, Math.abs(slope) * CurveBanking.GAUGE / 2 + slope * side, 0);
        return new Profile(center, lateral, slope);
    }

    private static void cap(List<BakedQuad> quads, Material.Baked steel, Profile profile, boolean forward) {
        var edges = RailProfile.SECTIONS;
        for (int i = 1; i < edges.length / 2; i++) {
            if (edges[i - 1].height() == edges[i].height()) continue;
            Vec3 a = profile.vertex(edges[i - 1]), b = profile.vertex(edges[edges.length - i]);
            Vec3 c = profile.vertex(edges[edges.length - 1 - i]), d = profile.vertex(edges[i]);
            if (forward) quad(quads, steel, a, b, c, d);
            else quad(quads, steel, b, a, d, c);
        }
    }

    private static void quad(List<BakedQuad> quads, Material.Baked texture, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        Vec3 face = b.subtract(a).cross(d.subtract(a)).normalize();
        Direction direction = Direction.getApproximateNearest(face.x, face.y, face.z);
        var material = BakedQuad.MaterialInfo.of(texture, Transparency.NONE, -1, null, 0, false);
        var normal = BakedNormals.of(BakedNormals.pack((float) face.x, (float) face.y, (float) face.z));
        var sprite = texture.sprite();
        quads.add(new BakedQuad(vertex(a), vertex(b), vertex(c), vertex(d),
            UVPair.pack(sprite.getU(0), sprite.getV(1)), UVPair.pack(sprite.getU(1), sprite.getV(1)),
            UVPair.pack(sprite.getU(1), sprite.getV(0)), UVPair.pack(sprite.getU(0), sprite.getV(0)),
            direction, material, normal, BakedColors.DEFAULT));
    }

    private static Vector3f vertex(Vec3 point) {
        return new Vector3f((float) point.x, (float) point.y, (float) point.z);
    }

    private record EndPart(List<BakedQuad> quads, Material.Baked particleMaterial) implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(@Nullable Direction direction) { return direction == null ? quads : List.of(); }

        @Override
        public int materialFlags() { return 0; }

        @Override
        public boolean useAmbientOcclusion() { return false; }
    }

    private record Unbaked(SingleVariant.Unbaked base) implements CustomUnbakedBlockStateModel {
        private static final MapCodec<Unbaked> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            SingleVariant.Unbaked.CODEC.fieldOf("base").forGetter(Unbaked::base)
        ).apply(instance, Unbaked::new));

        @Override
        public MapCodec<Unbaked> codec() { return CODEC; }

        @Override
        public BlockStateModel bake(ModelBaker baker) { return new RailEndModel(base.bake(baker)); }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) { base.resolveDependencies(resolver); }
    }
}
