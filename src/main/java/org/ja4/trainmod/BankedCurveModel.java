package org.ja4.trainmod;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import com.mojang.serialization.Codec;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.RegisterBlockStateModels;
import net.neoforged.neoforge.client.model.DelegateBlockStateModel;
import net.neoforged.neoforge.client.model.block.CustomUnbakedBlockStateModel;

final class BankedCurveModel extends DelegateBlockStateModel {
    private final List<BlockStateModel> normal;
    private final List<BlockStateModel> raised;
    private final int sides;
    private final boolean grounded;

    private BankedCurveModel(List<BlockStateModel> normal, List<BlockStateModel> raised, int sides, boolean grounded) {
        super(normal.getFirst());
        this.normal = normal;
        this.raised = raised;
        this.sides = sides;
        this.grounded = grounded;
    }

    static void register(RegisterBlockStateModels event) {
        event.registerModel(Identifier.fromNamespaceAndPath(Trainmod.ID, "banked_curve"), Unbaked.CODEC);
    }

    private BlockStateModel select(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        int connections = 0;
        if (sides != 0 && state.getBlock() instanceof TrackBlock track && track.kind.bankedCurve()) {
            var shape = track.shape(state);
            int index = state.getValue(TrackBlock.INDEX);
            if (index < shape.cells().size()) {
                BlockPos root = pos.subtract(shape.cells().get(index));
                connections = CurveBanking.connections(level, root, track.kind, shape.routes().getFirst(), sides);
            }
        }
        var models = !raised.isEmpty() && level.getBlockState(pos.below()).is(Trainmod.BALLAST.get()) ? raised : normal;
        return models.get(connections);
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
                             List<BlockStateModelPart> parts) {
        int firstPart = parts.size();
        select(level, pos, state).collectParts(level, pos, state, random, parts);
        if (grounded && state.getBlock() instanceof TrackBlock track && track.kind.bankedCurve()) {
            BallastSurface.addRailEnds(level, pos, state, delegate.particleMaterial(level, pos, state), parts);
            var shape = track.shape(state);
            int index = state.getValue(TrackBlock.INDEX);
            if (index >= shape.cells().size()) return;
            var footing = new CurveBallastGround(level, pos, shape.cells().get(index), shape.routes().getFirst(),
                level.getBlockState(pos.below()).is(Trainmod.BALLAST.get()));
            for (int i = firstPart; i < parts.size(); i++) parts.set(i, footing.conform(parts.get(i)));
        }
    }

    @Override
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        return grounded ? null : select(level, pos, state).createGeometryKey(level, pos, state, random);
    }

    private record Unbaked(List<SingleVariant.Unbaked> normal, Optional<List<SingleVariant.Unbaked>> raised, boolean grounded)
            implements CustomUnbakedBlockStateModel {
        private static final MapCodec<Unbaked> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            SingleVariant.Unbaked.CODEC.listOf(CurveBanking.VARIANTS, CurveBanking.VARIANTS).fieldOf("models").forGetter(Unbaked::normal),
            SingleVariant.Unbaked.CODEC.listOf(CurveBanking.VARIANTS, CurveBanking.VARIANTS).optionalFieldOf("raised").forGetter(Unbaked::raised),
            Codec.BOOL.optionalFieldOf("grounded", false).forGetter(Unbaked::grounded)
        ).apply(instance, Unbaked::new));

        @Override
        public MapCodec<Unbaked> codec() { return CODEC; }

        @Override
        public BlockStateModel bake(ModelBaker baker) {
            var raisedModels = raised.orElse(List.of());
            int sides = dependentSides(normal) | dependentSides(raisedModels);
            return new BankedCurveModel(normal.stream().map(model -> model.bake(baker)).toList(),
                raisedModels.stream().map(model -> model.bake(baker)).toList(), sides, grounded);
        }

        private static int dependentSides(List<SingleVariant.Unbaked> models) {
            if (models.isEmpty()) {
                return 0;
            }
            int sides = 0;
            for (int i = 0; i < models.size(); i++) {
                if (!models.get(i).equals(models.get(i / 3 * 3))) sides |= CurveBanking.START;
                if (!models.get(i).equals(models.get(i % 3))) sides |= CurveBanking.END;
            }
            return sides;
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            normal.forEach(model -> model.resolveDependencies(resolver));
            raised.ifPresent(models -> models.forEach(model -> model.resolveDependencies(resolver)));
        }
    }

    static BlockStateModel bakeVariants(List<SingleVariant.Unbaked> models, ModelBaker baker) {
        if (models.size() == 1) return models.getFirst().bake(baker);
        return new BankedCurveModel(models.stream().map(model -> model.bake(baker)).toList(), List.of(),
            Unbaked.dependentSides(models), false);
    }
}
