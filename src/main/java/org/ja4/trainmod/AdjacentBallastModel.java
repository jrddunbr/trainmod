package org.ja4.trainmod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
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

final class AdjacentBallastModel extends DelegateBlockStateModel {
    private final boolean foundation;
    private final BlockStateModel joined;

    private AdjacentBallastModel(boolean foundation, BlockStateModel separate, BlockStateModel joined) {
        super(separate);
        this.foundation = foundation;
        this.joined = joined;
    }

    static void register(RegisterBlockStateModels event) {
        event.registerModel(Identifier.fromNamespaceAndPath(Trainmod.ID, "adjacent_ballast"), Unbaked.CODEC);
    }

    private BlockStateModel select(BlockAndTintGetter level, BlockPos pos) {
        return joined != null && level.getBlockState(pos.below()).is(Trainmod.BALLAST.get()) ? joined : delegate;
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
                             List<BlockStateModelPart> parts) {
        var gravel = delegate.particleMaterial(level, pos, state);
        if (foundation) {
            BallastSurface.addFoundation(level, pos, gravel, parts);
        } else {
            select(level, pos).collectParts(level, pos, state, random, parts);
            BallastSurface.addRailEnds(level, pos, state, gravel, parts);
        }
    }

    @Override
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        return null;
    }

    private record Unbaked(boolean foundation, SingleVariant.Unbaked separate,
                           Optional<SingleVariant.Unbaked> joined) implements CustomUnbakedBlockStateModel {
        static final MapCodec<Unbaked> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("foundation", false).forGetter(Unbaked::foundation),
            SingleVariant.Unbaked.CODEC.fieldOf("separate").forGetter(Unbaked::separate),
            SingleVariant.Unbaked.CODEC.optionalFieldOf("joined").forGetter(Unbaked::joined)
        ).apply(instance, Unbaked::new));

        @Override
        public MapCodec<Unbaked> codec() {
            return CODEC;
        }

        @Override
        public BlockStateModel bake(ModelBaker baker) {
            return new AdjacentBallastModel(foundation, separate.bake(baker), joined.map(model -> model.bake(baker)).orElse(null));
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            separate.resolveDependencies(resolver);
            joined.ifPresent(model -> model.resolveDependencies(resolver));
        }
    }
}
