package org.ja4.trainmod;

import com.mojang.blaze3d.platform.Transparency;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

final class BallastSurface {
    private static final int GRID_STEPS = 6;
    private static final double JOIN_DISTANCE_SQR = BallastProfile.JOIN_DISTANCE * BallastProfile.JOIN_DISTANCE;

    private final BlockPos position;
    private final Material.Baked gravel;
    private final BakedQuad.MaterialInfo material;
    private final List<BakedQuad> quads = new ArrayList<>();

    private BallastSurface(BlockPos position, Material.Baked gravel) {
        this.position = position;
        this.gravel = gravel;
        material = BakedQuad.MaterialInfo.of(gravel, Transparency.NONE, -1, Direction.UP, 0, false);
    }

    static boolean covered(BlockState state) {
        return state.getBlock() instanceof TrackBlock track && track.kind.hasBallast()
            && track.index(state) < track.shape(state).cells().size();
    }

    static void addRailEnds(BlockAndTintGetter level, BlockPos pos, BlockState state, Material.Baked gravel,
                            List<BlockStateModelPart> parts) {
        if (!(state.getBlock() instanceof TrackBlock track)) return;
        var shape = track.shape(state);
        int index = track.index(state);
        if (index >= shape.cells().size()) return;
        BlockPos cell = shape.cells().get(index);
        BlockPos root = pos.subtract(cell);
        boolean raised = level.getBlockState(pos.below()).is(Trainmod.BALLAST.get());
        BallastSurface surface = null;
        for (var port : TrackEnds.at(shape, cell, root)) {
            var connection = TrackEnds.connection(level, root, port);
            if (connection == TrackEnds.Connection.RAIL) continue;
            BlockPos ahead = raised && connection == TrackEnds.Connection.OPEN
                ? foundationAhead(level, pos.below(), port) : null;
            if (ahead != null && covered(level.getBlockState(ahead.above()))) continue;
            if (surface == null) surface = new BallastSurface(pos, gravel);
            double ground = (raised ? pos.getY() - 1 : track.kind.grade() ? root.getY() : port.center().y)
                + BallastProfile.FOOT;
            if (ahead != null) {
                surface.joinBareBallast(port, ground, ahead);
            } else {
                surface.squareEnd(port, BallastProfile.bed(raised), ground);
            }
        }
        if (surface != null) surface.addPart(parts, false, false);
    }

    private static @Nullable BlockPos foundationAhead(BlockAndTintGetter level, BlockPos support, TrackEnds.Port port) {
        BlockPos landing = port.landing().below();
        if (level.getBlockState(landing).is(Trainmod.BALLAST.get())) return landing;
        BlockPos closest = null;
        double distance = Double.POSITIVE_INFINITY;
        var neighbor = new BlockPos.MutableBlockPos();
        for (int dz = -BallastProfile.JOIN_DISTANCE; dz <= BallastProfile.JOIN_DISTANCE; dz++) {
            for (int dx = -BallastProfile.JOIN_DISTANCE; dx <= BallastProfile.JOIN_DISTANCE; dx++) {
                if (dx * dx + dz * dz > JOIN_DISTANCE_SQR) continue;
                neighbor.setWithOffset(support, dx, 0, dz);
                Vec3 offset = Vec3.atCenterOf(neighbor).subtract(port.center()).multiply(1, 0, 1);
                double forward = offset.dot(port.outward());
                double candidate = offset.lengthSqr();
                if (candidate < distance && forward > 0 && offset.subtract(port.outward().scale(forward)).lengthSqr() < 0.56
                    && level.getBlockState(neighbor).is(Trainmod.BALLAST.get())) {
                    closest = neighbor.immutable();
                    distance = candidate;
                }
            }
        }
        return closest;
    }

    private void joinBareBallast(TrackEnds.Port port, double ground, BlockPos foundation) {
        Vec3 out = port.outward();
        Vec3 right = new Vec3(out.z, 0, -out.x);
        Vec3 center = Vec3.atCenterOf(foundation);
        double halfBlock = 0.5 / Math.max(Math.abs(out.x), Math.abs(out.z));
        double length = center.subtract(port.center()).dot(out) + halfBlock;
        double rise = port.center().y + BallastProfile.CREST - ground;
        for (int strip = 0; strip < 12; strip++) {
            double width0 = strip / 4.0 - BallastProfile.WIDTH;
            double width1 = width0 + 0.25;
            Vec3 a = port.center().add(right.scale(width0));
            Vec3 b = port.center().add(right.scale(width1));
            Vec3 tipA = a.add(out.scale(length));
            Vec3 tipB = b.add(out.scale(length));
            a = a.with(Direction.Axis.Y, ground + rise * BallastProfile.foundationHeight(width0, 0));
            b = b.with(Direction.Axis.Y, ground + rise * BallastProfile.foundationHeight(width1, 0));
            tipA = tipA.with(Direction.Axis.Y, foundationTop(foundation, center, tipA));
            tipB = tipB.with(Direction.Axis.Y, foundationTop(foundation, center, tipB));
            float u0 = (strip % 4) / 4f, u1 = u0 + 0.25f;
            for (int tile = 0; tile < length; tile++) {
                double span = Math.min(1, length - tile);
                double start = tile / length, middle = (tile + span * 0.5) / length, end = (tile + span) / length;
                quad(a.lerp(tipA, start), a.lerp(tipA, middle), b.lerp(tipB, middle), b.lerp(tipB, start),
                    uv(u0, 0), uv(u0, (float) (span * 0.5)), uv(u1, (float) (span * 0.5)), uv(u1, 0));
                quad(a.lerp(tipA, middle), a.lerp(tipA, end), b.lerp(tipB, end), b.lerp(tipB, middle),
                    uv(u0, (float) (span * 0.5)), uv(u0, (float) span), uv(u1, (float) span), uv(u1, (float) (span * 0.5)));
            }
        }
    }

    private static double foundationTop(BlockPos foundation, Vec3 center, Vec3 point) {
        return foundation.getY() + BallastProfile.foundationHeight(point.x - center.x, point.z - center.z);
    }

    private void squareEnd(TrackEnds.Port port, BallastProfile.Section[] profile, double ground) {
        Vec3 right = new Vec3(port.outward().z, 0, -port.outward().x);
        Vec3 run = port.outward().scale(BallastProfile.END_LENGTH);
        double crest = port.center().y + BallastProfile.CREST;
        for (int i = 1; i < profile.length; i++) {
            var first = profile[i - 1];
            var second = profile[i];
            double h0 = endWeight(first), h1 = endWeight(second);
            double y0 = ground + (crest - ground) * h0, y1 = ground + (crest - ground) * h1;
            Vec3 a = port.center().add(right.scale(first.across())).with(Direction.Axis.Y, y0);
            Vec3 b = port.center().add(right.scale(second.across())).with(Direction.Axis.Y, y1);
            Vec3 foldA = a.add(run.scale(1 - h0));
            Vec3 foldB = b.add(run.scale(1 - h1));
            double tile = Math.floor(first.across() + BallastProfile.WIDTH + 1e-8);
            float u0 = (float) (first.across() + BallastProfile.WIDTH - tile);
            float u1 = (float) (second.across() + BallastProfile.WIDTH - tile);
            float v0 = (float) (1 - h0), v1 = (float) (1 - h1);
            if (v0 > 0 || v1 > 0) {
                quad(a, foldA, foldB, b, uv(u0, 0), uv(u0, v0), uv(u1, v1), uv(u1, 0));
            }
            if (v0 < 1 || v1 < 1) {
                Vec3 tipA = a.add(run).with(Direction.Axis.Y, ground);
                Vec3 tipB = b.add(run).with(Direction.Axis.Y, ground);
                quad(foldA, tipA, tipB, foldB, uv(u0, v0), uv(u0, 1), uv(u1, 1), uv(u1, v1));
            }
        }
    }

    private static double endWeight(BallastProfile.Section section) {
        return (section.height() - BallastProfile.FOOT) / (BallastProfile.CREST - BallastProfile.FOOT);
    }

    static void addFoundation(BlockAndTintGetter level, BlockPos pos, Material.Baked gravel,
                              List<BlockStateModelPart> parts) {
        var sources = new ArrayList<Vec3>();
        Vec3 origin = Vec3.atCenterOf(pos);
        int search = BallastProfile.SEARCH_DISTANCE;
        var cursor = new BlockPos.MutableBlockPos();
        for (int dz = -search; dz <= search; dz++) {
            for (int dx = -search; dx <= search; dx++) {
                cursor.setWithOffset(pos, dx, 0, dz);
                var state = level.getBlockState(cursor);
                if (!state.is(Trainmod.BALLAST.get())) continue;
                sources.add(Vec3.atCenterOf(cursor));
            }
        }
        var surface = new BallastSurface(pos, gravel);
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                Vec3 center = origin.add(dx, 0, dz);
                if (ownsTile(origin, sources, center)) surface.foundationTile(center, sources);
            }
        }
        // Sample light in the space above the ballast, just as the track's bed does.
        boolean lightFromAbove = level.getBlockState(pos.above()).getFaceOcclusionShape(Direction.DOWN).isEmpty();
        surface.addPart(parts, true, lightFromAbove);
    }

    private static boolean ownsTile(Vec3 origin, List<Vec3> sources, Vec3 center) {
        double distance = origin.distanceToSqr(center);
        for (Vec3 source : sources) {
            double other = source.distanceToSqr(center);
            if (other < distance || other == distance
                && (source.z < origin.z || source.z == origin.z && source.x < origin.x)) return false;
        }
        return true;
    }

    private void foundationTile(Vec3 center, List<Vec3> sources) {
        double reach = BallastProfile.JOIN_WIDTH + Math.sqrt(0.5);
        double limit = reach + BallastProfile.JOIN_DISTANCE;
        var local = sources.stream().filter(source -> source.distanceToSqr(center) <= limit * limit).toList();
        var joins = new ArrayList<Join>();
        for (int i = 0; i < local.size(); i++) {
            Vec3 from = local.get(i);
            for (int j = i + 1; j < local.size(); j++) {
                Vec3 to = local.get(j);
                double distance = from.distanceToSqr(to);
                if (distance > JOIN_DISTANCE_SQR) continue;
                Vec3 run = to.subtract(from);
                double along = Math.clamp(center.subtract(from).dot(run) / distance, 0, 1);
                if (from.add(run.scale(along)).distanceToSqr(center) <= reach * reach) {
                    joins.add(new Join(from, run, distance));
                }
            }
        }
        Vec3[][] vertices = new Vec3[GRID_STEPS + 1][GRID_STEPS + 1];
        for (int z = 0; z <= GRID_STEPS; z++) {
            for (int x = 0; x <= GRID_STEPS; x++) {
                Vec3 point = center.add(x / (double) GRID_STEPS - 0.5, 0, z / (double) GRID_STEPS - 0.5);
                double height = 0;
                for (Vec3 source : local) {
                    height = Math.max(height, BallastProfile.foundationHeight(point.x - source.x, point.z - source.z));
                }
                for (Join join : joins) height = Math.max(height, join.height(point));
                vertices[z][x] = point.with(Direction.Axis.Y, position.getY() + height);
            }
        }
        for (int z = 0; z < GRID_STEPS; z++) {
            for (int x = 0; x < GRID_STEPS; x++) {
                Vec3 a = vertices[z][x], b = vertices[z + 1][x];
                Vec3 c = vertices[z + 1][x + 1], d = vertices[z][x + 1];
                if (Math.max(Math.max(a.y, b.y), Math.max(c.y, d.y)) <= position.getY()) continue;
                float u = x / (float) GRID_STEPS, uNext = (x + 1) / (float) GRID_STEPS;
                float v = z / (float) GRID_STEPS, vNext = (z + 1) / (float) GRID_STEPS;
                if (Math.abs(b.y - d.y) > Math.abs(a.y - c.y)) {
                    quad(b, c, d, a, uv(u, vNext), uv(uNext, vNext), uv(uNext, v), uv(u, v));
                } else {
                    quad(a, b, c, d, uv(u, v), uv(u, vNext), uv(uNext, vNext), uv(uNext, v));
                }
            }
        }
    }

    private record Join(Vec3 start, Vec3 run, double lengthSqr) {
        double height(Vec3 point) {
            Vec3 offset = point.subtract(start);
            double progress = offset.dot(run) / lengthSqr;
            if (progress <= 0 || progress >= 1) return 0;
            return BallastProfile.joinHeight(Math.sqrt(lengthSqr), progress, offset.subtract(run.scale(progress)).length());
        }
    }

    private Vector3f local(Vec3 point) {
        return new Vector3f((float) (point.x - position.getX()), (float) (point.y - position.getY()),
            (float) (point.z - position.getZ()));
    }

    private long uv(float u, float v) { return UVPair.pack(gravel.sprite().getU(u), gravel.sprite().getV(v)); }

    private void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, long uvA, long uvB, long uvC, long uvD) {
        quads.add(new BakedQuad(local(a), local(b), local(c), local(d), uvA, uvB, uvC, uvD, Direction.UP, material));
    }

    private void addPart(List<BlockStateModelPart> parts, boolean foundation, boolean lightFromAbove) {
        List<BakedQuad> underside = List.of();
        if (foundation) {
            var down = BakedQuad.MaterialInfo.of(gravel, Transparency.NONE, -1, Direction.DOWN, 0, false);
            underside = List.of(new BakedQuad(new Vector3f(), new Vector3f(1, 0, 0), new Vector3f(1, 0, 1),
                new Vector3f(0, 0, 1), uv(0, 0), uv(1, 0), uv(1, 1), uv(0, 1), Direction.DOWN, down));
        }
        if (!quads.isEmpty() || foundation) parts.add(new SurfacePart(quads, underside, gravel, material.flags(), lightFromAbove));
    }

    private record SurfacePart(List<BakedQuad> quads, List<BakedQuad> underside, Material.Baked particleMaterial,
                               int materialFlags, boolean lightFromAbove)
        implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(@Nullable Direction side) {
            if (side == Direction.DOWN) return underside;
            return side == (lightFromAbove ? Direction.UP : null) ? quads : List.of();
        }

        @Override
        public boolean useAmbientOcclusion() { return false; }
    }
}
