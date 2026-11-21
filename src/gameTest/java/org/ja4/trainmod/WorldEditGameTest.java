package org.ja4.trainmod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

final class WorldEditGameTest {
    private WorldEditGameTest() {}

    // The compatibility suite runs only when WorldEdit is installed in the test server.
    static void run(GameTestHelper test) {
        try {
            verifyLayouts(test);
            test.succeed();
        } catch (ReflectiveOperationException | java.io.IOException error) {
            throw test.assertionException("WorldEdit compatibility test failed: %s", error.getCause() == null
                ? error.toString() : error.getCause().toString());
        }
    }

    private static void verifyLayouts(GameTestHelper test) throws ReflectiveOperationException, java.io.IOException {
        Class<?> adapter = we("neoforge.NeoForgeAdapter");
        Object adapterInstance = adapter.getMethod("get").invoke(null);
        Object world = adapter.getMethod("fromNativeWorld", Level.class).invoke(adapterInstance, test.getLevel());
        var blockPosition = adapter.getMethod("adapt", BlockPos.class);
        Class<?> vector = we("math.BlockVector3");
        Class<?> region = we("regions.Region");
        Class<?> clipboard = we("extent.clipboard.Clipboard");
        Class<?> extent = we("extent.Extent");
        Class<?> operation = we("function.operation.Operation");
        var complete = we("function.operation.Operations").getMethod("complete", operation);
        Object format = we("extent.clipboard.io.BuiltInClipboardFormat").getField("SPONGE_SCHEMATIC").get(null);
        Class<?> affine = we("math.transform.AffineTransform");
        Object rotate = affine.getMethod("rotateY", double.class).invoke(affine.getConstructor().newInstance(), -90.0);
        Object mirror = affine.getMethod("scale", double.class, double.class, double.class)
            .invoke(affine.getConstructor().newInstance(), -1.0, 1.0, 1.0);
        Class<?> holderType = we("session.ClipboardHolder");
        Class<?> pasteBuilder = we("session.PasteBuilder");
        var level = test.getLevel();

        for (TrackGeometry.Kind kind : TrackGeometry.Kind.values()) {
            BlockPos root = test.absolutePos(new BlockPos(0, 84 + kind.ordinal() * 7, 0));
            int startingRotation = kind == TrackGeometry.Kind.DIAGONAL ? 14 : 0;
            RailwayGameTests.piece(level, root, kind, startingRotation, true);
            var cells = kind.shape(startingRotation, true).cells();
            int minX = cells.stream().mapToInt(BlockPos::getX).min().orElseThrow();
            int minZ = cells.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
            int maxX = cells.stream().mapToInt(BlockPos::getX).max().orElseThrow();
            int maxY = cells.stream().mapToInt(BlockPos::getY).max().orElseThrow();
            int maxZ = cells.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
            Object origin = blockPosition.invoke(adapterInstance, root);
            Object min = blockPosition.invoke(adapterInstance, root.offset(minX, -1, minZ));
            Object max = blockPosition.invoke(adapterInstance, root.offset(maxX, maxY, maxZ));
            Object bounds = we("regions.CuboidRegion").getConstructor(vector, vector).newInstance(min, max);
            Object copied = we("extent.clipboard.BlockArrayClipboard").getConstructor(region).newInstance(bounds);
            clipboard.getMethod("setOrigin", vector).invoke(copied, origin);
            Object copyOperation = we("function.operation.ForwardExtentCopy")
                .getConstructor(extent, region, vector, extent, vector).newInstance(world, bounds, origin, copied, origin);
            complete.invoke(null, copyOperation);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (var writer = (AutoCloseable) we("extent.clipboard.io.ClipboardFormat").getMethod("getWriter", OutputStream.class).invoke(format, bytes)) {
                we("extent.clipboard.io.ClipboardWriter").getMethod("write", clipboard).invoke(writer, copied);
            } catch (ReflectiveOperationException e) {
                throw e;
            } catch (Exception e) {
                throw new java.io.IOException(e);
            }

            Object loaded;
            try (var reader = (AutoCloseable) we("extent.clipboard.io.ClipboardFormat").getMethod("getReader", InputStream.class)
                    .invoke(format, new ByteArrayInputStream(bytes.toByteArray()))) {
                loaded = we("extent.clipboard.io.ClipboardReader").getMethod("read").invoke(reader);
            } catch (ReflectiveOperationException e) {
                throw e;
            } catch (Exception e) {
                throw new java.io.IOException(e);
            }

            Object holder = holderType.getConstructor(clipboard).newInstance(loaded);
            int pasteIndex = 0;
            for (Object transform : new Object[]{rotate, mirror}) {
                BlockPos target = root.above(3 + 3 * pasteIndex);
                holderType.getMethod("setTransform", we("math.transform.Transform")).invoke(holder, transform);
                Object paste = holderType.getMethod("createPaste", extent).invoke(holder, world);
                pasteBuilder.getMethod("to", vector).invoke(paste, blockPosition.invoke(adapterInstance, target));
                complete.invoke(null, pasteBuilder.getMethod("build").invoke(paste));
                RailNetwork.Rail rail = RailNetwork.railAt(level, target);
                test.assertTrue(rail != null, kind + " resolves after a WorldEdit schematic copy and transform");
                var position = RailwayGameTests.at(level, target);
                test.assertTrue(RailNetwork.advance(level, position, 0.1) != null,
                    kind + " remains traversable after the schematic paste");
                if (rail.cells().size() > 1) {
                    level.setBlockAndUpdate(rail.cells().getLast(), Blocks.AIR.defaultBlockState());
                    test.assertTrue(RailNetwork.railAt(level, target) == null, "A partial WorldEdit paste is invalid");
                }
                pasteIndex++;
            }
        }

        var girder = Trainmod.STEEL_GIRDER.get();
        var root = test.absolutePos(new BlockPos(0, 253, 0));
        level.setBlockAndUpdate(root, girder.defaultBlockState().setValue(BridgeBlock.AXIS, Direction.Axis.Z));
        Object origin = blockPosition.invoke(adapterInstance, root);
        Object bounds = we("regions.CuboidRegion").getConstructor(vector, vector).newInstance(origin, origin);
        Object copied = we("extent.clipboard.BlockArrayClipboard").getConstructor(region).newInstance(bounds);
        clipboard.getMethod("setOrigin", vector).invoke(copied, origin);
        complete.invoke(null, we("function.operation.ForwardExtentCopy").getConstructor(extent, region, vector, extent, vector)
            .newInstance(world, bounds, origin, copied, origin));
        Object holder = holderType.getConstructor(clipboard).newInstance(copied);
        holderType.getMethod("setTransform", we("math.transform.Transform")).invoke(holder, rotate);
        Object paste = holderType.getMethod("createPaste", extent).invoke(holder, world);
        BlockPos target = root.above(2);
        pasteBuilder.getMethod("to", vector).invoke(paste, blockPosition.invoke(adapterInstance, target));
        complete.invoke(null, pasteBuilder.getMethod("build").invoke(paste));
        var placed = level.getBlockState(target);
        test.assertTrue(placed.is(girder) && placed.getValue(BridgeBlock.AXIS) == Direction.Axis.X,
            "WorldEdit pastes an ordinary girder block and rotates the span axis");

    }

    private static Class<?> we(String name) throws ClassNotFoundException {
        return Class.forName("com.sk89q.worldedit." + name);
    }
}
