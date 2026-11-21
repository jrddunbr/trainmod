package org.ja4.trainmod;

import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class BufferStop extends Block {
    static final double CARDINAL_FACE = 15.98;
    static final double DIAGONAL_FACE = 8 + 8 * Math.sqrt(2) - 0.02;
    static final double BLOCK_BEAM_BOTTOM = 16 * TrackGeometry.RAIL_HEIGHT + VehicleModels.COUPLER_BOTTOM;
    static final double BLOCK_BEAM_TOP = 16 * TrackGeometry.RAIL_HEIGHT + VehicleModels.COUPLER_TOP;
    private static final VoxelShape[] SHAPES = new VoxelShape[8];

    static {
        for (int direction = 0; direction < SHAPES.length; direction++) {
            SHAPES[direction] = shape(direction);
        }
    }

    BufferStop(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(TrackBlock.ROTATION, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TrackBlock.ROTATION);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return TrackBlock.supported(level, pos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[((state.getValue(TrackBlock.ROTATION) + 1) & 15) / 2];
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(TrackBlock.ROTATION, rotation.rotate(state.getValue(TrackBlock.ROTATION), 16));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(TrackBlock.ROTATION, mirror.mirror(state.getValue(TrackBlock.ROTATION), 16));
    }

    private static VoxelShape shape(int direction) {
        double angle = direction * Math.PI / 4;
        double face = direction % 2 == 0 ? CARDINAL_FACE : DIAGONAL_FACE;
        VoxelShape base = turnedBox(2, 0, 2, 14, 3, face - 2, angle);
        VoxelShape beam = Shapes.empty();
        for (int slice = 2; slice < 14; slice += 2) {
            beam = Shapes.or(beam, turnedBox(slice, BLOCK_BEAM_BOTTOM, face - 3, slice + 2, BLOCK_BEAM_TOP,
                face, angle));
        }
        return Shapes.or(base, beam, turnedBox(3, 3, face - 6, 6, BLOCK_BEAM_BOTTOM + 1, face - 3, angle),
            turnedBox(10, 3, face - 6, 13, BLOCK_BEAM_BOTTOM + 1, face - 3, angle));
    }

    private static VoxelShape turnedBox(double x1, double y1, double z1, double x2, double y2, double z2, double angle) {
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        double x = (x1 + x2) / 2 - 8;
        double z = (z1 + z2) / 2 - 8;
        double width = (x2 - x1) / 2;
        double depth = (z2 - z1) / 2;
        double cx = 8 + x * cos - z * sin;
        double cz = 8 + x * sin + z * cos;
        double dx = Math.abs(cos) * width + Math.abs(sin) * depth;
        double dz = Math.abs(sin) * width + Math.abs(cos) * depth;
        return box(cx - dx, y1, cz - dz, cx + dx, y2, cz + dz);
    }

    static final class Item extends net.minecraft.world.item.Item {
        Item(Properties properties) { super(properties); }

        @Override
        public InteractionResult useOn(UseOnContext context) {
            if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
            var player = context.getPlayer();
            if (player == null) return InteractionResult.FAIL;
            BlockPos cell = context.getClickedPos();
            Vec3 hit = context.getClickLocation();
            Vec3 view = Vec3.directionFromRotation(0, player.getYRot());
            AABB nearby = new AABB(cell).inflate(0.06, 0.5, 0.06);
            var position = RailNetwork.physicalRailsAt(level, cell).stream()
                .map(rail -> RailNetwork.onRail(rail, hit, view))
                .filter(rail -> nearby.contains(rail.point()))
                .min(Comparator.comparingDouble(rail -> rail.point().distanceToSqr(hit)
                    - 0.02 * Math.abs(rail.tangent().dot(view))));
            if (position.isPresent() && level.mayInteract(player, cell)) {
                var stop = new Mounted(Trainmod.MOUNTED_BUFFER.get(), level);
                var mount = position.get();
                stop.rail = cell.immutable();
                Vec3 facing = mount.tangent().scale(-1);
                stop.setYRot((float) Math.toDegrees(Math.atan2(-facing.x, facing.z)));
                stop.setXRot((float) -Math.toDegrees(Math.atan2(facing.y, facing.horizontalDistance())));
                stop.setPos(mount.point());
                var bounds = stop.getBoundingBox();
                if (level.getEntitiesOfClass(Mounted.class, bounds).isEmpty()
                    && level.getEntitiesOfClass(TrainVehicle.class, bounds).isEmpty()
                    && level.getEntitiesOfClass(net.minecraft.world.entity.vehicle.minecart.AbstractMinecart.class,
                        bounds).isEmpty() && level.addFreshEntity(stop)) {
                    context.getItemInHand().consume(1, player);
                    return InteractionResult.SUCCESS;
                }
            }
            player.sendOverlayMessage(Component.translatable("message.trainmod.buffer_site"));
            return InteractionResult.FAIL;
        }
    }

    static final class Mounted extends VehicleEntity {
        static final float HEIGHT = VehicleModels.COUPLER_TOP / 16;
        private BlockPos rail;

        Mounted(EntityType<? extends Mounted> type, Level level) { super(type, level); }

        @Override
        protected net.minecraft.world.item.Item getDropItem() { return Trainmod.BUFFER_ITEM.get(); }

        @Override
        public ItemStack getPickResult() { return new ItemStack(getDropItem()); }

        @Override
        public boolean isPickable() { return isAlive(); }

        @Override
        public boolean canBeCollidedWith(Entity other) { return isAlive(); }

        @Override
        protected AABB makeBoundingBox(Vec3 pos) {
            Vec3 heading = Vec3.directionFromRotation(0, getYRot());
            double x = Math.abs(heading.z) * 0.39 + Math.abs(heading.x) * (7.25 / 16);
            double z = Math.abs(heading.x) * 0.39 + Math.abs(heading.z) * (7.25 / 16);
            return new AABB(pos.x - x, pos.y, pos.z - z, pos.x + x, pos.y + HEIGHT, pos.z + z);
        }

        @Override
        public void tick() {
            super.tick();
            if (level() instanceof ServerLevel server && (tickCount & 7) == 0) {
                BlockPos cell = rail == null ? blockPosition() : rail;
                if (server.isLoaded(cell) && RailNetwork.physicalRailsAt(server, cell).stream()
                    .noneMatch(path -> path.point(path.project(position())).distanceToSqr(position()) < 0.04)) {
                    destroy(server, getDropItem());
                }
            }
        }

        @Override
        protected void readAdditionalSaveData(ValueInput input) {
            rail = input.read("Rail", BlockPos.CODEC).orElse(null);
        }

        @Override
        protected void addAdditionalSaveData(ValueOutput output) {
            if (rail != null) output.store("Rail", BlockPos.CODEC, rail);
        }
    }
}
