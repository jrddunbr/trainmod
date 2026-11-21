package org.ja4.trainmod;

import java.util.HashSet;
import java.util.Set;
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
import net.minecraft.world.phys.Vec3;
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
            return null;
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
        protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor,
                                       net.minecraft.world.level.redstone.Orientation orientation, boolean moved) {
            if (!level.isClientSide()) {
                level.scheduleTick(pos, this, 1);
            }
        }

        @Override
        protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            if (state.getValue(PENDING) && !throwSwitch(level, pos, state)) level.scheduleTick(pos, this, 2);
            updateIndicator(level, pos, level.getBlockState(pos));
        }

        static void routeChanged(ServerLevel level, BlockPos turnout) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = turnout.relative(direction);
                BlockState stand = level.getBlockState(neighbor);
                if (stand.getBlock() instanceof Stand) updateIndicator(level, neighbor, stand);
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
            var turnouts = new java.util.ArrayList<Connection>();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                Connection track = turnoutAt(level, pos.relative(direction));
                if (track == null) continue;
                if (track.turnout.occupied(level, track.position, track.state, track.panel)) return false;
                turnouts.add(track);
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
                .setValue(BlockStateProperties.POWERED, true).setValue(BlockStateProperties.INVERTED, false));
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

        @Override
        protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
            if (!level.isClientSide()) {
                level.scheduleTick(pos, this, 1);
            }
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                                    BlockHitResult hit) {
            if (!level.isClientSide()) {
                BlockState changed = state.cycle(BlockStateProperties.INVERTED);
                level.setBlock(pos, changed, UPDATE_ALL);
                player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable(
                    changed.getValue(BlockStateProperties.INVERTED)
                        ? "message.trainmod.signal_reverse" : "message.trainmod.signal_forward"));
            }
            return InteractionResult.SUCCESS;
        }

        @Override
        protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            boolean occupied = requiresStop(level, pos, state);
            if (occupied != state.getValue(BlockStateProperties.POWERED)) {
                level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, occupied), UPDATE_ALL);
            }
            level.scheduleTick(pos, this, 8);
        }

        @Override
        protected boolean isSignalSource(BlockState state) { return true; }

        @Override
        protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
            return state.getValue(BlockStateProperties.POWERED) && side != state.getValue(FACING).getOpposite() ? 15 : 0;
        }

        private static boolean requiresStop(ServerLevel level, BlockPos pos, BlockState state) {
            BlockPos anchor = pos.relative(state.getValue(FACING));
            RailNetwork.Rail rail = RailNetwork.railAt(level, anchor);
            if (rail == null) {
                return true;
            }
            int direction = state.getValue(BlockStateProperties.INVERTED) ? -1 : 1;
            double start = rail.project(TrackGeometry.origin(anchor));
            if (rail.point(start).distanceToSqr(TrackGeometry.origin(anchor)) > 0.8) {
                return true;
            }
            Set<BlockPos> visited = new HashSet<>();
            for (int hops = 0; hops < 128; hops++) {
                if (!visited.add(rail.root())) {
                    return false;
                }
                double boundary = nextSignal(level, rail, pos, start, direction);
                double end = Double.isNaN(boundary) ? (direction > 0 ? rail.path().length : 0) : boundary;
                if (RailNetwork.occupied(level, rail, start, end)) {
                    return true;
                }
                if (!Double.isNaN(boundary)) {
                    return false;
                }
                if (!rail.open(direction > 0)) return true;
                RailNetwork.Position next = RailNetwork.next(level, rail, direction > 0);
                if (next == null) {
                    Vec3 endpoint = rail.end(direction > 0);
                    Vec3 outward = rail.path().tangent(end).scale(direction * 0.2);
                    BlockPos ahead = BlockPos.containing(endpoint.add(outward.x, 0.0001, outward.z));
                    return !level.isLoaded(ahead) || (RailNetwork.isRail(level.getBlockState(ahead))
                        && RailNetwork.railAt(level, ahead) == null);
                }
                rail = next.rail();
                direction = next.forward();
                start = next.distance();
            }
            return true;
        }

        private static double nextSignal(ServerLevel level, RailNetwork.Rail rail, BlockPos origin,
                                         double start, int direction) {
            double nearest = Double.POSITIVE_INFINITY;
            double result = Double.NaN;
            for (BlockPos cell : rail.cells()) {
                double at = rail.project(TrackGeometry.origin(cell));
                double ahead = (at - start) * direction;
                if (ahead < -0.0001 || ahead >= nearest
                    || rail.point(at).distanceToSqr(TrackGeometry.origin(cell)) > 0.8) {
                    continue;
                }
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    BlockPos target = cell.relative(side);
                    if (target.equals(origin) || !level.isLoaded(target)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(target);
                    if (state.getBlock() instanceof Signal && state.getValue(FACING) == side.getOpposite()
                        && state.getValue(BlockStateProperties.INVERTED) == (direction < 0)) {
                        nearest = ahead;
                        result = at;
                    }
                }
            }
            return result;
        }
    }
}
