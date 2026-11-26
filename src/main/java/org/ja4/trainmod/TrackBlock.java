package org.ja4.trainmod;

import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

class TrackBlock extends Block {
    enum Condition implements StringRepresentable {
        BALLASTED("ballasted"), UNBALLASTED("unballasted"), BROKEN("broken");

        private final String name;

        Condition(String name) { this.name = name; }

        @Override
        public String getSerializedName() { return name; }
    }

    static final EnumProperty<Condition> CONDITION = EnumProperty.create("condition", Condition.class);
    static final net.minecraft.world.level.block.state.properties.BooleanProperty COMPACT =
        net.minecraft.world.level.block.state.properties.BooleanProperty.create("compact");
    static final net.minecraft.world.level.block.state.properties.BooleanProperty DIAGONAL_LOW =
        net.minecraft.world.level.block.state.properties.BooleanProperty.create("diagonal_low");
    static final IntegerProperty INDEX = IntegerProperty.create("segment", 0, TrackGeometry.Kind.STRAIGHT.segmentLimit() - 1);
    static final IntegerProperty ROTATION = BlockStateProperties.ROTATION_16;
    static final EnumProperty<ChestType> HAND = EnumProperty.create("type", ChestType.class,
        ChestType.LEFT, ChestType.RIGHT);
    static final VoxelShape OUTLINE = Block.box(0, 0, 0, 16, 4, 16);
    private static final VoxelShape BROKEN_OUTLINE = Block.box(-4, 0, -4, 20, 4, 20);
    final TrackGeometry.Kind kind;

    TrackBlock(TrackGeometry.Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        registerDefaultState(defaultBlockState().setValue(indexProperty(), 0).setValue(HAND, ChestType.LEFT)
            .setValue(CONDITION, kind == TrackGeometry.Kind.UNBALLASTED_STRAIGHT ? Condition.UNBALLASTED : Condition.BALLASTED)
            .setValue(ROTATION, kind == TrackGeometry.Kind.DIAGONAL ? 14 : 0));
    }

    BlockState segment(int rotation, boolean left, int index) {
        return segment(rotation, left, index, false);
    }

    BlockState segment(int rotation, boolean left, int index, boolean diagonalLow) {
        return defaultBlockState().setValue(ROTATION, rotation)
            .setValue(HAND, left ? ChestType.LEFT : ChestType.RIGHT).setValue(indexProperty(), index)
            .trySetValue(COMPACT, true).trySetValue(DIAGONAL_LOW, diagonalLow);
    }

    TrackGeometry.Shape shape(BlockState state) {
        return kind.shape(state.getValue(ROTATION), state.getValue(HAND) == ChestType.LEFT,
            state.getOptionalValue(COMPACT).orElse(true), state.getOptionalValue(DIAGONAL_LOW).orElse(false));
    }

    IntegerProperty indexProperty() { return INDEX; }

    int index(BlockState state) { return state.getValue(indexProperty()); }

    static boolean ballasted(BlockState state) {
        return state.getBlock() instanceof TrackBlock track && track.kind.hasBallast()
            && state.getValue(CONDITION) == Condition.BALLASTED;
    }

    static boolean unballasted(BlockState state) {
        return state.getBlock() instanceof TrackBlock track && track.kind != TrackGeometry.Kind.BRIDGE_STRAIGHT
            && state.getValue(CONDITION) == Condition.UNBALLASTED;
    }

    static boolean broken(BlockState state) {
        return state.hasProperty(CONDITION) && state.getValue(CONDITION) == Condition.BROKEN;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(indexProperty(), ROTATION, HAND, CONDITION);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (kind.grade()) {
            var shape = shape(state);
            int index = index(state);
            if (index < shape.cells().size()) {
                var cell = shape.cells().get(index);
                double height;
                if (kind.gradedCurve()) {
                    var path = shape.routes().getFirst();
                    height = path.point(path.project(net.minecraft.world.phys.Vec3.atLowerCornerOf(cell))).y;
                } else {
                    height = Math.min(1, (Math.abs(cell.getX()) + Math.abs(cell.getZ()) + 0.5) / kind.gradeRun());
                }
                double rise = height - cell.getY();
                int edge = broken(state) ? 4 : 0;
                return Block.box(-edge, Math.max(0, rise * 16 - 3), -edge, 16 + edge, Math.max(1, rise * 16 + 4), 16 + edge);
            }
        }
        return broken(state) ? BROKEN_OUTLINE : OUTLINE;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(ROTATION, rotation.rotate(state.getValue(ROTATION), 16));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        if (mirror == Mirror.NONE) {
            return state;
        }
        return state.setValue(ROTATION, mirror.mirror(state.getValue(ROTATION), 16))
            .setValue(HAND, state.getValue(HAND) == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT);
    }

    @Override
    protected net.minecraft.world.item.ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level,
            BlockPos pos, BlockState state, boolean includeData) {
        var kit = switch (kind) {
            case DIAGONAL, UNBALLASTED_STRAIGHT -> TrackGeometry.Kind.STRAIGHT;
            default -> kind;
        };
        return new net.minecraft.world.item.ItemStack(Trainmod.TRACK_ITEMS.get(kit).get());
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        var cells = shape(state).cells();
        int index = index(state);
        if (index < cells.size()) {
            BlockPos root = pos.subtract(cells.get(index));
            for (int i = 0; i < cells.size(); i++) {
                BlockState segment = level.getBlockState(root.offset(cells.get(i)));
                if (broken(segment) && RailNetwork.matches(state, segment, i)) return 0;
            }
        }
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!broken(state)) return InteractionResult.PASS;
        if (level instanceof ServerLevel server && player instanceof ServerPlayer recycler) {
            var offsets = shape(state).cells();
            int index = index(state);
            if (index >= offsets.size()) return InteractionResult.FAIL;
            BlockPos root = pos.subtract(offsets.get(index));
            var segments = new LinkedHashMap<BlockPos, BlockState>();
            for (int i = 0; i < offsets.size(); i++) {
                BlockPos cell = root.offset(offsets.get(i));
                if (!server.isLoaded(cell)) return InteractionResult.FAIL;
                BlockState segment = server.getBlockState(cell);
                if (!RailNetwork.matches(state, segment, i)) continue;
                if (!TrackWork.mayBreak(server, recycler, cell, segment)) return InteractionResult.FAIL;
                segments.put(cell, segment);
            }
            if (segments.get(pos) != state || !TrackWork.removeTrack(server, segments)) return InteractionResult.FAIL;
            if (segments.containsKey(root)) {
                var quantities = new LinkedHashMap<String, Integer>();
                for (String item : TrackMaterials.salvage(kind)) {
                    quantities.merge(item, 1, Integer::sum);
                }
                quantities.forEach((item, count) ->
                    popResource(server, pos, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(item)), count)));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state,
                                        net.minecraft.world.entity.player.Player player) {
        if (level instanceof ServerLevel server && player instanceof net.minecraft.server.level.ServerPlayer miner) {
            var cells = shape(state).cells();
            int index = index(state);
            if (index < cells.size() && cells.size() > 1) {
                BlockPos root = pos.subtract(cells.get(index));
                var remaining = new java.util.ArrayList<BlockPos>();
                boolean permitted = true;
                for (int i = 0; i < cells.size(); i++) {
                    BlockPos segment = root.offset(cells.get(i));
                    if (segment.equals(pos)) {
                        continue;
                    }
                    if (!level.isLoaded(segment)) {
                        permitted = false;
                        break;
                    }
                    BlockState actual = level.getBlockState(segment);
                    if (!RailNetwork.matches(state, actual, i)) {
                        continue;
                    }
                    if (!TrackWork.mayBreak(server, miner, segment, actual)) {
                        permitted = false;
                        break;
                    }
                    remaining.add(segment);
                }
                if (permitted) {
                    if (index != 0 && remaining.contains(root) && !miner.preventsBlockDrops()) {
                        dropResources(level.getBlockState(root), level, root, null, miner, miner.getMainHandItem());
                    }
                    remaining.forEach(segment -> level.removeBlock(segment, false));
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    static boolean supported(BlockGetter level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState foundation = level.getBlockState(below);
        return foundation.is(Trainmod.BALLAST.get()) || BridgeBlock.supportsRail(foundation) || foundation.isFaceSturdy(level, below, Direction.UP);
    }

    static boolean supported(BlockGetter level, BlockPos pos, boolean highEndOfGrade) {
        return supported(level, pos) || highEndOfGrade && level.getBlockState(pos.below()).canBeReplaced()
            && level.getFluidState(pos.below()).isEmpty() && supported(level, pos.below());
    }

    static final class Crossing extends TrackBlock {
        Crossing(TrackGeometry.Kind kind, Properties properties) {
            super(kind, properties);
            registerDefaultState(defaultBlockState().setValue(COMPACT, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(COMPACT);
        }
    }

    static final class RisingCurve extends TrackBlock {
        RisingCurve(TrackGeometry.Kind kind, Properties properties) {
            super(kind, properties);
            registerDefaultState(defaultBlockState().setValue(DIAGONAL_LOW, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(DIAGONAL_LOW);
        }
    }

    static class Turnout extends TrackBlock implements net.minecraft.world.level.block.EntityBlock {
        Turnout(TrackGeometry.Kind kind, Properties properties) {
            super(kind, properties);
            registerDefaultState(defaultBlockState().setValue(BlockStateProperties.POWERED, false));
        }

        @Override
        public SwitchPoints newBlockEntity(BlockPos pos, BlockState state) {
            return panel(state) != null ? new SwitchPoints(pos, state) : null;
        }

        SwitchLayout.Panel panel(BlockState state) {
            var shape = shape(state);
            int index = index(state);
            return index < shape.cells().size() ? SwitchLayout.of(kind, shape).panelAt(shape.cells().get(index)) : null;
        }

        boolean occupied(ServerLevel level, BlockPos pos, BlockState state, SwitchLayout.Panel panel) {
            BlockPos root = pos.subtract(panel.control());
            return RailNetwork.panelOccupied(level, root, panel);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(BlockStateProperties.POWERED);
        }

        @Override
        protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
            if (level instanceof ServerLevel server) {
                var points = panel(state);
                if (points != null) server.scheduleTick(pos, this, 1);
                if (points != (old.is(this) ? panel(old) : null)) RailwayBlocks.Stand.pointsChanged(server, pos);
            }
        }

        @Override
        protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
            if (panel(state) != null) RailwayBlocks.Stand.pointsChanged(level, pos);
        }

        @Override
        protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor,
                                       Orientation orientation, boolean moved) {
            if (!level.isClientSide() && panel(state) != null) {
                level.scheduleTick(pos, this, 1);
            }
        }

        @Override
        protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            refreshPower(level, pos);
        }

        void refreshPower(ServerLevel level, BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            if (!state.is(this)) return;
            var panel = panel(state);
            if (panel == null) return;
            boolean powered = level.hasNeighborSignal(pos)
                || ((state.getValue(ROTATION) & 2) != 0 && RailwayBlocks.Stand.hasRemotePower(level, pos));
            if (powered != state.getValue(BlockStateProperties.POWERED)) {
                if (occupied(level, pos, state, panel)) {
                    level.scheduleTick(pos, this, 2);
                    return;
                }
                level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, powered), Block.UPDATE_ALL);
                RailwayBlocks.Stand.routeChanged(level, pos);
            }
        }
    }

    static final class LongScissors extends Turnout {
        private static final IntegerProperty LONG_INDEX = IntegerProperty.create("segment", 0,
            TrackGeometry.Kind.SCISSORS_LONG.segmentLimit() - 1);

        LongScissors(TrackGeometry.Kind kind, Properties properties) { super(kind, properties); }

        @Override
        IntegerProperty indexProperty() { return LONG_INDEX; }
    }

    static final class ParallelTurnout extends Turnout {
        ParallelTurnout(TrackGeometry.Kind kind, Properties properties) {
            super(kind, properties);
            registerDefaultState(defaultBlockState().setValue(COMPACT, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(COMPACT);
        }
    }
}
