package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class TrainVehicle extends VehicleEntity {
    enum Kind {
        LOCOMOTIVE(1.22f, 3.25f, 0.90f, true),
        FLATCAR(0.5625f, 2.50f, 0.90f, false),
        GONDOLA(0.8125f, 2.80f, 0.875f, false),
        BOXCAR(1.1875f, 3.00f, 0.90f, false),
        TUNNEL_BORE(2.65f, 4.15f, true),
        BALLAST_CAR(0.8125f, 2.85f, 0.875f, false),
        STEAM_LOCOMOTIVE(1.43f, 3.65f, 0.90f, true),
        TENDER(1.05f, 2.45f, 0.90f, false),
        ELECTRIC_SHUNTER(1.30f, 2.35f, 0.90f, true);

        final float height;
        final float length;
        final float width;
        final boolean powered;

        Kind(float height, float length, boolean powered) {
            this(height, length, 1.5f, powered);
        }

        Kind(float height, float length, float width, boolean powered) {
            this.height = height;
            this.length = length;
            this.width = width;
            this.powered = powered;
        }

        String id() { return name().toLowerCase(Locale.ROOT); }

        float brakeWheelHeight() {
            return switch (this) {
                case FLATCAR -> 7.5f;
                case GONDOLA -> 11.5f;
                default -> 12;
            };
        }

        double brakeWheelOffset() { return VehicleModels.BRAKE_X * width / 32.0; }

        boolean passengerCar() { return this == GONDOLA || this == BOXCAR; }

        boolean showsLoad() { return this == FLATCAR || this == GONDOLA || this == BALLAST_CAR; }

        boolean freight() { return this == FLATCAR || this == GONDOLA || this == BOXCAR; }
    }

    enum BallastMode {
        NARROW, WIDE, TRACK_ONLY;

        private static final BallastMode[] MODES = values();

        static BallastMode byId(int id) { return id >= 0 && id < MODES.length ? MODES[id] : NARROW; }

        BallastMode next() { return MODES[(ordinal() + 1) % MODES.length]; }
    }

    private record Member(TrainVehicle vehicle, int movement, int parent, int parentEnd) {}
    private record CouplingEnds(int own, int other) {}
    private record CouplerContact(TrainVehicle vehicle, int end) {}
    private record Engineer(TrainVehicle engine, TrainVehicle stand, ServerPlayer rider) {}
    private record Traction(TrainVehicle engine, TrainVehicle tender, SteamTraction.Output steam, boolean wired, double power) {}
    private record Impact(TrainVehicle incoming, int incomingEnd, TrainVehicle waiting, int waitingEnd,
                          RailNetwork.Position contact, List<Member> waitingConsist, double travel) {}

    private static final java.util.concurrent.atomic.AtomicLong BOARDING_ORDER =
        new java.util.concurrent.atomic.AtomicLong();
    private static final double ONE_MPH = 0.44704 / 20;
    private static final double COUPLING_NOTICE_RADIUS = 16;

    static final int RAIL_SUPPLY = 0;
    static final int TIE_SUPPLY = 1;
    static final int STONE_SUPPLY = 2;

    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> BANK =
        net.minecraft.network.syncher.SynchedEntityData.defineId(TrainVehicle.class,
            net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> FRONT_SEAT =
        net.minecraft.network.syncher.SynchedEntityData.defineId(TrainVehicle.class,
            net.minecraft.network.syncher.EntityDataSerializers.INT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> REAR_SEAT =
        net.minecraft.network.syncher.SynchedEntityData.defineId(TrainVehicle.class,
            net.minecraft.network.syncher.EntityDataSerializers.INT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> FRONT_COUPLED =
        net.minecraft.network.syncher.SynchedEntityData.defineId(TrainVehicle.class,
            net.minecraft.network.syncher.EntityDataSerializers.INT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> REAR_COUPLED =
        net.minecraft.network.syncher.SynchedEntityData.defineId(TrainVehicle.class,
            net.minecraft.network.syncher.EntityDataSerializers.INT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> CARGO_LOAD =
        net.minecraft.network.syncher.SynchedEntityData.defineId(TrainVehicle.class,
            net.minecraft.network.syncher.EntityDataSerializers.INT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DOORS =
        net.minecraft.network.syncher.SynchedEntityData.defineId(TrainVehicle.class,
            net.minecraft.network.syncher.EntityDataSerializers.INT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> HANDBRAKE =
        net.minecraft.network.syncher.SynchedEntityData.defineId(TrainVehicle.class,
            net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> WHEEL_PHASE =
        net.minecraft.network.syncher.SynchedEntityData.defineId(TrainVehicle.class,
            net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> BORE_TIRES =
        net.minecraft.network.syncher.SynchedEntityData.defineId(TrainVehicle.class,
            net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> HEADLIGHT =
        net.minecraft.network.syncher.SynchedEntityData.defineId(TrainVehicle.class,
            net.minecraft.network.syncher.EntityDataSerializers.INT);
    static final double DRIVER_WHEEL_RADIUS = 0.20;
    private static final net.minecraft.network.syncher.EntityDataAccessor<Byte> AUDIO =
        net.minecraft.network.syncher.SynchedEntityData.defineId(TrainVehicle.class,
            net.minecraft.network.syncher.EntityDataSerializers.BYTE);
    private float previousWheelPhase;
    private float wheelPhase;
    private double wheelTravel;
    private float previousBank;
    private float currentBank;

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(BANK, 0f);
        builder.define(FRONT_SEAT, -1);
        builder.define(REAR_SEAT, -1);
        builder.define(FRONT_COUPLED, -1);
        builder.define(REAR_COUPLED, -1);
        builder.define(CARGO_LOAD, 0);
        builder.define(DOORS, 0);
        builder.define(HANDBRAKE, false);
        builder.define(WHEEL_PHASE, 0f);
        builder.define(BORE_TIRES, 0f);
        builder.define(HEADLIGHT, 0);
        builder.define(AUDIO, (byte) 0);
    }

    float renderBank(float partial) {
        return net.minecraft.util.Mth.lerp(partial, previousBank, currentBank);
    }

    int coupledId(int end) {
        return entityData.get(end == 0 ? FRONT_COUPLED : REAR_COUPLED);
    }

    private void syncCoupler(int end, Entity linked) {
        entityData.set(end == 0 ? FRONT_COUPLED : REAR_COUPLED, linked == null ? -1 : linked.getId());
    }

    final Kind kind;
    final SimpleContainer cargo;
    final SimpleContainer overflow;
    final SimpleContainer supplies;
    private final UUID[] links = new UUID[2];
    private BlockPos railRoot;
    private int railRoute = -1;
    private double speed;
    private double pendingPush;
    private PartMotion leftDoorMotion = new PartMotion(0);
    private PartMotion rightDoorMotion = new PartMotion(0);
    private PartMotion handbrakeMotion = new PartMotion(0);
    private PartMotion boreTireMotion = new PartMotion(0);
    private boolean boardFront;
    private boolean boardingTender;
    private int boardingKeyGrace;
    private boolean working;
    private BallastMode ballastMode = BallastMode.NARROW;
    private boolean previousJump;
    private long boardingOrder;
    private long lastAudioTick = Long.MIN_VALUE;
    private long lastMoveTick = Long.MIN_VALUE;
    private UUID lastMoveLeader;
    private byte controlKeys;
    private int lastControlTick = -100;
    private final VehicleFuel fuel = new VehicleFuel();
    private UUID controlRider;
    private UUID activeDriver;
    private UUID activeStand;
    private long nextMessageTick;
    private Vec3 lastSyncedPosition;

    TrainVehicle(EntityType<? extends TrainVehicle> type, Level level, Kind kind) {
        super(type, level);
        this.kind = kind;
        cargo = inventory(kind == Kind.TUNNEL_BORE ? 54 : kind.freight() || kind == Kind.BALLAST_CAR ? 27 : 0);
        overflow = inventory(kind == Kind.TUNNEL_BORE ? 54 : 0);
        supplies = inventory(kind == Kind.TUNNEL_BORE || kind == Kind.TENDER || kind == Kind.LOCOMOTIVE ? 9 : 0);
    }

    float wheelPhase(float partial) {
        return net.minecraft.util.Mth.rotLerp(partial, previousWheelPhase, wheelPhase);
    }

    int headlight() { return entityData.get(HEADLIGHT); }

    boolean hornActive() { return (entityData.get(AUDIO) & TrainControls.HORN) != 0; }

    boolean bellActive() { return (entityData.get(AUDIO) & TrainControls.BELL) != 0; }

    private void setAudio(boolean horn, boolean bell) {
        entityData.set(AUDIO, (byte) ((horn ? TrainControls.HORN : 0) | (bell ? TrainControls.BELL : 0)));
    }

    int railLayers() { return Math.max(0, entityData.get(CARGO_LOAD)); }

    float gravelFill() { return Math.max(0, -entityData.get(CARGO_LOAD)) / 100f; }

    boolean doorOpen(boolean left) { return (entityData.get(DOORS) & (left ? 1 : 2)) != 0; }

    boolean handbrakeApplied() { return entityData.get(HANDBRAKE); }

    float doorProgress(boolean left, float partial) {
        return (float) (left ? leftDoorMotion : rightDoorMotion).at(level().getGameTime() + (double) partial);
    }

    float handbrakeAngle(float partial) {
        return (float) handbrakeMotion.at(level().getGameTime() + (double) partial);
    }

    float boreTireExtension(float partial) {
        return (float) boreTireMotion.at(level().getGameTime() + (double) partial);
    }

    @Override
    public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (key.equals(DOORS)) {
            leftDoorMotion = movePart(leftDoorMotion, doorOpen(true) ? 1 : 0);
            rightDoorMotion = movePart(rightDoorMotion, doorOpen(false) ? 1 : 0);
        } else if (key.equals(HANDBRAKE)) {
            handbrakeMotion = movePart(handbrakeMotion, handbrakeApplied() ? 90 : 0);
        } else if (key.equals(BORE_TIRES)) {
            boreTireMotion = movePart(boreTireMotion, entityData.get(BORE_TIRES));
        }
    }

    private PartMotion movePart(PartMotion motion, double target) {
        // An entity entering the client view starts in its saved pose.
        if (level().isClientSide() && tickCount == 0) return new PartMotion(target);
        motion.moveTo(target, level().getGameTime());
        return motion;
    }

    private void syncCargo() {
        double rails = 0;
        double gravel = 0;
        boolean railsOnly = true;
        for (int slot = 0; slot < cargo.getContainerSize(); slot++) {
            ItemStack stack = cargo.getItem(slot);
            if (stack.isEmpty()) continue;
            double units = stack.getCount() / (double) stack.getMaxStackSize();
            if (kind != Kind.BALLAST_CAR && (stack.getItem() instanceof TrackItem
                || stack.getItem() instanceof net.minecraft.world.item.BlockItem item
                && item.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock)) {
                rails += units;
            } else {
                railsOnly = false;
                if (ballastSupply(stack)) gravel += units;
            }
        }
        int load = railsOnly && rails > 0 ? Math.clamp((int) Math.ceil(rails * 4 / cargo.getContainerSize()), 1, 4)
            : (kind == Kind.GONDOLA || kind == Kind.BALLAST_CAR) && gravel > 0 ? -Math.clamp((int) Math.ceil(gravel * 100 / cargo.getContainerSize()), 1, 100)
            : 0;
        entityData.set(CARGO_LOAD, load);
    }

    private SimpleContainer inventory(int size) {
        return new SimpleContainer(size) {
            @Override
            public void setChanged() {
                super.setChanged();
                if (this == cargo && kind.showsLoad() && !level().isClientSide()) syncCargo();
            }

            @Override
            public boolean stillValid(Player player) {
                return isAlive() && player.distanceToSqr(TrainVehicle.this) <= 64;
            }
        };
    }

    BlockPos railRoot() { return railRoot; }

    @Override
    protected Item getDropItem() { return Trainmod.VEHICLE_ITEMS.get(kind).get(); }

    @Override
    public boolean isPickable() { return isAlive(); }

    @Override
    public boolean canBeCollidedWith(Entity other) { return isAlive(); }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return passenger instanceof Player && (hasDriverSeat() && getPassengers().isEmpty()
            || kind.passengerCar() && getPassengers().size() < 2);
    }

    @Override
    public LivingEntity getControllingPassenger() {
        return hasDriverSeat() && getFirstPassenger() instanceof Player player ? player : null;
    }

    private boolean hasDriverSeat() {
        return kind.powered && kind != Kind.STEAM_LOCOMOTIVE || kind == Kind.TENDER;
    }

    @Override
    protected boolean isLocalClientAuthoritative() { return false; }

    @Override
    public boolean isClientAuthoritative() { return false; }

    @Override
    protected InterpolationHandler createInterpolationHandler() {
        return level().isClientSide() ? new TrainInterpolation(this) : InterpolationHandler.NO_OP;
    }

    @Override
    public void updateDataBeforeSync() {
        Vec3 current = position();
        Vec3 displacement = lastSyncedPosition == null ? Vec3.ZERO : current.subtract(lastSyncedPosition);
        lastSyncedPosition = current;
        // Publish each car's actual travel, including cars whose movement is driven by another vehicle.
        setDeltaMovement(displacement.lengthSqr() <= 0.4 * 0.4 ? displacement : Vec3.ZERO);
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        lerpMotion(packet.getMovement());
    }

    @Override
    public void lerpMotion(Vec3 movement) {
        super.lerpMotion(movement);
        if (getInterpolation() instanceof TrainInterpolation interpolation) interpolation.setVelocity(movement);
    }

    @Override
    public void snapTo(double x, double y, double z, float yaw, float pitch) {
        getInterpolation().cancel();
        super.snapTo(x, y, z, yaw, pitch);
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, net.minecraft.world.entity.EntityDimensions dimensions,
                                               float partialTick) {
        if (kind.passengerCar()) {
            return facing().scale(kind.length * (entityData.get(FRONT_SEAT) == passenger.getId() ? 0.28 : -0.28))
                .add(0, 0.37, 0);
        }
        return switch (kind) {
            case LOCOMOTIVE, ELECTRIC_SHUNTER -> new Vec3(0, 0.55, 0);
            case TENDER -> new Vec3(0, 1.0, 0).add(facing().scale(-0.24));
            default -> facing().scale(-0.65).add(0, 0.68, 0);
        };
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (!level().isClientSide() && hasDriverSeat()) boardingOrder = BOARDING_ORDER.incrementAndGet();
        if (!level().isClientSide() && kind.passengerCar()) {
            boolean front = entityData.get(FRONT_SEAT) < 0 && (boardFront || entityData.get(REAR_SEAT) >= 0);
            entityData.set(front ? FRONT_SEAT : REAR_SEAT, passenger.getId());
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide() && hasDriverSeat()) {
            boardingTender = false;
            controlKeys = 0;
            controlRider = null;
            lastControlTick = -100;
        }
        if (!level().isClientSide() && kind.passengerCar()) {
            if (entityData.get(FRONT_SEAT) == passenger.getId()) entityData.set(FRONT_SEAT, -1);
            if (entityData.get(REAR_SEAT) == passenger.getId()) entityData.set(REAR_SEAT, -1);
        }
    }

    private VehicleHitboxes.Component clickedControl(Player player, VehicleHitboxes.Frame frame, Vec3 click) {
        // The client picks against the rendered pose. Allow a little room for movement before the packet arrives.
        for (var target : VehicleHitboxes.of(kind).controls()) {
            var here = VehicleHitboxes.bounds(this, target, 0);
            var recent = VehicleHitboxes.bounds(this, target, -2);
            if (here.minmax(recent).inflate(0.035).contains(click)) return target.component();
        }
        Vec3 eye = frame.local(player.getEyePosition());
        Vec3 ray = click.subtract(eye);
        if (ray.lengthSqr() < 1e-8 || ray.length() > player.entityInteractionRange()) return null;
        var hit = VehicleHitboxes.pick(this, eye, eye.add(ray.normalize().scale(player.entityInteractionRange())));
        return hit == null ? null : hit.component();
    }

    VehicleHitboxes.Frame renderFrame(float partial) {
        return new VehicleHitboxes.Frame(getPosition(partial), net.minecraft.util.Mth.rotLerp(partial, yRotO, getYRot()),
            net.minecraft.util.Mth.lerp(partial, xRotO, getXRot()), renderBank(partial));
    }

    private VehicleHitboxes.Frame currentFrame(Vec3 position) {
        return new VehicleHitboxes.Frame(position, getYRot(), getXRot(), entityData.get(BANK));
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 hit) {
        if (!(level() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }
        ItemStack held = player.getItemInHand(hand);
        boolean secondary = player.isSecondaryUseActive();
        var frame = currentFrame(position());
        Vec3 local = frame.local(position().add(hit));
        var control = secondary && kind.passengerCar() ? null : clickedControl(player, frame, local);
        int clickedEnd = control == null ? -1 : control.couplerEnd();
        if (held.is(Trainmod.COUPLER.get())) {
            int end = clickedEnd >= 0 ? clickedEnd : local.z >= 0 ? 0 : 1;
            couple(level, player, held, end, clickedEnd >= 0, clickedEnd >= 0 || Math.abs(local.z) >= kind.length * 0.24);
        } else if (kind == Kind.TENDER && (control == VehicleHitboxes.Component.TENDER_SEAT
            || secondary && local.y >= 0.80 && Math.abs(local.z + 0.24) <= 0.68)) {
            boardTender(level, player, secondary);
        } else if (!secondary && clickedEnd >= 0 && links[clickedEnd] != null) {
            releaseCoupler(level, player, clickedEnd);
        } else if (kind.passengerCar() && secondary) {
            boardFront = local.z >= 0;
            // Consume the boarding press so the same held key does not instantly dismount the player.
            player.setShiftKeyDown(false);
            if (!player.startRiding(this)) player.setShiftKeyDown(true);
        } else if (control == VehicleHitboxes.Component.LEFT_DOOR || control == VehicleHitboxes.Component.RIGHT_DOOR) {
            entityData.set(DOORS, entityData.get(DOORS) ^ (control == VehicleHitboxes.Component.LEFT_DOOR ? 1 : 2));
        } else if (control == VehicleHitboxes.Component.HANDBRAKE) {
            boolean set = !handbrakeApplied();
            entityData.set(HANDBRAKE, set);
            if (set) speed = 0;
            message(player, set ? "brake_set" : "brake_released");
        } else if (kind == Kind.BALLAST_CAR) {
            if (secondary) {
                working = !working;
                message(player, working ? "ballast_on" : "ballast_off");
            } else {
                player.openMenu(new SimpleMenuProvider((id, inv, ignored) -> new BallastRegulatorMenu(id, inv, cargo, this),
                    getDisplayName()));
            }
        } else if (kind == Kind.TENDER) {
            openStockMenu(player);
        } else if (kind == Kind.LOCOMOTIVE && secondary) {
            openStockMenu(player);
        } else if (kind == Kind.STEAM_LOCOMOTIVE) {
            message(player, "tender_board");
        } else if (kind == Kind.TUNNEL_BORE && held.is(Items.CHEST)) {
            player.openMenu(cargoMenu(secondary ? overflow : cargo,
                Component.translatable(secondary ? "menu.trainmod.bore_2" : "menu.trainmod.bore_1")));
        } else if (kind == Kind.TUNNEL_BORE && (secondary || held.is(Items.HOPPER))) {
            player.openMenu(new SimpleMenuProvider((id, inv, ignored) -> new SupplyMenu(id, inv, supplies),
                Component.translatable("menu.trainmod.supplies")));
        } else if (kind.freight()) {
            player.openMenu(cargoMenu(cargo, getDisplayName()));
        } else if (kind.powered) {
            if (player.startRiding(this)) {
                message(player, kind == Kind.TUNNEL_BORE
                    ? (working ? "bore_drive_working" : "bore_drive_paused") : "drive_controls");
            }
        }
        return InteractionResult.SUCCESS;
    }

    private void boardTender(ServerLevel level, Player player, boolean sneaking) {
        player.setShiftKeyDown(false);
        if (player.startRiding(this)) {
            boardingTender = sneaking;
            boardingKeyGrace = tickCount + 3;
            message(player, directSteamEngine(level) == null ? "tender_not_coupled" : "drive_controls");
        } else {
            player.setShiftKeyDown(sneaking);
            message(player, "tender_seat_unavailable");
        }
    }

    private void keepTenderRiderSeated() {
        if (!boardingTender || !(getFirstPassenger() instanceof ServerPlayer rider)) return;
        if (tickCount > boardingKeyGrace && !rider.getLastClientInput().shift() && !rider.isShiftKeyDown()) {
            boardingTender = false;
        } else {
            // The vehicle ticks before its passenger checks the dismount key.
            rider.setShiftKeyDown(false);
        }
    }

    private void openStockMenu(Player player) {
        player.openMenu(new SimpleMenuProvider((id, inv, ignored) -> new StockMenu(id, inv, supplies, kind),
            Component.translatable("menu.trainmod." + kind.id())));
    }

    private static MenuProvider cargoMenu(SimpleContainer cargo, Component title) {
        return new SimpleMenuProvider((id, playerInventory, player) -> cargo.getContainerSize() == 54
            ? ChestMenu.sixRows(id, playerInventory, cargo) : ChestMenu.threeRows(id, playerInventory, cargo), title);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            previousWheelPhase = wheelPhase;
            wheelPhase = entityData.get(WHEEL_PHASE);
            previousBank = currentBank;
            currentBank = net.minecraft.util.Mth.lerp(0.5f, currentBank, entityData.get(BANK));
            setBoundingBox(makeBoundingBox(position()));
            return;
        }
        if (getHurtTime() > 0) {
            setHurtTime(getHurtTime() - 1);
        }
        if (getDamage() > 0) {
            setDamage(getDamage() - 1);
        }
        if (level() instanceof ServerLevel level) {
            keepTenderRiderSeated();
            if ((tickCount & 7) == 0) {
                for (int end = 0; end < links.length; end++) {
                    if (links[end] != null) syncCoupler(end, level.getEntity(links[end]));
                }
            }
            if (kind.powered) {
                if (kind == Kind.ELECTRIC_SHUNTER && Catenary.powered(level, cursor())) fuel.charge();
                driveIfSelected(level);
            } else {
                roll(level);
            }
        }
        if (speed == 0 && railRoot != null && (tickCount & 7) == 0) {
            var rail = cursor();
            refreshBank(rail);
            if (kind == Kind.TUNNEL_BORE && rail != null) refreshBoreTires(rail);
        }
    }

    @Override
    public void push(double x, double y, double z) {
        if (!kind.powered && !handbrakeApplied() && !level().isClientSide()) {
            pendingPush = Math.clamp(pendingPush + facing().dot(new Vec3(x, 0, z)), -0.10, 0.10);
        }
    }

    private void roll(ServerLevel level) {
        if (railRoot == null) return;
        var members = consist(level);
        if (members == null || members.stream().anyMatch(member -> member.vehicle.kind.powered)
            || members.stream().anyMatch(member -> member.vehicle.getUUID().compareTo(getUUID()) < 0)) {
            speed = 0;
            return;
        }

        double gravity = 0;
        double push = 0;
        double slowdown = 0;
        boolean braked = false;
        for (Member member : members) {
            TrainVehicle car = member.vehicle;
            var position = car.cursor();
            if (position == null) {
                speed = 0;
                return;
            }
            gravity -= position.tangent().y * member.movement;
            push += (car.pendingPush + car.walkingPush(level)) * member.movement;
            car.pendingPush = 0;
            slowdown += car.isVehicle() ? 0.997 : 0.96;
            braked |= car.handbrakeApplied();
        }
        if (braked) {
            speed = 0;
            return;
        }
        // Gravity in blocks per tick squared, resolved along the rail and averaged across the coupled cars.
        speed = Math.clamp((speed * slowdown + gravity * (9.80665 / 400)) / members.size()
            + Math.clamp(push, -0.10, 0.10) / members.size(), -0.33, 0.33);
        if (Math.abs(speed) < 0.001) {
            speed = 0;
        } else {
            moveConsist(level, null, members, Set.of());
        }
    }

    private double walkingPush(ServerLevel level) {
        double impulse = 0;
        Vec3 forward = facing();
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, getBoundingBox().inflate(0.16))) {
            if (player.isPassenger() || player.isSpectator()) continue;
            double movement = player.getKnownMovement().dot(forward);
            var input = player.getLastClientInput();
            if (Math.abs(movement) < 0.01 && input.forward() != input.backward()) {
                movement = player.getViewVector(1).dot(forward) * (input.forward() ? 0.10 : -0.10);
            }
            double side = player.position().subtract(position()).dot(forward);
            if (side * movement < 0) impulse += movement * 0.12;
        }
        return impulse;
    }

    void receiveControls(ServerPlayer player, byte keys) {
        if (getControllingPassenger() != player) return;
        ServerLevel level = player.level();
        TrainVehicle engine = kind == Kind.TENDER ? directSteamEngine(level) : this;
        if (engine == null || engine.kind == Kind.STEAM_LOCOMOTIVE && engine.tender(level, true) != this) return;
        if ((keys & TrainControls.BELL) != 0
            && (!player.getUUID().equals(controlRider) || (controlKeys & TrainControls.BELL) == 0)) {
            engine.toggleTrainBell(level);
        }
        controlRider = player.getUUID();
        controlKeys = keys;
        lastControlTick = tickCount;
    }

    private void driveIfSelected(ServerLevel level) {
        Engineer engineer = engineer(level);
        if (engineer != null) {
            List<Member> train = consist(level);
            if (train == null) {
                stop(engineer.rider, "consist_unavailable");
            } else if (selectEngineer(level, train).engine == this) {
                if (train.stream().anyMatch(member -> member.vehicle.lastMoveTick == level.getGameTime()
                    && !getUUID().equals(member.vehicle.lastMoveLeader))) return;
                if (!engineer.rider.getUUID().equals(activeDriver) || !engineer.stand.getUUID().equals(activeStand)) {
                    activeDriver = engineer.rider.getUUID();
                    activeStand = engineer.stand.getUUID();
                    previousJump = false;
                }
                drive(level, engineer, train);
                return;
            }
        }
        activeDriver = null;
        activeStand = null;
        speed = 0;
        if (hornActive() && lastAudioTick < level.getGameTime() - 1) setAudio(false, bellActive());
    }

    private Engineer engineer(ServerLevel level) {
        TrainVehicle stand = kind == Kind.STEAM_LOCOMOTIVE ? tender(level, true) : this;
        return stand != null && stand.getControllingPassenger() instanceof ServerPlayer driver
            ? new Engineer(this, stand, driver) : null;
    }

    private static Engineer selectEngineer(ServerLevel level, List<Member> train) {
        Engineer best = null;
        for (Member member : train) {
            if (!member.vehicle.kind.powered) continue;
            Engineer candidate = member.vehicle.engineer(level);
            if (candidate != null && (best == null || compareEngineers(candidate, best) < 0)) best = candidate;
        }
        return best;
    }

    private static int compareEngineers(Engineer first, Engineer second) {
        int moving = Boolean.compare(Math.abs(second.engine.speed) > 1e-5, Math.abs(first.engine.speed) > 1e-5);
        if (moving != 0) return moving;
        int seats = Long.compare(first.stand.boardingOrder, second.stand.boardingOrder);
        return seats != 0 ? seats : first.rider.getUUID().compareTo(second.rider.getUUID());
    }

    private TrainVehicle directSteamEngine(ServerLevel level) {
        for (UUID id : links) {
            if (id != null && level.getEntity(id) instanceof TrainVehicle other
                && other.kind == Kind.STEAM_LOCOMOTIVE && other.linkedEnd(getUUID()) >= 0) return other;
        }
        return null;
    }

    private TrainVehicle tender(ServerLevel level, boolean riderRequired) {
        for (UUID id : links) {
            if (id != null && level.getEntity(id) instanceof TrainVehicle tender && tender.kind == Kind.TENDER
                && (!riderRequired || tender.getControllingPassenger() instanceof ServerPlayer)
                && tender.directSteamEngine(level) == this) return tender;
        }
        return null;
    }

    private void playTrainSound(ServerLevel level, net.minecraft.sounds.SoundEvent sound, float volume) {
        level.playSound(null, getX(), getY(), getZ(), sound, net.minecraft.sounds.SoundSource.NEUTRAL, volume, 1f);
    }

    private void drive(ServerLevel level, Engineer engineer, List<Member> consist) {
        TrainVehicle stand = engineer.stand;
        ServerPlayer driver = engineer.rider;
        byte keys = driver.getUUID().equals(stand.controlRider) && stand.tickCount - stand.lastControlTick <= 16
            ? stand.controlKeys : 0;
        updateTrainAudio(level, consist, keys);
        boolean work = (keys & TrainControls.WORK) != 0;
        if (kind == Kind.TUNNEL_BORE && work && !previousJump) {
            working = !working;
            message(driver, working ? "bore_on" : "bore_off");
        }
        previousJump = work;
        boolean forward = (keys & TrainControls.FORWARD) != 0;
        boolean reverse = (keys & TrainControls.REVERSE) != 0;
        int orientation = stand != this && linkedEnd(stand.getUUID()) == stand.linkedEnd(getUUID()) ? -1 : 1;
        double throttle = (forward == reverse ? 0 : forward ? 1 : -1) * orientation;
        List<Traction> powered = new ArrayList<>();
        if (throttle != 0) {
            for (Member member : consist) {
                if (!member.vehicle.kind.powered) continue;
                Traction draw = member.vehicle.traction(level, member.vehicle == this ? stand : null,
                    throttle * member.movement);
                if (draw != null) powered.add(draw);
            }
        }
        double power = powered.stream().mapToDouble(draw -> Math.abs(draw.power)).max().orElse(0);
        boolean symmetric = kind == Kind.LOCOMOTIVE || kind == Kind.ELECTRIC_SHUNTER;
        double target = Math.copySign(power * (throttle >= 0 || symmetric ? 0.24 : 0.14), throttle);
        if (target * speed < 0) target = 0;
        if (symmetric) {
            double step = Math.abs(target) < Math.abs(speed) ? 0.04 : 0.025;
            speed += Math.clamp(target - speed, -step, step);
        } else {
            speed += Math.clamp(target - speed, -0.04, 0.025);
        }
        if (Math.abs(speed) < 1e-5) {
            speed = 0;
            return;
        }
        RailNetwork.Position start = cursor();
        if (start == null) {
            stop(driver, "route_unavailable");
            return;
        }
        if (consist.stream().anyMatch(member -> member.vehicle.handbrakeApplied())) {
            stop(driver, "brake_holding");
            return;
        }
        if (kind == Kind.TUNNEL_BORE && working && speed > 0) {
            var issue = TrackWork.extendIfNeeded(level, driver, this, start, speed, trailingFreight(level));
            if (issue != null) {
                stop(driver, issue.message());
                return;
            }
        }
        Set<TrainVehicle> steaming = new HashSet<>();
        if (target != 0) {
            for (Traction draw : powered) if (draw.steam != null) steaming.add(draw.engine);
        }
        if (moveConsist(level, driver, consist, steaming) && target != 0) {
            for (Traction draw : powered) draw.engine.consumeTraction(level, draw);
        }
    }

    private void toggleTrainBell(ServerLevel level) {
        List<Member> train = consist(level);
        if (train == null) return;
        Engineer selected = selectEngineer(level, train);
        if (selected == null || selected.engine != this) return;
        boolean bell = !bellActive();
        for (Member member : train) {
            TrainVehicle engine = member.vehicle;
            if (engine.kind.powered) engine.setAudio(engine.hornActive(), bell);
        }
    }

    private void updateTrainAudio(ServerLevel level, List<Member> consist, byte keys) {
        boolean bell = bellActive();
        boolean horn = (keys & TrainControls.HORN) != 0;
        for (Member member : consist) {
            TrainVehicle vehicle = member.vehicle;
            if (!vehicle.kind.powered) continue;
            vehicle.lastAudioTick = level.getGameTime();
            vehicle.setAudio(horn && (vehicle.kind == Kind.LOCOMOTIVE || vehicle.kind == Kind.STEAM_LOCOMOTIVE), bell);
        }
    }

    private Traction traction(ServerLevel level, TrainVehicle stand, double throttle) {
        return switch (kind) {
            case STEAM_LOCOMOTIVE -> {
                TrainVehicle tender = stand == null ? tender(level, false) : stand;
                if (tender == null) yield null;
                var output = fuel.steam(throttle, tender.supplies);
                yield output.power() == 0 ? null : new Traction(this, tender, output, false, output.power());
            }
            case LOCOMOTIVE -> fuel.hasDiesel(supplies) ? new Traction(this, null, null, false, throttle) : null;
            case ELECTRIC_SHUNTER -> {
                boolean wired = Catenary.powered(level, cursor());
                yield wired || fuel.hasCharge() ? new Traction(this, null, null, wired, throttle) : null;
            }
            case TUNNEL_BORE -> new Traction(this, null, null, false, throttle);
            default -> null;
        };
    }

    private void consumeTraction(ServerLevel level, Traction draw) {
        switch (kind) {
            case STEAM_LOCOMOTIVE -> fuel.burnSteam(draw.tender.supplies, draw.steam);
            case LOCOMOTIVE -> {
                fuel.burnDiesel(supplies);
                if ((tickCount & 3) == 0) exhaust(level, net.minecraft.core.particles.ParticleTypes.SMOKE,
                    new Vec3(0, 1.18, 0.65));
            }
            case ELECTRIC_SHUNTER -> {
                if (!draw.wired) fuel.drawCharge();
            }
            default -> {}
        }
    }

    private void exhaust(ServerLevel level, net.minecraft.core.particles.ParticleOptions particle, Vec3 offset) {
        Vec3 at = currentFrame(position()).world(offset);
        level.sendParticles(particle, at.x, at.y, at.z, 3, 0.05, 0.06, 0.05, 0.018);
    }

    private boolean moveConsist(ServerLevel level, ServerPlayer driver, List<Member> consist, Set<TrainVehicle> steaming) {
        Set<UUID> own = new HashSet<>();
        consist.forEach(member -> own.add(member.vehicle.getUUID()));
        RailNetwork.Position current = cursor();
        if (current == null) {
            stop(driver, "route_unavailable");
            return false;
        }
        double travel = speed;
        String endMessage = kind == Kind.TUNNEL_BORE && speed > 0 && !working ? "bore_enable_work" : "end_of_track";
        List<RailNetwork.Position> targets = travelPositions(level, consist, current, travel);
        boolean reachedEnd = targets == null;
        if (targets == null) {
            double allowed = 0, blockedTravel = 1;
            // Check only on a failing step. Stop at the wheel limit instead of one whole tick short of it.
            for (int i = 0; i < 12; i++) {
                double fraction = (allowed + blockedTravel) / 2;
                var candidate = travelPositions(level, consist, current, travel * fraction);
                if (candidate == null) blockedTravel = fraction;
                else {
                    allowed = fraction;
                    targets = candidate;
                }
            }
            travel *= allowed;
            if (targets == null || Math.abs(travel) < 1e-5) {
                stop(driver, endMessage);
                return false;
            }
        }
        if (coupleOnImpact(level, consist, targets, own, driver != null)) return true;
        int blocked = blockedPosition(level, consist, targets, own);
        if (blocked >= 0) {
            var car = consist.get(blocked).vehicle;
            stop(driver, Component.translatable("message.trainmod.vehicle_clearance", car.getDisplayName(),
                BlockPos.containing(targets.get(blocked).point()).toShortString()));
            return false;
        }
        for (int i = 0; i < consist.size(); i++) {
            TrainVehicle vehicle = consist.get(i).vehicle;
            RailNetwork.Position next = targets.get(i);
            if (vehicle.kind == Kind.TUNNEL_BORE && BoreDrive.tireExtension(level, next) < 0) {
                stop(driver, "bore_no_ground");
                return false;
            }
        }
        for (int i = 0; i < consist.size(); i++) {
            TrainVehicle vehicle = consist.get(i).vehicle;
            RailNetwork.Position next = targets.get(i);
            vehicle.moveToRail(next);
            vehicle.lastMoveTick = level.getGameTime();
            vehicle.lastMoveLeader = getUUID();
            double movement = travel * consist.get(i).movement;
            if (vehicle.kind == Kind.STEAM_LOCOMOTIVE) vehicle.turnWheels(level, movement, steaming.contains(vehicle));
            if (vehicle.kind == Kind.LOCOMOTIVE) vehicle.entityData.set(HEADLIGHT, (int) Math.signum(movement));
            if (driver != null && vehicle.kind == Kind.BALLAST_CAR && vehicle.working) {
                BlockPos track = next.trackCell();
                if (track != null) vehicle.regulate(level, driver, track.below());
            }
        }
        if (reachedEnd) stop(driver, endMessage);
        return true;
    }

    private List<RailNetwork.Position> travelPositions(ServerLevel level, List<Member> consist,
                                                      RailNetwork.Position current, double travel) {
        var lead = kind == Kind.TUNNEL_BORE ? RailNetwork.advanceBore(level, current, travel)
            : RailNetwork.advance(level, current, travel);
        var targets = plannedPositions(level, consist, lead);
        if (targets == null) return null;
        for (int i = 0; i < consist.size(); i++) {
            Member member = consist.get(i);
            Kind stock = member.vehicle.kind;
            var target = targets.get(i);
            if (stock == Kind.TUNNEL_BORE) {
                if (!BoreDrive.onRearRails(level, target)) return null;
            } else {
                double axle = travel * member.movement > 0 ? VehicleModels.frontAxle(stock) : VehicleModels.rearAxle(stock);
                if (RailNetwork.advance(level, target, axle) == null) return null;
            }
        }
        return targets;
    }

    private void turnWheels(ServerLevel level, double distance, boolean steaming) {
        double interval = Math.PI * DRIVER_WHEEL_RADIUS / 2;
        long lastBeat = (long) Math.floor(wheelTravel / interval);
        wheelTravel += distance;
        // Five driver revolutions also complete eight pilot-wheel revolutions.
        entityData.set(WHEEL_PHASE, (float) Math.toDegrees(wheelTravel / DRIVER_WHEEL_RADIUS % (10 * Math.PI)));
        if (steaming && lastBeat != (long) Math.floor(wheelTravel / interval)) {
            exhaust(level, net.minecraft.core.particles.ParticleTypes.WHITE_SMOKE, new Vec3(0, 1.43, 0.93));
            playTrainSound(level, Trainmod.CHUFF.get(), 0.6f);
        }
    }

    static boolean ballastSupply(ItemStack stack) {
        return stack.is(Items.GRAVEL) || stack.is(Trainmod.BALLAST.get().asItem());
    }

    BallastMode ballastMode() { return ballastMode; }

    void cycleBallastMode() { ballastMode = ballastMode.next(); }

    void regulate(ServerLevel level, ServerPlayer driver, BlockPos under) {
        int available = cargo.getItems().stream().filter(TrainVehicle::ballastSupply).mapToInt(ItemStack::getCount).sum();
        if (available == 0) return;
        boolean trackOnly = ballastMode == BallastMode.TRACK_ONLY;
        int used = TrackWork.ballast(level, driver, under, available, trackOnly);
        if (!trackOnly) used += TrackWork.cascadeBallast(level, driver, under, ballastMode == BallastMode.WIDE, available - used);
        if (used == 0) return;
        for (int slot = 0; used > 0 && slot < cargo.getContainerSize(); slot++) {
            if (ballastSupply(cargo.getItem(slot))) used -= cargo.removeItem(slot, used).getCount();
        }
        var soundPos = trackOnly ? under.above() : under;
        var sound = Trainmod.BALLAST.get().defaultBlockState().getSoundType(level, soundPos, driver);
        level.playSound(null, soundPos, sound.getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS,
            (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8f);
    }

    private boolean coupleOnImpact(ServerLevel level, List<Member> consist, List<RailNetwork.Position> targets, Set<UUID> own,
                                   boolean driven) {
        Impact impact = null;
        for (int i = 0; i < consist.size(); i++) {
            var member = consist.get(i);
            var incoming = member.vehicle;
            int end = speed * member.movement > 0 ? 0 : 1;
            if (incoming.links[end] != null) continue;
            var current = incoming.cursor();
            if (current == null) continue;
            var target = targets.get(i);
            Vec3 heading = current.tangent().scale(end == 0 ? 1 : -1);
            AABB nearby = AABB.ofSize(target.point(), 8, 2, 8);
            for (var waiting : level.getEntitiesOfClass(TrainVehicle.class, nearby,
                car -> (driven || !car.kind.powered) && !own.contains(car.getUUID()))) {
                var candidate = impact(level, incoming, end, waiting, current, target, heading, consist.size());
                if (candidate != null && (impact == null || candidate.travel < impact.travel)) impact = candidate;
            }
        }
        if (impact == null) return false;
        var incomingConsist = impact.incoming.consist(level);
        if (incomingConsist == null) return false;
        Set<UUID> both = new HashSet<>(own);
        impact.waitingConsist.forEach(member -> both.add(member.vehicle.getUUID()));
        var positions = plannedPositions(level, incomingConsist, impact.contact);
        if (positions == null || blockedPosition(level, incomingConsist, positions, both) >= 0) return false;
        for (int i = 0; i < incomingConsist.size(); i++) incomingConsist.get(i).vehicle.moveToRail(positions.get(i));
        impact.incoming.linkPair(impact.incomingEnd, impact.waiting, impact.waitingEnd);
        Vec3 joint = impact.incoming.couplerTip(impact.incomingEnd).lerp(impact.waiting.couplerTip(impact.waitingEnd), 0.5);
        for (ServerPlayer player : level.getPlayers(player ->
                player.distanceToSqr(joint) <= COUPLING_NOTICE_RADIUS * COUPLING_NOTICE_RADIUS)) {
            message(player, "coupled");
        }
        for (var group : List.of(incomingConsist, impact.waitingConsist)) {
            for (var member : group) {
                if (!driven || !member.vehicle.kind.powered) member.vehicle.speed = 0;
                member.vehicle.lastMoveTick = level.getGameTime();
                member.vehicle.lastMoveLeader = getUUID();
                member.vehicle.pendingPush = 0;
            }
        }
        return true;
    }

    private Impact impact(ServerLevel level, TrainVehicle incoming, int end, TrainVehicle waiting, RailNetwork.Position from,
                          RailNetwork.Position to, Vec3 heading, int incomingCount) {
        var fixed = waiting.cursor();
        if (fixed == null) return null;
        Vec3 separation = fixed.point().subtract(from.point());
        if (separation.dot(heading) <= 0) return null;
        int waitingEnd = fixed.tangent().dot(separation) < 0 ? 0 : 1;
        if (waiting.links[waitingEnd] != null) return null;
        var contact = touchCouplers(level, fixed, waiting, waitingEnd, incoming, end == waitingEnd);
        if (contact == null) return null;
        Vec3 approach = contact.point().subtract(from.point());
        double travel = approach.dot(heading);
        double reach = to.point().subtract(from.point()).dot(heading);
        if (travel < -0.085 || travel > reach + 1e-5) return null;
        var probe = RailNetwork.advance(level, from, travel * (end == 0 ? 1 : -1));
        if (probe == null || probe.point().distanceToSqr(contact.point()) > 0.0016) return null;

        var waitingConsist = waiting.consist(level);
        if (waitingConsist == null || waitingConsist.size() + incomingCount > 24) return null;
        double waitingSpeed = waitingConsist.stream().mapToDouble(member -> member.vehicle.speed * member.movement).sum();
        double closingSpeed = Math.abs(speed) + (waitingEnd == 0 ? waitingSpeed : -waitingSpeed);
        return closingSpeed > ONE_MPH ? new Impact(incoming, end, waiting, waitingEnd, contact, waitingConsist, travel) : null;
    }

    private void stop(ServerPlayer driver, String key) {
        stop(driver, Component.translatable("message.trainmod." + key));
    }

    private void stop(ServerPlayer driver, Component reason) {
        speed = 0;
        if (driver != null && tickCount >= nextMessageTick) {
            nextMessageTick = tickCount + 30;
            driver.sendOverlayMessage(reason);
        }
    }

    RailNetwork.Position cursor() {
        if (railRoot == null) return null;
        var rail = RailNetwork.locate(level(), railRoot, position(), facing(), railRoute);
        return kind == Kind.TUNNEL_BORE && rail != null ? BoreDrive.restoreCenter(level(), rail, position()) : rail;
    }

    private Vec3 facing() {
        return Vec3.directionFromRotation(0, getYRot());
    }

    void moveToRail(RailNetwork.Position position) {
        railRoot = position.rail().root();
        railRoute = position.rail().route();
        refreshBank(position);
        Vec3 tangent = position.tangent();
        setRot((float) Math.toDegrees(Math.atan2(-tangent.x, tangent.z)),
            (float) -Math.toDegrees(Math.atan2(tangent.y, tangent.horizontalDistance())));
        setPos(position.point());
        if (kind == Kind.TUNNEL_BORE) refreshBoreTires(position);
    }

    private void refreshBoreTires(RailNetwork.Position position) {
        entityData.set(BORE_TIRES, (float) Math.max(0, BoreDrive.tireExtension(level(), position)));
    }

    private void refreshBank(RailNetwork.Position position) {
        float bank = 0;
        if (position != null && level().getBlockState(position.rail().root()).getBlock() instanceof TrackBlock track
            && track.kind.bankedCurve()) {
            var path = position.rail().path();
            int connections = CurveBanking.connections(level(), position.rail().root(), track.kind, path, CurveBanking.START | CurveBanking.END);
            bank = (float) (CurveBanking.slope(track.kind, path, position.distance(), connections) * position.forward());
        }
        entityData.set(BANK, bank);
    }

    @Override
    protected AABB makeBoundingBox(Vec3 position) {
        if (kind == null) {
            return super.makeBoundingBox(position);
        }
        return currentFrame(position).worldBounds(loadingGauge());
    }

    private AABB loadingGauge() {
        double width = kind.width / 2.0;
        double length = kind.length / 2.0;
        return new AABB(-width, 0, -length, width, kind.height, length);
    }

    boolean foulsPoints(List<Vec3> bladePoints, double clearance) {
        var frame = currentFrame(position());
        double width = SwitchLayout.GAUGE + 0.035;
        double front = kind == Kind.TUNNEL_BORE && entityData.get(BORE_TIRES) > 0
            ? VehicleModels.boreInnerRearAxle() : VehicleModels.frontAxle(kind);
        var gear = new AABB(-width, -0.14, VehicleModels.rearAxle(kind), width, 0.15, front);
        double limit = clearance * clearance;
        return bladePoints.stream().anyMatch(point -> gear.distanceToSqr(frame.local(point)) <= limit);
    }

    boolean canMoveTo(ServerLevel level, RailNetwork.Position target, Set<UUID> own) {
        Vec3 point = target.point();
        Vec3 forward = target.tangent().multiply(1, 0, 1).normalize();
        Vec3 left = new Vec3(forward.z, 0, -forward.x);
        double sampleRadius = Math.min(0.24, kind.width / 6.0);
        double sideOffset = kind.width / 2.0 - sampleRadius;
        AABB search = AABB.ofSize(point.add(0, 0.5, 0), 8, 2.5, 8);
        var buffers = level.getEntitiesOfClass(BufferStop.Mounted.class, search);
        for (int longitudinal = -2; longitudinal <= 2; longitudinal++) {
            double along = longitudinal * (kind.length / 2.0 - sampleRadius) / 2.0;
            for (int across = -1; across <= 1; across++) {
                Vec3 center = point.add(target.tangent().scale(along)).add(left.scale(across * sideOffset));
                AABB part = new AABB(center.x - sampleRadius, center.y + 0.20, center.z - sampleRadius,
                    center.x + sampleRadius, center.y + kind.height, center.z + sampleRadius);
                if (!level.isLoaded(BlockPos.containing(center)) || level.getBlockCollisions(this, part).iterator().hasNext()
                    || buffers.stream().anyMatch(buffer -> buffer.getBoundingBox().intersects(part))) {
                    return false;
                }
            }
        }
        return level.getEntitiesOfClass(TrainVehicle.class, search, car -> car != this
                && !own.contains(car.getUUID()) && vehiclesOverlap(point, forward, car)).isEmpty()
            && level.getEntitiesOfClass(AbstractMinecart.class, search,
                cart -> cart.position().distanceToSqr(point) < 1.5 * 1.5).isEmpty();
    }

    private boolean vehiclesOverlap(Vec3 point, Vec3 forward, TrainVehicle other) {
        Vec3 separation = other.position().subtract(point).multiply(1, 0, 1);
        double distance = separation.length();
        Vec3 axis = separation.normalize();
        return point.y < other.getY() + other.kind.height && other.getY() < point.y + kind.height
            && (distance < 0.01 || distance < extent(forward, axis, kind)
                + extent(other.facing(), axis, other.kind) - 0.08);
    }

    private static double extent(Vec3 forward, Vec3 axis, Kind kind) {
        return Math.abs(forward.dot(axis)) * kind.length / 2
            + Math.abs(new Vec3(forward.z, 0, -forward.x).dot(axis)) * kind.width / 2;
    }

    private List<Member> consist(ServerLevel level) {
        List<Member> members = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        members.add(new Member(this, 1, -1, -1));
        visited.add(getUUID());
        for (int i = 0; i < members.size(); i++) {
            Member member = members.get(i);
            for (int end = 0; end < 2; end++) {
                UUID id = member.vehicle.links[end];
                if (id == null || visited.contains(id)) {
                    continue;
                }
                if (!(level.getEntity(id) instanceof TrainVehicle next)) {
                    return null;
                }
                int otherEnd = next.linkedEnd(member.vehicle.getUUID());
                if (otherEnd < 0 || members.size() >= 24) {
                    return null;
                }
                visited.add(id);
                members.add(new Member(next, member.movement * (end == otherEnd ? -1 : 1), i, end));
            }
        }
        return members;
    }

    private static List<RailNetwork.Position> coupledPositions(ServerLevel level, List<Member> consist,
                                                              RailNetwork.Position lead, Set<UUID> own) {
        var positions = plannedPositions(level, consist, lead);
        return positions != null && blockedPosition(level, consist, positions, own) < 0 ? positions : null;
    }

    private static List<RailNetwork.Position> plannedPositions(ServerLevel level, List<Member> consist, RailNetwork.Position lead) {
        var targets = new ArrayList<RailNetwork.Position>();
        for (Member member : consist) {
            RailNetwork.Position target = lead;
            if (member.parent >= 0) {
                Member parent = consist.get(member.parent);
                target = touchCouplers(level, targets.get(member.parent), parent.vehicle, member.parentEnd,
                    member.vehicle, member.movement != parent.movement);
            }
            if (target == null) return null;
            targets.add(target);
        }
        return targets;
    }

    private static int blockedPosition(ServerLevel level, List<Member> consist, List<RailNetwork.Position> targets,
                                       Set<UUID> own) {
        for (int i = 0; i < consist.size(); i++) {
            var car = consist.get(i).vehicle;
            var target = targets.get(i);
            if (!car.canMoveTo(level, target, own)
                || car.handbrakeApplied() && target.point().distanceToSqr(car.position()) > 1e-6) return i;
        }
        return -1;
    }

    private static RailNetwork.Position touchCouplers(ServerLevel level, RailNetwork.Position fixed, TrainVehicle fixedCar,
                                                      int fixedEnd, TrainVehicle movingCar, boolean reversed) {
        double separation = (fixedCar.kind.length + movingCar.kind.length) / 2.0;
        var position = RailNetwork.advance(level, fixed, fixedEnd == 0 ? separation : -separation);
        return position != null && reversed
            ? new RailNetwork.Position(position.rail(), position.distance(), -position.forward()) : position;
    }

    private List<TrainVehicle> trailingFreight(ServerLevel level) {
        return trailingConsist(level).stream().filter(car -> car.kind.freight()).toList();
    }

    List<TrainVehicle> trailingConsist(ServerLevel level) {
        List<TrainVehicle> cars = new ArrayList<>();
        TrainVehicle current = this;
        int rear = 1;
        for (int i = 0; i < 23 && current.links[rear] != null; i++) {
            if (!(level.getEntity(current.links[rear]) instanceof TrainVehicle next)) {
                break;
            }
            rear = 1 - next.linkedEnd(current.getUUID());
            if (rear > 1) {
                break;
            }
            cars.add(next);
            current = next;
        }
        return cars;
    }

    private int linkedEnd(UUID target) {
        return target.equals(links[0]) ? 0 : target.equals(links[1]) ? 1 : -1;
    }

    private void couple(ServerLevel level, Player player, ItemStack tool, int end, boolean exact, boolean nearEnd) {
        var data = TrackBuilder.data(tool);
        if (links[end] != null && exact) {
            releaseCoupler(level, player, end);
            data.remove("vehicle");
            TrackBuilder.save(tool, data);
            return;
        }
        UUID selected = data.read("vehicle", UUIDUtil.CODEC).orElse(null);
        TrainVehicle other = selected != null && level.getEntity(selected) instanceof TrainVehicle car && car != this ? car : null;
        int preferredOther = data.getIntOr("end", 0) == 0 ? 0 : 1;
        boolean otherExact = data.getBooleanOr("exact", false);
        if (other == null && nearEnd && links[end] == null) {
            CouplerContact contact = touchingCoupler(level, end);
            if (contact != null) {
                other = contact.vehicle;
                preferredOther = contact.end;
                otherExact = true;
                exact = true;
            }
        }
        if (other == null) {
            if (links[end] != null && links[1 - end] == null) end = 1 - end;
            data.store("vehicle", UUIDUtil.CODEC, getUUID());
            data.putInt("end", end);
            data.putBoolean("exact", exact);
            TrackBuilder.save(tool, data);
            message(player, "coupler_selected");
            return;
        }
        TrainVehicle selectedCar = other;
        List<Member> ownConsist = consist(level);
        List<Member> otherConsist = other.consist(level);
        RailNetwork.Position first = cursor();
        RailNetwork.Position second = other.cursor();
        if (ownConsist == null || otherConsist == null || ownConsist.size() + otherConsist.size() > 24
            || ownConsist.stream().anyMatch(member -> member.vehicle == selectedCar || Math.abs(member.vehicle.speed) > 0.001)
            || otherConsist.stream().anyMatch(member -> Math.abs(member.vehicle.speed) > 0.001)
            || first == null || second == null) {
            message(player, "coupler_alignment");
            return;
        }

        CouplingEnds ends = facingCouplers(level, other, first, second, end, exact, preferredOther, otherExact);
        if (ends == null) {
            message(player, "coupler_alignment");
            return;
        }
        Set<UUID> both = new HashSet<>();
        ownConsist.forEach(member -> both.add(member.vehicle.getUUID()));
        otherConsist.forEach(member -> both.add(member.vehicle.getUUID()));
        boolean reversed = ends.own == ends.other;
        var moving = ownConsist;
        var positions = coupledPositions(level, moving, touchCouplers(level, second, other, ends.other, this, reversed), both);
        if (positions == null) {
            moving = otherConsist;
            positions = coupledPositions(level, moving, touchCouplers(level, first, this, ends.own, other, reversed), both);
        }
        if (positions == null) {
            message(player, "coupler_alignment");
            return;
        }
        for (int i = 0; i < moving.size(); i++) moving.get(i).vehicle.moveToRail(positions.get(i));
        linkPair(ends.own, other, ends.other);
        data.remove("vehicle");
        TrackBuilder.save(tool, data);
        message(player, "coupled");
    }

    private CouplerContact touchingCoupler(ServerLevel level, int end) {
        var start = cursor();
        if (start == null || Math.abs(speed) > 0.001) return null;
        Vec3 tip = couplerTip(end);
        CouplerContact contact = null;
        double nearest = 0.32 * 0.32;
        for (TrainVehicle other : level.getEntitiesOfClass(TrainVehicle.class, AABB.ofSize(tip, 3, 2, 3),
            car -> car != this && Math.abs(car.speed) <= 0.001)) {
            var position = other.cursor();
            if (position == null) continue;
            for (int remote = 0; remote < 2; remote++) {
                double distance = tip.distanceToSqr(other.couplerTip(remote));
                if (other.links[remote] == null && distance < nearest
                    && canCouple(level, start, end, position, remote, (kind.length + other.kind.length) / 2.0)) {
                    contact = new CouplerContact(other, remote);
                    nearest = distance;
                }
            }
        }
        return contact;
    }

    private CouplingEnds facingCouplers(ServerLevel level, TrainVehicle other, RailNetwork.Position first,
                                        RailNetwork.Position second, int end, boolean exact,
                                        int otherEnd, boolean otherExact) {
        double gap = (kind.length + other.kind.length) / 2.0;
        for (int own : exact ? new int[]{end} : new int[]{end, 1 - end}) {
            if (links[own] != null) continue;
            for (int remote : otherExact ? new int[]{otherEnd} : new int[]{otherEnd, 1 - otherEnd}) {
                if (other.links[remote] == null && canCouple(level, first, own, second, remote, gap)) {
                    return new CouplingEnds(own, remote);
                }
            }
        }
        return null;
    }

    private void releaseCoupler(ServerLevel level, Player player, int end) {
        unlink(level, end);
        message(player, "uncoupled");
    }

    private void linkPair(int end, TrainVehicle other, int otherEnd) {
        links[end] = other.getUUID();
        other.links[otherEnd] = getUUID();
        syncCoupler(end, other);
        other.syncCoupler(otherEnd, this);
    }

    private Vec3 couplerTip(int end) {
        return position().add(couplerOffset(end, getYRot(), getXRot(), entityData.get(BANK)));
    }

    Vec3 couplerOffset(int end, float yaw, float pitch, double bank) {
        double height = (VehicleModels.COUPLER_BOTTOM + VehicleModels.COUPLER_HEIGHT / 2) / 16;
        return new VehicleHitboxes.Frame(Vec3.ZERO, yaw, pitch, bank)
            .world(new Vec3(0, height, (end == 0 ? 1 : -1) * kind.length / 2));
    }

    private static boolean canCouple(ServerLevel level, RailNetwork.Position first, int end,
                                     RailNetwork.Position second, int otherEnd, double combinedLength) {
        int sign = end == 0 ? 1 : -1;
        for (int i = 0; i <= 24; i++) {
            RailNetwork.Position probe = RailNetwork.advance(level, first, sign * (combinedLength - 0.18 + i * 0.09));
            if (probe == null) {
                break;
            }
            if (probe.point().distanceToSqr(second.point()) < 0.012
                && probe.tangent().dot(second.tangent()) * sign * (otherEnd == 0 ? 1 : -1) < -0.65) {
                return true;
            }
        }
        return false;
    }

    private void unlink(ServerLevel level, int end) {
        if (links[end] != null && level.getEntity(links[end]) instanceof TrainVehicle other) {
            int otherEnd = other.linkedEnd(getUUID());
            if (otherEnd >= 0) {
                other.links[otherEnd] = null;
                other.syncCoupler(otherEnd, null);
            }
        }
        links[end] = null;
        syncCoupler(end, null);
    }

    @Override
    public void onRemoval(RemovalReason reason) {
        super.onRemoval(reason);
        if (kind == Kind.TUNNEL_BORE && level() instanceof ServerLevel server
            && (reason.shouldDestroy() || reason == RemovalReason.CHANGED_DIMENSION)) {
            BoreTickets.release(server, getUUID());
            if (reason == RemovalReason.CHANGED_DIMENSION) {
                unlink(server, 0);
                unlink(server, 1);
            }
        }
    }

    @Override
    public void restoreFrom(Entity source) {
        super.restoreFrom(source);
        if (kind == Kind.TUNNEL_BORE && source.level() != level()) {
            links[0] = null;
            links[1] = null;
            railRoot = null;
            railRoute = -1;
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() && level() instanceof ServerLevel level) {
            unlink(level, 0);
            unlink(level, 1);
            if (level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.ENTITY_DROPS)) {
                for (SimpleContainer inventory : List.of(cargo, overflow, supplies)) {
                    for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                        ItemStack stack = inventory.removeItemNoUpdate(slot);
                        if (!stack.isEmpty()) {
                            spawnAtLocation(level, stack);
                        }
                    }
                }
            }
        }
        super.remove(reason);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        railRoot = input.read("Rail", BlockPos.CODEC).orElse(null);
        railRoute = input.getIntOr("RailRoute", -1);
        links[0] = input.read("Front", UUIDUtil.CODEC).orElse(null);
        links[1] = input.read("Rear", UUIDUtil.CODEC).orElse(null);
        working = input.getBooleanOr("Working", false);
        if (kind == Kind.BALLAST_CAR) {
            int legacyMode = input.getBooleanOr("WideBallast", false) ? BallastMode.WIDE.ordinal() : BallastMode.NARROW.ordinal();
            ballastMode = BallastMode.byId(input.getIntOr("BallastMode", legacyMode));
        }
        fuel.load(input);
        entityData.set(HANDBRAKE, input.getBooleanOr("Handbrake", false));
        entityData.set(DOORS, input.getIntOr("Doors", 0) & 3);
        handbrakeMotion = new PartMotion(handbrakeApplied() ? 90 : 0);
        leftDoorMotion = new PartMotion(doorOpen(true) ? 1 : 0);
        rightDoorMotion = new PartMotion(doorOpen(false) ? 1 : 0);
        loadInventory(input, "Cargo", cargo);
        loadInventory(input, "Overflow", overflow);
        loadInventory(input, "Supplies", supplies);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        if (railRoot != null) {
            output.store("Rail", BlockPos.CODEC, railRoot);
            output.putInt("RailRoute", railRoute);
        }
        if (links[0] != null) {
            output.store("Front", UUIDUtil.CODEC, links[0]);
        }
        if (links[1] != null) {
            output.store("Rear", UUIDUtil.CODEC, links[1]);
        }
        output.putBoolean("Working", working);
        if (kind == Kind.BALLAST_CAR) output.putInt("BallastMode", ballastMode.ordinal());
        fuel.save(output);
        output.putBoolean("Handbrake", handbrakeApplied());
        if (kind == Kind.BOXCAR) output.putInt("Doors", entityData.get(DOORS));
        saveInventory(output, "Cargo", cargo);
        saveInventory(output, "Overflow", overflow);
        saveInventory(output, "Supplies", supplies);
    }

    private static void loadInventory(ValueInput input, String key, SimpleContainer inventory) {
        int slot = 0;
        for (ItemStack stack : input.listOrEmpty(key, ItemStack.OPTIONAL_CODEC)) {
            if (slot >= inventory.getContainerSize()) {
                break;
            }
            // Retain stacks saved before a kit's stack limit changed.
            inventory.getItems().set(slot++, stack);
        }
        inventory.setChanged();
    }

    private static void saveInventory(ValueOutput output, String key, SimpleContainer inventory) {
        var list = output.list(key, ItemStack.OPTIONAL_CODEC);
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            list.add(inventory.getItem(slot));
        }
    }

    private static void message(Player player, String key) {
        player.sendOverlayMessage(Component.translatable("message.trainmod." + key));
    }

    private static final class StockMenu extends ChestMenu {
        private final Kind stockKind;

        StockMenu(int id, Inventory player, SimpleContainer items, Kind kind) {
            super(MenuType.GENERIC_9x1, id, player, items, items.getContainerSize() / 9);
            stockKind = kind;
        }

        @Override
        protected Slot addSlot(Slot slot) {
            if (!(slot.container instanceof SimpleContainer)) return super.addSlot(slot);
            return super.addSlot(new Slot(slot.container, slot.getContainerSlot(), slot.x, slot.y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return switch (stockKind) {
                        case TENDER -> getContainerSlot() == 0 ? stack.is(Items.COAL) : stack.is(Items.WATER_BUCKET);
                        case LOCOMOTIVE -> stack.is(VehicleFuel.DIESEL);
                        default -> false;
                    };
                }

                @Override
                public int getMaxStackSize(ItemStack stack) {
                    return stockKind == Kind.LOCOMOTIVE || stockKind == Kind.TENDER && getContainerSlot() != 0
                        ? 1 : super.getMaxStackSize(stack);
                }
            });
        }
    }

    private static final class SupplyMenu extends ChestMenu {
        SupplyMenu(int id, Inventory player, SimpleContainer items) {
            super(MenuType.GENERIC_9x1, id, player, items, 1);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            if (index < 9) return super.quickMoveStack(player, index);
            Slot slot = slots.get(index);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack moved = stack.copy();
            if (!moveItemStackTo(stack, RAIL_SUPPLY, STONE_SUPPLY + 1, false)) return ItemStack.EMPTY;
            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
            else slot.setChanged();
            return moved;
        }

        @Override
        protected Slot addSlot(Slot slot) {
            if (slots.size() >= 9) {
                return super.addSlot(slot);
            }
            int index = slots.size();
            return super.addSlot(new Slot(slot.container, index, slot.x, slot.y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return switch (index) {
                        case RAIL_SUPPLY -> stack.is(Trainmod.RAIL.get());
                        case TIE_SUPPLY -> stack.is(Trainmod.TIE_BUNDLE.get());
                        case STONE_SUPPLY -> stack.is(Items.COBBLESTONE);
                        default -> false;
                    };
                }
            });
        }
    }
}
