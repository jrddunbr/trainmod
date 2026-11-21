package org.ja4.trainmod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

class TrackBlock extends Block {
    static final IntegerProperty INDEX = IntegerProperty.create("segment", 0, 47);
    static final IntegerProperty ROTATION = BlockStateProperties.ROTATION_16;
    static final EnumProperty<ChestType> HAND = EnumProperty.create("type", ChestType.class,
        ChestType.LEFT, ChestType.RIGHT);
    static final VoxelShape OUTLINE = Block.box(0, 0, 0, 16, 4, 16);
    final TrackGeometry.Kind kind;

    TrackBlock(TrackGeometry.Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        registerDefaultState(defaultBlockState().setValue(INDEX, 0).setValue(HAND, ChestType.LEFT)
            .setValue(ROTATION, kind == TrackGeometry.Kind.DIAGONAL ? 14 : 0));
    }

    BlockState segment(int rotation, boolean left, int index) {
        return defaultBlockState().setValue(ROTATION, rotation)
            .setValue(HAND, left ? ChestType.LEFT : ChestType.RIGHT).setValue(INDEX, index);
    }

    TrackGeometry.Shape shape(BlockState state) {
        return kind.shape(state.getValue(ROTATION), state.getValue(HAND) == ChestType.LEFT);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(INDEX, ROTATION, HAND);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (kind.grade()) {
            var cells = shape(state).cells();
            int index = state.getValue(INDEX);
            if (index < cells.size()) {
                var cell = cells.get(index);
                int run = kind == TrackGeometry.Kind.GRADE_6 ? 6 : 8;
                double rise = Math.min(1, (Math.abs(cell.getX()) + Math.abs(cell.getZ()) + 0.5) / run) - cell.getY();
                return Block.box(0, Math.max(0, rise * 16 - 3), 0, 16, rise * 16 + 4, 16);
            }
        }
        return OUTLINE;
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
        return new net.minecraft.world.item.ItemStack(
            Trainmod.TRACK_ITEMS.get(kind == TrackGeometry.Kind.DIAGONAL ? TrackGeometry.Kind.STRAIGHT : kind).get());
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state,
                                        net.minecraft.world.entity.player.Player player) {
        if (level instanceof ServerLevel server && player instanceof net.minecraft.server.level.ServerPlayer miner) {
            var cells = shape(state).cells();
            int index = state.getValue(INDEX);
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
                    if (!server.mayInteract(miner, segment) || miner.blockActionRestricted(level, segment, miner.gameMode())
                        || net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                            new net.neoforged.neoforge.event.level.block.BreakBlockEvent(level, segment, actual, miner)).isCanceled()) {
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
        return foundation.is(Trainmod.BALLAST.get()) || foundation.isFaceSturdy(level, below, Direction.UP);
    }

    static boolean supported(BlockGetter level, BlockPos pos, boolean highEndOfGrade) {
        return supported(level, pos) || highEndOfGrade && level.getBlockState(pos.below()).canBeReplaced()
            && level.getFluidState(pos.below()).isEmpty() && supported(level, pos.below());
    }

    static final class Turnout extends TrackBlock implements net.minecraft.world.level.block.EntityBlock {
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
            int index = state.getValue(INDEX);
            return index < shape.cells().size() ? SwitchLayout.of(kind, shape).panelAt(shape.cells().get(index)) : null;
        }

        boolean occupied(ServerLevel level, BlockPos pos, BlockState state, SwitchLayout.Panel panel) {
            BlockPos root = pos.subtract(panel.control());
            return kind == TrackGeometry.Kind.SCISSORS ? RailNetwork.panelOccupied(level, root, panel)
                : RailNetwork.turnoutOccupied(level, root, shape(state));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(BlockStateProperties.POWERED);
        }

        @Override
        protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
            if (!level.isClientSide() && panel(state) != null) {
                level.scheduleTick(pos, this, 1);
            }
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
            boolean powered = level.hasNeighborSignal(pos);
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
}
