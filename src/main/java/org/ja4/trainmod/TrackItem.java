package org.ja4.trainmod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class TrackItem extends Item {
    record Placement(BlockPos root, int rotation, boolean left, TrackGeometry.Shape shape, double score,
                     List<BlockPos> missingSupports) {
        boolean supported() { return missingSupports.isEmpty(); }
    }

    private final TrackGeometry.Kind kind;

    TrackItem(Properties properties, TrackGeometry.Kind kind) {
        super(properties);
        this.kind = kind;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable(kind.grade() ? "tooltip.trainmod.grade"
            : kind.flexibleStraight() ? "tooltip.trainmod.straight_track"
            : kind.handed() ? "tooltip.trainmod.handed_track" : "tooltip.trainmod.track")
            .withStyle(ChatFormatting.GRAY));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS;
        }
        Placement placement = findPlacement(level, context, player, kind, null);
        if (placement == null || !place(level, player, kind, placement)) {
            player.sendOverlayMessage(Component.translatable("message.trainmod.track_site"));
            return InteractionResult.FAIL;
        }
        context.getItemInHand().consume(1, player);
        return InteractionResult.SUCCESS;
    }

    TrackGeometry.Kind kind() { return kind; }

    static Placement findPlacement(Level level, UseOnContext context, Player player,
                                   TrackGeometry.Kind kind, Boolean fixedHand) {
        return pickPlacement(level, context, player, kind, fixedHand, false);
    }

    Placement preview(Level level, UseOnContext context, Player player) {
        return pickPlacement(level, context, player, kind, null, true);
    }

    private static Placement pickPlacement(Level level, UseOnContext context, Player player,
                                           TrackGeometry.Kind kind, Boolean fixedHand, boolean preview) {
        BlockPos clicked = context.getClickedPos();
        Vec3 look = Vec3.directionFromRotation(0, player.getYRot());
        List<Placement> candidates = new ArrayList<>();
        if (RailNetwork.isRail(level.getBlockState(clicked))) {
            addSnapped(level, player, context, kind, fixedHand, look, RailNetwork.exposedPorts(level, clicked), null, candidates, preview);
        } else {
            BlockPos target = level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(context.getClickedFace());
            List<RailNetwork.Port> nearby = new ArrayList<>();
            for (BlockPos cell : BlockPos.betweenClosed(target.offset(-1, -1, -1), target.offset(1, 1, 1))) {
                nearby.addAll(RailNetwork.exposedPorts(level, cell));
            }
            addSnapped(level, player, context, kind, fixedHand, look, nearby, target, candidates, preview);
            boolean diagonalApproach = nearby.stream().anyMatch(port -> target.equals(port.landing())
                && Math.abs(port.outward().x * port.outward().z) > 0.1 && port.outward().dot(look) > 0.5);
            if (candidates.stream().noneMatch(Placement::supported) && !diagonalApproach) {
                for (int rotation : kind.rotations()) {
                    var shape = kind.shape(rotation, true);
                    Vec3 heading = horizontal(shape.routes().getFirst().tangent(0));
                    double alignment = heading.dot(look);
                    if (alignment < 0.5) continue;
                    boolean hand = hand(player, context, heading, TrackGeometry.origin(target), fixedHand);
                    add(level, player, kind, target, rotation, hand, alignment, candidates, preview);
                }
            }
        }
        return candidates.stream().max(Comparator.comparing(Placement::supported).thenComparingDouble(Placement::score)).orElse(null);
    }

    private static void addSnapped(Level level, Player player, UseOnContext context, TrackGeometry.Kind kind,
                                   Boolean fixedHand, Vec3 look, List<RailNetwork.Port> ports, BlockPos target,
                                   List<Placement> candidates, boolean preview) {
        for (var port : ports) {
            if (target != null && !target.equals(port.landing())) continue;
            double bearing = horizontal(port.outward()).dot(look);
            double distance = port.point().distanceToSqr(context.getClickLocation());
            if (bearing < 0.5 || distance > 2.25) continue;
            Vec3 center = TrackGeometry.origin(port.landing());
            boolean wantLeft = hand(player, context, horizontal(port.outward()), center, fixedHand);
            for (int rotation : kind.rotations()) {
                for (boolean enteredAtEnd : new boolean[]{false, true}) {
                    boolean shapeLeft = wantLeft != enteredAtEnd;
                    var shape = kind.shape(rotation, shapeLeft);
                    for (var route : shape.routes()) {
                        Vec3 heading = horizontal(route.tangent(enteredAtEnd ? route.length : 0))
                            .scale(enteredAtEnd ? -1 : 1);
                        if (heading.dot(horizontal(port.outward())) < 0.99) continue;
                        Vec3 start = port.point().subtract(route.endpoint(enteredAtEnd));
                        BlockPos root = BlockPos.containing(start);
                        if (TrackGeometry.origin(root).distanceToSqr(start) > 1e-6) continue;
                        // At a switch throat use one diverging direction per item hand. The stock exits stay available.
                        add(level, player, kind, root, rotation, shapeLeft, bearing - distance * 0.15, candidates, preview);
                    }
                }
            }
        }
    }

    private static Vec3 horizontal(Vec3 vector) {
        return vector.multiply(1, 0, 1).normalize();
    }

    private static boolean hand(Player player, UseOnContext context, Vec3 heading, Vec3 center, Boolean fixedHand) {
        if (fixedHand != null) return fixedHand;
        Vec3 left = new Vec3(heading.z, 0, -heading.x);
        double side = context.getClickLocation().subtract(center).dot(left);
        return Math.abs(side) > 0.07 ? side > 0 : player.position().subtract(center).dot(left) >= 0;
    }

    private static void add(Level level, Player player, TrackGeometry.Kind kind, BlockPos root,
                            int rotation, boolean left, double score, List<Placement> candidates, boolean preview) {
        var shape = kind.shape(rotation, left);
        List<BlockPos> supports = new ArrayList<>();
        for (BlockPos offset : shape.cells()) {
            BlockPos pos = root.offset(offset);
            if (!level.isLoaded(pos) || !level.isInWorldBounds(pos) || !level.mayInteract(player, pos)
                || !level.getBlockState(pos).canBeReplaced() || !level.getFluidState(pos).isEmpty()) return;
            if (!TrackBlock.supported(level, pos, kind.grade() && offset.getY() == 1)) {
                if (!preview) return;
                supports.add(pos.below());
            }
        }
        candidates.add(new Placement(root, rotation, left, shape, score, List.copyOf(supports)));
    }

    static boolean place(ServerLevel level, ServerPlayer player, TrackGeometry.Kind kind, Placement placement) {
        TrackBlock block = Trainmod.TRACK.get(kind).get();
        var changes = new LinkedHashMap<BlockPos, BlockState>();
        List<BlockPos> cells = placement.shape().cells();
        for (int index = 0; index < cells.size(); index++) {
            changes.put(placement.root().offset(cells.get(index)), block.segment(placement.rotation(), placement.left(), index));
        }
        return TrackWork.apply(level, player, changes, level.getBlockState(placement.root().below()));
    }
}
