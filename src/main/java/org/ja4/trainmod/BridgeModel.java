package org.ja4.trainmod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.RegisterBlockStateModels;
import net.neoforged.neoforge.client.model.DelegateBlockStateModel;
import net.neoforged.neoforge.client.model.block.CustomUnbakedBlockStateModel;

final class BridgeModel extends DelegateBlockStateModel {
    private final Map<String, BlockStateModel> models;

    private BridgeModel(Map<String, BlockStateModel> models) {
        super(models.get("base"));
        this.models = models;
    }

    static void register(RegisterBlockStateModels event) {
        event.registerModel(Identifier.fromNamespaceAndPath(Trainmod.ID, "bridge"), Unbaked.CODEC);
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
                             List<BlockStateModelPart> parts) {
        if (!(state.getBlock() instanceof BridgeBlock bridge)) {
            delegate.collectParts(level, pos, state, random, parts);
            return;
        }
        if (bridge.kind == BridgeBlock.Kind.TRESTLE) {
            int depth = bridge.trestleDepth(level, pos);
            models.get(depth == 0 ? "base" : "depth_" + depth).collectParts(level, pos, state, random, parts);
            return;
        }
        if (bridge.kind == BridgeBlock.Kind.STONE && bridge.column(level, pos)
            || bridge.kind.steel() && bridge.girderColumn(level, pos)) {
            models.get("column").collectParts(level, pos, state, random, parts);
            return;
        }
        delegate.collectParts(level, pos, state, random, parts);
        if (bridge.kind.steel() && bridge.girderFeet(level, pos)) {
            models.get("foot").collectParts(level, pos, state, random, parts);
        }
        if (bridge.kind == BridgeBlock.Kind.GIRDER) {
            Direction.Axis axis = state.getValue(BridgeBlock.AXIS);
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (side.getAxis() != axis && level.getBlockState(pos.relative(side)).is(bridge)) {
                    models.get(side.getName()).collectParts(level, pos, state, random, parts);
                }
            }
        } else if (bridge.kind == BridgeBlock.Kind.STONE) {
            int alongX = opening(level, pos, bridge, Direction.Axis.X);
            int alongZ = opening(level, pos, bridge, Direction.Axis.Z);
            if ((alongX > 0) != (alongZ > 0)) {
                int depth = alongX > 0 ? alongX : alongZ;
                models.get("arch_" + depth).collectParts(level, pos, state, random, parts);
            }
        }
    }

    // A deck is an arch only when piers bound its opening and another opening continues on that axis.
    static int opening(BlockAndTintGetter level, BlockPos pos, BridgeBlock bridge, Direction.Axis axis) {
        Direction forward = Direction.get(Direction.AxisDirection.POSITIVE, axis);
        int left = pierDistance(level, pos, bridge, forward.getOpposite());
        int right = pierDistance(level, pos, bridge, forward);
        if (left + right > 9 || left == 0 || right == 0) return 0;
        if (!anotherOpening(level, pos, bridge, forward, right)
            && !anotherOpening(level, pos, bridge, forward.getOpposite(), left)) return 0;
        return Math.min(left, right);
    }

    private static int pierDistance(BlockAndTintGetter level, BlockPos pos, BridgeBlock bridge, Direction direction) {
        for (int distance = 1; distance <= 8; distance++) {
            BlockPos cell = pos.relative(direction, distance);
            if (!level.getBlockState(cell).is(bridge)) return 0;
            if (bridge.column(level, cell)) return distance;
        }
        return 0;
    }

    private static boolean anotherOpening(BlockAndTintGetter level, BlockPos pos, BridgeBlock bridge,
                                          Direction direction, int pier) {
        for (int distance = pier + 1; distance <= 16; distance++) {
            BlockPos cell = pos.relative(direction, distance);
            if (!level.getBlockState(cell).is(bridge)) break;
            if (!bridge.column(level, cell)) return true;
        }
        return false;
    }

    @Override
    public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        return null;
    }

    private record Unbaked(Map<String, SingleVariant.Unbaked> models) implements CustomUnbakedBlockStateModel {
        static final MapCodec<Unbaked> CODEC = Codec.unboundedMap(Codec.STRING, SingleVariant.Unbaked.CODEC)
            .fieldOf("models").xmap(Unbaked::new, Unbaked::models);

        @Override
        public MapCodec<Unbaked> codec() { return CODEC; }

        @Override
        public BlockStateModel bake(ModelBaker baker) {
            Map<String, BlockStateModel> baked = new HashMap<>();
            models.forEach((name, model) -> baked.put(name, model.bake(baker)));
            return new BridgeModel(baked);
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            models.values().forEach(model -> model.resolveDependencies(resolver));
        }
    }
}
