package org.ja4.trainmod;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
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
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;
import net.neoforged.neoforge.client.model.block.CustomUnbakedBlockStateModel;
import org.jspecify.annotations.Nullable;

final class TrackRenderModel implements DynamicBlockStateModel {
    private static final ModelBaker.SharedOperationKey<TrackRenderModel> MODEL = TrackRenderModel::new;
    private static final int MAX_CACHED_QUADS = 180_000;
    private final Map<RailMesh.Surface, BakedQuad.MaterialInfo> materials = new EnumMap<>(RailMesh.Surface.class);
    private final Map<Layout, TiePositions> tiePositions = new ConcurrentHashMap<>();
    // Keep quads for used shapes across chunk builds. The model baker supplies a new cache on every resource reload.
    private final LoadingCache<GeometryKey, Geometry> geometry = CacheBuilder.newBuilder()
        .maximumWeight(MAX_CACHED_QUADS)
        .weigher((GeometryKey key, Geometry value) -> Math.max(1, value.quads()))
        .build(CacheLoader.from(this::generate));
    private final Material.Baked gravel;
    private final Material.Baked wood;
    private final Material.Baked steel;
    private final int flags;

    private enum Part { RAILS, TIES, BALLAST, JOINED_BALLAST }

    private record Layout(TrackGeometry.Kind kind, TrackGeometry.Shape shape) {
        // TrackGeometry supplies interned shapes. Cache lookups can skip hashing their routes and cells.
        @Override
        public int hashCode() { return 31 * kind.ordinal() + System.identityHashCode(shape); }

        @Override
        public boolean equals(Object other) {
            return other instanceof Layout layout && kind == layout.kind && shape == layout.shape;
        }
    }
    private record TiePositions(List<RailMesh.Tie> all, Map<BlockPos, List<RailMesh.Tie>> cells) {}
    private record GeometryKey(Layout layout, int bank, Part part, BlockPos cell, int pattern) {}
    private record Geometry(Map<BlockPos, MeshPart> cells, int quads) {}

    static void register(RegisterBlockStateModels event) {
        event.registerModel(Identifier.fromNamespaceAndPath(Trainmod.ID, "procedural_track"), Unbaked.CODEC);
    }

    private TrackRenderModel(ModelBaker baker) {
        gravel = material(baker, Identifier.withDefaultNamespace("block/gravel"));
        wood = material(baker, Identifier.fromNamespaceAndPath(Trainmod.ID, "block/creosote"));
        steel = material(baker, Identifier.fromNamespaceAndPath(Trainmod.ID, "block/steel_head"));
        var web = material(baker, Identifier.fromNamespaceAndPath(Trainmod.ID, "block/steel_web"));
        materials.put(RailMesh.Surface.STEEL, quadMaterial(steel, null));
        materials.put(RailMesh.Surface.WEB, quadMaterial(web, null));
        materials.put(RailMesh.Surface.TIE, quadMaterial(wood, null));
        materials.put(RailMesh.Surface.BALLAST, quadMaterial(gravel, Direction.UP));
        flags = materials.values().stream().mapToInt(BakedQuad.MaterialInfo::flags).reduce(0, (a, b) -> a | b);
    }

    private static Material.Baked material(ModelBaker baker, Identifier sprite) {
        return baker.materials().get(new Material(sprite), () -> "trainmod:procedural_track");
    }

    private static BakedQuad.MaterialInfo quadMaterial(Material.Baked material, @Nullable Direction shade) {
        return BakedQuad.MaterialInfo.of(material, material.sprite().transparency(), -1, shade, 0, false);
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
                             List<BlockStateModelPart> parts) {
        if (!(state.getBlock() instanceof TrackBlock track)) return;
        var shape = track.shape(state);
        int index = track.index(state);
        if (index >= shape.cells().size()) return;
        BlockPos cell = shape.cells().get(index);
        BlockPos root = pos.subtract(cell);
        var kind = track.kind;
        var layout = new Layout(kind, shape);
        var route = shape.routes().getFirst();
        int bank = kind.bankedCurve() ? CurveBanking.connections(level, root, kind, route, CurveBanking.START | CurveBanking.END) : -1;
        int pattern = ClientConfig.variedRailTies() ? TieVariation.patternAt(pos) : 0;
        int first = parts.size();
        add(parts, new GeometryKey(layout, bank, Part.TIES, cell, pattern), cell);
        BentTrackModel.deformParts(state, parts, first, false);
        first = parts.size();
        add(parts, new GeometryKey(layout, bank, Part.RAILS, BlockPos.ZERO, 0), cell);
        BentTrackModel.deformParts(state, parts, first, true);
        if (TrackBlock.ballasted(state)) {
            first = parts.size();
            boolean raised = level.getBlockState(pos.below()).is(Trainmod.BALLAST.get());
            add(parts, new GeometryKey(layout, bank, raised ? Part.JOINED_BALLAST : Part.BALLAST, BlockPos.ZERO, 0), cell);
            BallastSurface.addRailEnds(level, pos, state, gravel, parts);
            if (kind.bankedCurve() && first < parts.size()) {
                var footing = new CurveBallastGround(level, pos, cell, route, raised);
                for (int i = first; i < parts.size(); i++) parts.set(i, footing.conform(parts.get(i)));
            }
        }
        RailEndModel.addEnds(level, pos, state, steel, parts);
    }

    private void add(List<BlockStateModelPart> parts, GeometryKey key, BlockPos cell) {
        var part = geometry.getUnchecked(key).cells().get(cell);
        if (part != null) parts.add(part);
    }

    private Geometry generate(GeometryKey key) {
        var layout = key.layout();
        var kind = layout.kind();
        var shape = layout.shape();
        var quadsByCell = new HashMap<BlockPos, List<BakedQuad>>();
        var target = new MeshOutput(quadsByCell);
        switch (key.part()) {
            case RAILS -> RailMesh.fixed(kind, shape, key.bank(), ties(layout).all(), target);
            case TIES -> RailMesh.woodTies(kind, shape, ties(layout).cells().getOrDefault(key.cell(), List.of()),
                key.bank(), key.pattern(), target);
            case BALLAST, JOINED_BALLAST -> RailMesh.roadbed(kind, shape, key.part() == Part.JOINED_BALLAST, key.bank(), target);
        }
        var particle = kind.hasBallast() ? gravel : wood;
        var result = new HashMap<BlockPos, MeshPart>();
        int count = 0;
        for (var entry : quadsByCell.entrySet()) {
            var quads = entry.getValue();
            result.put(entry.getKey(), new MeshPart(List.copyOf(quads), particle, flags));
            count += quads.size();
        }
        return new Geometry(Map.copyOf(result), count);
    }

    private TiePositions ties(Layout layout) {
        return tiePositions.computeIfAbsent(layout, key -> {
            var all = RailMesh.ties(key.kind(), key.shape());
            var byCell = new HashMap<BlockPos, List<RailMesh.Tie>>();
            for (var tie : all) byCell.computeIfAbsent(tie.owner(), ignored -> new ArrayList<>()).add(tie);
            return new TiePositions(all, byCell);
        });
    }

    private final class MeshOutput implements RailMesh.QuadSink {
        private final Map<BlockPos, List<BakedQuad>> quads;
        private BlockPos cell;
        private Vec3 origin;
        private List<BakedQuad> current;

        MeshOutput(Map<BlockPos, List<BakedQuad>> quads) { this.quads = quads; }

        @Override
        public void add(BlockPos cell, RailMesh.Surface surface, SurfaceUv.Vertex a, SurfaceUv.Vertex b, SurfaceUv.Vertex c,
                        SurfaceUv.Vertex d) {
            if (!cell.equals(this.cell)) {
                this.cell = cell;
                origin = Vec3.atLowerCornerOf(cell).add(-0.5, 0, -0.5);
                current = quads.computeIfAbsent(cell, ignored -> new ArrayList<>());
            }
            current.add(BakedSurface.quad(a, b, c, d, origin, materials.get(surface), null));
        }
    }

    @Override
    public Material.Baked particleMaterial() { return gravel; }

    @Override
    public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return state.getBlock() instanceof TrackBlock track && !track.kind.hasBallast() ? wood : gravel;
    }

    @Override
    public int materialFlags() { return flags; }

    private record MeshPart(List<BakedQuad> quads, Material.Baked particleMaterial, int materialFlags) implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(@Nullable Direction side) { return side == null ? quads : List.of(); }

        @Override
        public boolean useAmbientOcclusion() { return false; }
    }

    private record Unbaked() implements CustomUnbakedBlockStateModel, BlockStateModel.UnbakedRoot {
        private static final MapCodec<Unbaked> CODEC = MapCodec.unit(new Unbaked());

        @Override
        public MapCodec<Unbaked> codec() { return CODEC; }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {}

        @Override
        public BlockStateModel.UnbakedRoot asRoot() { return this; }

        @Override
        public BlockStateModel bake(ModelBaker baker) { return baker.compute(MODEL); }

        @Override
        public BlockStateModel bake(BlockState state, ModelBaker baker) { return bake(baker); }

        @Override
        public Object visualEqualityGroup(BlockState state) { return state; }
    }
}
