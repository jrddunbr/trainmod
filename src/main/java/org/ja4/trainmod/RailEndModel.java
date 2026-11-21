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
        if (!(state.getBlock() instanceof TrackBlock block)) return;
        var shape = block.shape(state);
        int index = state.getValue(TrackBlock.INDEX);
        if (index >= shape.cells().size()) return;
        BlockPos cell = shape.cells().get(index);
        BlockPos root = pos.subtract(cell);
        var steel = delegate.particleMaterial(level, pos, state);
        var quads = new ArrayList<BakedQuad>();
        for (var port : TrackEnds.at(shape, cell, root)) {
            if (TrackEnds.connection(level, root, port) == TrackEnds.Connection.OPEN) {
                cap(quads, steel, port, pos);
            }
        }
        if (!quads.isEmpty()) parts.add(new EndPart(quads, steel));
    }

    @Override
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        return null;
    }

    private static void cap(List<BakedQuad> quads, Material.Baked steel, TrackEnds.Port port, BlockPos pos) {
        Vec3 out = port.outward();
        Vec3 right = new Vec3(out.z, 0, -out.x);
        Vec3 center = port.center().subtract(Vec3.atLowerCornerOf(pos));
        Direction direction = Direction.getApproximateNearest(out.x, out.y, out.z);
        var material = BakedQuad.MaterialInfo.of(steel, Transparency.NONE, -1, null, 0, false);
        var normal = BakedNormals.of(BakedNormals.pack((float) out.x, 0, (float) out.z));
        var profile = RailProfile.SECTIONS;
        for (double offset : new double[]{-SwitchLayout.GAUGE, SwitchLayout.GAUGE}) {
            Vec3 rail = center.add(right.scale(offset));
            for (int i = 1; i < profile.length / 2; i++) {
                var low = profile[i - 1];
                var high = profile[i];
                if (low.height() == high.height()) continue;
                quads.add(new BakedQuad(vertex(rail, right, low, 1), vertex(rail, right, low, -1),
                    vertex(rail, right, high, -1), vertex(rail, right, high, 1),
                    uv(steel, low, 1), uv(steel, low, -1), uv(steel, high, -1), uv(steel, high, 1),
                    direction, material, normal, BakedColors.DEFAULT));
            }
        }
    }

    private static Vector3f vertex(Vec3 rail, Vec3 right, RailProfile.Section edge, int sign) {
        Vec3 point = rail.add(right.scale(edge.across() * sign)).add(0, edge.height(), 0);
        return new Vector3f((float) point.x, (float) point.y, (float) point.z);
    }

    private static long uv(Material.Baked steel, RailProfile.Section edge, int sign) {
        return UVPair.pack(steel.sprite().getU((float) (0.5 + sign * edge.across() * 10)),
            steel.sprite().getV((float) ((TrackGeometry.RAIL_HEIGHT - edge.height()) * 10)));
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
