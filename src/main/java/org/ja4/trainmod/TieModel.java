package org.ja4.trainmod;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import java.util.List;
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

final class TieModel extends DelegateBlockStateModel {
    private final List<BlockStateModel> patterns;

    private TieModel(List<BlockStateModel> patterns) {
        super(patterns.getFirst());
        this.patterns = patterns;
    }

    static void register(RegisterBlockStateModels event) {
        event.registerModel(Identifier.fromNamespaceAndPath(Trainmod.ID, "track_ties"), Unbaked.CODEC);
    }

    private BlockStateModel select(BlockPos pos) {
        return ClientConfig.variedRailTies() ? patterns.get(TieVariation.patternAt(pos)) : delegate;
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
                             List<BlockStateModelPart> parts) {
        int first = parts.size();
        select(pos).collectParts(level, pos, state, random, parts);
        BentTrackModel.deformParts(state, parts, first, false);
    }

    @Override
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        return TrackBlock.broken(state) ? null : select(pos).createGeometryKey(level, pos, state, random);
    }

    private record Unbaked(List<List<SingleVariant.Unbaked>> patterns) implements CustomUnbakedBlockStateModel {
        private static final MapCodec<Unbaked> CODEC = SingleVariant.Unbaked.CODEC.listOf(1, CurveBanking.VARIANTS)
            .validate(models -> models.size() == 1 || models.size() == CurveBanking.VARIANTS
                ? DataResult.success(models) : DataResult.error(() -> "A tie pattern needs one model or a model for every curve bank"))
            .listOf(TieVariation.PATTERNS, TieVariation.PATTERNS).fieldOf("patterns")
            .xmap(Unbaked::new, Unbaked::patterns);

        @Override
        public MapCodec<Unbaked> codec() { return CODEC; }

        @Override
        public BlockStateModel bake(ModelBaker baker) {
            return new TieModel(patterns.stream().map(models -> BankedCurveModel.bakeVariants(models, baker)).toList());
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            patterns.forEach(models -> models.forEach(model -> model.resolveDependencies(resolver)));
        }
    }
}
