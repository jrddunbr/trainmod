package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class RailwayBlocks {
    private RailwayBlocks() {}

    static final class Ballast extends Block {
        private static final VoxelShape EMBANKMENT = Shapes.or(Shapes.block(),
            Block.box(-8, 0, -8, 24, 12, 24),
            Block.box(-12, 0, -12, 28, 8, 28),
            Block.box(-16, 0, -16, 32, 4, 32));

        Ballast(Properties properties) {
            super(properties);
            registerDefaultState(defaultBlockState().setValue(TrackBlock.ROTATION, 0));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(TrackBlock.ROTATION);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState().setValue(TrackBlock.ROTATION, heading(context.getRotation()));
        }

        static int heading(float yaw) { return Math.floorMod(Math.round(yaw / 45) * 2, 16); }

        @Override
        protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return EMBANKMENT;
        }

        @Override
        protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
            return Shapes.block();
        }

        @Override
        protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
            return state.setValue(TrackBlock.ROTATION, rotation.rotate(state.getValue(TrackBlock.ROTATION), 16));
        }

        @Override
        protected BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
            return state.setValue(TrackBlock.ROTATION, mirror.mirror(state.getValue(TrackBlock.ROTATION), 16));
        }
    }

    enum RouteTarget implements net.minecraft.util.StringRepresentable {
        STRAIGHT(0), LEFT(-90), RIGHT(90);

        final int angle;

        RouteTarget(int angle) { this.angle = angle; }

        @Override
        public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    static final class Stand extends HorizontalDirectionalBlock implements net.minecraft.world.level.block.EntityBlock {
        static final net.minecraft.world.level.block.state.properties.BooleanProperty PENDING =
            net.minecraft.world.level.block.state.properties.BooleanProperty.create("pending");
        static final net.minecraft.world.level.block.state.properties.EnumProperty<RouteTarget> TARGET =
            net.minecraft.world.level.block.state.properties.EnumProperty.create("target", RouteTarget.class);
        private static final VoxelShape OUTLINE = Shapes.or(Block.box(3, 0, 3, 13, 3, 13),
            Block.box(7, 3, 7, 9, 15, 9), Block.box(2, 11, 7, 14, 17, 9),
            Block.box(7, 11, 2, 9, 17, 14));
        private static final List<BlockPos> EXTRA_REACH = List.of(new BlockPos(0, 0, -2), new BlockPos(0, 0, 2),
            new BlockPos(-2, 0, 0), new BlockPos(2, 0, 0), new BlockPos(-1, 0, -1), new BlockPos(1, 0, -1),
            new BlockPos(-1, 0, 1), new BlockPos(1, 0, 1));

        Stand(Properties properties) {
            super(properties);
            registerDefaultState(defaultBlockState().setValue(FACING, Direction.SOUTH)
                .setValue(TARGET, RouteTarget.STRAIGHT).setValue(BlockStateProperties.POWERED, false).setValue(PENDING, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING, TARGET, BlockStateProperties.POWERED, PENDING);
        }

        @Override
        public SwitchStand newBlockEntity(BlockPos pos, BlockState state) { return new SwitchStand(pos, state); }

        record Connection(BlockPos position, TrackBlock.Turnout turnout, BlockState state, SwitchLayout.Panel panel) {
            boolean diagonal() { return (state.getValue(TrackBlock.ROTATION) & 2) != 0; }

            Direction facing() {
                Direction approach = Direction.from2DDataValue(state.getValue(TrackBlock.ROTATION) / 4);
                return panel.first().reverse() ? approach.getOpposite() : approach;
            }

            RouteTarget route(boolean powered) {
                if (turnout.kind.wye()) return powered ? RouteTarget.RIGHT : RouteTarget.LEFT;
                if (!powered) return RouteTarget.STRAIGHT;
                return panel.divergesLeft() ? RouteTarget.LEFT : RouteTarget.RIGHT;
            }
        }

        static Connection connection(BlockGetter level, BlockPos pos) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                Connection found = turnoutAt(level, pos.relative(direction));
                if (found != null) return found;
            }
            for (BlockPos offset : EXTRA_REACH) {
                Connection found = turnoutAt(level, pos.offset(offset));
                if (found != null && found.diagonal()) return found;
            }
            return null;
        }

        static boolean hasRemotePower(ServerLevel level, BlockPos turnout) {
            for (BlockPos offset : EXTRA_REACH) {
                BlockPos pos = turnout.offset(offset);
                BlockState stand = level.getBlockState(pos);
                if (!(stand.getBlock() instanceof Stand) || !stand.getValue(BlockStateProperties.POWERED)) continue;
                Connection track = connection(level, pos);
                if (track != null && track.position.equals(turnout)) return true;
            }
            return false;
        }

        private static Connection turnoutAt(BlockGetter level, BlockPos railPos) {
            BlockState rail = level.getBlockState(railPos);
            if (rail.getBlock() instanceof TrackBlock.Turnout turnout) {
                var panel = turnout.panel(rail);
                if (panel != null) return new Connection(railPos, turnout, rail, panel);
            }
            return null;
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            Connection track = connection(context.getLevel(), context.getClickedPos());
            if (track == null) return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
            return defaultBlockState().setValue(FACING, track.facing())
                .setValue(TARGET, track.route(track.state.getValue(BlockStateProperties.POWERED)));
        }

        @Override
        protected boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
            return TrackBlock.supported(level, pos);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return OUTLINE;
        }

        @Override
        protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
            if (!level.isClientSide() && !old.is(this)) {
                level.scheduleTick(pos, this, 1);
            }
        }

        @Override
        protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
            if (state.getValue(BlockStateProperties.POWERED)) scheduleRemoteTurnouts(level, pos);
        }

        private static void scheduleRemoteTurnouts(ServerLevel level, BlockPos pos) {
            for (BlockPos offset : EXTRA_REACH) {
                Connection track = turnoutAt(level, pos.offset(offset));
                if (track != null && track.diagonal()) level.scheduleTick(track.position, track.turnout, 1);
            }
        }

        @Override
        protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor,
                                       net.minecraft.world.level.redstone.Orientation orientation, boolean moved) {
            if (!level.isClientSide()) {
                level.scheduleTick(pos, this, 1);
            }
        }

        @Override
        protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            if (state.getValue(PENDING) && !throwSwitch(level, pos, state)) level.scheduleTick(pos, this, 2);
            if (state.getValue(BlockStateProperties.POWERED)) scheduleRemoteTurnouts(level, pos);
            updateIndicator(level, pos, level.getBlockState(pos));
        }

        static void pointsChanged(ServerLevel level, BlockPos turnout) {
            for (Direction direction : Direction.Plane.HORIZONTAL) scheduleStand(level, turnout.relative(direction));
            for (BlockPos offset : EXTRA_REACH) scheduleStand(level, turnout.offset(offset));
        }

        private static void scheduleStand(ServerLevel level, BlockPos pos) {
            if (level.getBlockState(pos).getBlock() instanceof Stand stand) level.scheduleTick(pos, stand, 1);
        }

        static void routeChanged(ServerLevel level, BlockPos turnout) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = turnout.relative(direction);
                updateIndicator(level, neighbor, level.getBlockState(neighbor));
            }
            for (BlockPos offset : EXTRA_REACH) {
                BlockPos remote = turnout.offset(offset);
                updateIndicator(level, remote, level.getBlockState(remote));
            }
        }

        private static void updateIndicator(ServerLevel level, BlockPos pos, BlockState state) {
            if (!(state.getBlock() instanceof Stand)) return;
            Connection track = connection(level, pos);
            boolean powered = state.getValue(BlockStateProperties.POWERED);
            RouteTarget target = powered ? RouteTarget.LEFT : RouteTarget.STRAIGHT;
            if (track != null) {
                state = state.setValue(FACING, track.facing());
                target = track.route(track.state.getValue(BlockStateProperties.POWERED));
            }
            BlockState updated = state.setValue(TARGET, target);
            if (updated != level.getBlockState(pos)) level.setBlock(pos, updated, UPDATE_CLIENTS);
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                                    BlockHitResult hit) {
            if (level instanceof ServerLevel server) {
                if (state.getValue(PENDING)) {
                    server.setBlock(pos, state.setValue(PENDING, false), UPDATE_CLIENTS);
                } else if (!throwSwitch(server, pos, state)) {
                    server.setBlock(pos, state.setValue(PENDING, true), UPDATE_CLIENTS);
                    server.scheduleTick(pos, this, 2);
                }
            }
            return InteractionResult.SUCCESS;
        }

        private static boolean throwSwitch(ServerLevel level, BlockPos pos, BlockState state) {
            var turnouts = new ArrayList<Connection>();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                Connection track = turnoutAt(level, pos.relative(direction));
                if (track != null) turnouts.add(track);
            }
            if (turnouts.isEmpty()) {
                Connection track = connection(level, pos);
                if (track != null) turnouts.add(track);
            }
            for (Connection track : turnouts) {
                if (track.turnout.occupied(level, track.position, track.state, track.panel)) return false;
            }

            BlockState changed = state.cycle(BlockStateProperties.POWERED).setValue(PENDING, false);
            level.setBlock(pos, changed, UPDATE_ALL);
            // The turnout samples the new output in this tick, before either renderer starts its throw.
            for (Connection track : turnouts) track.turnout.refreshPower(level, track.position);
            updateIndicator(level, pos, level.getBlockState(pos));
            return true;
        }

        @Override
        protected boolean isSignalSource(BlockState state) { return true; }

        @Override
        protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
            return state.getValue(BlockStateProperties.POWERED) ? 15 : 0;
        }
    }

    static final class Signal extends HorizontalDirectionalBlock {
        private static final VoxelShape OUTLINE = Shapes.or(Block.box(3, 0, 3, 13, 2, 13),
            Block.box(7, 2, 7, 9, 14, 9), Block.box(5, 9, 5, 11, 16, 11));

        Signal(Properties properties) {
            super(properties);
            registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH)
                .setValue(BlockStateProperties.POWERED, false).setValue(BlockStateProperties.INVERTED, false));
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            Direction face = context.getClickedFace();
            return defaultBlockState().setValue(FACING, face.getAxis().isHorizontal()
                ? face.getOpposite() : context.getHorizontalDirection());
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING, BlockStateProperties.POWERED, BlockStateProperties.INVERTED);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return OUTLINE;
        }

    }
}
