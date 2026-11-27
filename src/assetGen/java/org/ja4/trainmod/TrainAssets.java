package org.ja4.trainmod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public final class TrainAssets {
    private static final Gson JSON = new Gson();
    private static final List<String> FACES = List.of("north", "south", "west", "east", "up", "down");
    private final Path root;
    private final Set<Integer> paint = new HashSet<>();

    private TrainAssets(Path root) { this.root = root; }

    public static void main(String[] args) throws IOException {
        TrainAssets assets = new TrainAssets(Path.of(args[0]));
        assets.rails();
        assets.railwayBlocks();
        assets.infrastructure();
        assets.vehicles();
        assets.tools();
        assets.recipes();
    }

    private void rails() throws IOException {
        texture("block/creosote", 0x604435, 16);
        texture("block/steel_head", 0xa4a6a5, 16);
        texture("block/steel_web", 0x524948, 16);
        for (TrackGeometry.Kind kind : TrackGeometry.Kind.values()) {
            String id = kind.id() + "_rail";
            write("assets/trainmod/blockstates/" + id + ".json", Map.of("variants",
                Map.of("", Map.of("type", "trainmod:procedural_track"))));
            if (kind.hasKit()) trackItem(kind, id);
            trackLoot(id, switch (kind) {
                case DIAGONAL -> "straight_rail";
                case UNBALLASTED_STRAIGHT -> "straight_rail";
                default -> id;
            });
        }
    }

    private void obj(String name, ObjMesh mesh, Map<String, String> textures, boolean inventory) throws IOException {
        String path = "models/block/" + name;
        writeText("assets/trainmod/" + path + ".obj", mesh.contents());
        var settings = new HashMap<String, Object>(Map.of("loader", "neoforge:obj",
            "model", "trainmod:" + path + ".obj", "textures", textures,
            "ambientocclusion", false, "automatic_culling", false, "flip_v", true, "emissive_ambient", false,
            "shade_quads", mesh.shadeQuads()));
        if (inventory) {
            settings.put("parent", "minecraft:block/block");
            settings.put("display", Map.of("gui", Map.of("rotation", vector(27, 150, 0),
                "translation", vector(0, -2.5, 0), "scale", vector(0.47, 0.47, 0.47))));
        }
        write("assets/trainmod/" + path + ".json", settings);
    }

    private void trackItem(TrackGeometry.Kind kind, String id) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        var routes = kind.shape(kind.rotation(0), true).routes();
        double maxX = routes.stream().flatMap(path -> path.points().stream()).mapToDouble(point -> Math.abs(point.x)).max().orElse(1);
        double scale = Math.min(3.0, 6.0 / Math.max(1, maxX));
        double far = routes.stream().flatMap(path -> path.points().stream()).mapToDouble(point -> point.z).max().orElse(1);
        double near = routes.stream().flatMap(path -> path.points().stream()).mapToDouble(point -> point.z).min().orElse(0);
        for (int layer = 1; layer < 3; layer++) {
            graphics.setColor(new java.awt.Color(new int[]{0x777168, 0x664938, 0xbfc0b8}[layer]));
            graphics.setStroke(new java.awt.BasicStroke(layer == 0 ? 5 : layer == 1 ? 4 : 1));
            for (TrackGeometry.Path path : routes) {
                for (int rail = layer == 2 ? -1 : 0; rail <= (layer == 2 ? 1 : 0); rail += layer == 2 ? 2 : 1) {
                    java.awt.geom.Path2D line = new java.awt.geom.Path2D.Double();
                    for (int i = 0; i < path.points().size(); i++) {
                        var p = path.points().get(i);
                        double x = 7.5 + p.x * scale + rail * 1.35;
                        double y = 13 - 10.5 * (p.z - near) / (far - near);
                        if (i == 0) line.moveTo(x, y);
                        else line.lineTo(x, y);
                    }
                    graphics.draw(line);
                }
            }
        }
        graphics.dispose();
        generatedItem(id, image);
    }

    private void trackLoot(String id, String item) throws IOException {
        write("data/trainmod/loot_table/blocks/" + id + ".json", Map.of("type", "minecraft:block", "pools",
            List.of(Map.of("rolls", 1, "conditions", List.of(Map.of("condition", "minecraft:survives_explosion"),
                Map.of("condition", "minecraft:block_state_property", "block", "trainmod:" + id, "properties", Map.of("segment", "0")),
                Map.of("condition", "minecraft:inverted", "term", Map.of("condition", "minecraft:block_state_property",
                    "block", "trainmod:" + id, "properties", Map.of("condition", "broken")))),
                "entries", List.of(Map.of("type", "minecraft:item", "name", "trainmod:" + item))))));
    }

    private void railwayBlocks() throws IOException {
        model("ballast_surface", Map.of("particle", "minecraft:block/gravel"), List.of());
        write("assets/trainmod/blockstates/ballast.json", Map.of("variants", Map.of("", Map.of(
            "type", "trainmod:adjacent_ballast", "foundation", true, "separate", ref("ballast_surface")))));
        model("ballast_item", Map.of("rock", "minecraft:block/gravel", "particle", "minecraft:block/gravel"),
            List.of(ballastItemBox(0, 0, 16, 5), ballastItemBox(2, 5, 14, 10), ballastItemBox(4, 10, 12, 15)));
        item("ballast", "block/ballast_item");
        loot("ballast", "trainmod:ballast");

        texture("block/cast_iron", 0x343840, 16);
        texture("block/white_enamel", 0xe3dcc5, 16);
        texture("block/red_enamel", 0xbe2f2b, 16);
        model("signal_retired", Map.of("metal", "trainmod:block/cast_iron", "glass", "trainmod:block/red_enamel",
            "particle", "trainmod:block/cast_iron"), List.of(cube(3, 0, 3, 13, 2, 13, "metal"),
                cube(7, 2, 7, 9, 13, 9, "metal"), cube(4.5, 9, 5.5, 11.5, 16, 10.5, "metal"),
                cube(6, 10.5, 5.1, 10, 14.5, 5.5, "glass"), cube(6, 10.5, 10.5, 10, 14.5, 10.9, "glass")));
        Map<String, Object> signalStates = new HashMap<>();
        List<String> facing = List.of("north", "east", "south", "west");
        for (int i = 0; i < 4; i++) {
            signalStates.put("facing=" + facing.get(i), Map.of("model", "trainmod:block/signal_retired", "y", i * 90));
        }
        write("assets/trainmod/blockstates/signal.json", Map.of("variants", signalStates));
        switchStands();
        bufferStops();
        railExtruder();
        item("switch_stand", "block/switch_stand_straight");
        item("signal", "block/signal_retired");
        loot("switch_stand", "trainmod:switch_stand");
        loot("signal", "trainmod:signal");
    }

    private static Map<String, Object> ballastItemBox(double left, double bottom, double right, double top) {
        var box = cube(left, bottom, 0, right, top, 16, "rock");
        var faces = new HashMap<String, Object>();
        FACES.forEach(side -> faces.put(side, cubeUv("#rock", 16, 16)));
        box.put("faces", faces);
        return box;
    }

    private void railExtruder() throws IOException {
        model("rail_extruder", Map.of("frame", "trainmod:block/cast_iron", "casing", "minecraft:block/iron_block",
                "roller", "minecraft:block/copper_block", "rail", "trainmod:block/steel_head", "particle", "trainmod:block/cast_iron"),
            List.of(cube(0, 0, 0, 16, 12, 16, "frame"), cube(1, 4, 0, 15, 10, 1, "casing"),
                cube(0, 12, 0, 3, 16, 16, "casing"), cube(13, 12, 0, 16, 16, 16, "casing"),
                cube(3, 12, 3, 13, 15, 6, "roller"), cube(3, 12, 10, 13, 15, 13, "roller"),
                cube(6, 6, 0, 10, 8, 0.5, "rail")));
        write("assets/trainmod/blockstates/rail_extruder.json", Map.of("variants", Map.of("", ref("rail_extruder"))));
        item("rail_extruder", "block/rail_extruder");
        loot("rail_extruder", "trainmod:rail_extruder");
        write("data/minecraft/tags/block/mineable/pickaxe.json", Map.of("values", List.of("trainmod:rail_extruder")));
    }

    private void infrastructure() throws IOException {
        signalCabin();
        bridgeModels();
        catenaryTowers();
        for (String id : List.of("wood_trestle", "steel_girder", "stone_arch", "catenary_single",
            "catenary_double", "catenary_gantry")) {
            item(id, "block/" + id);
            loot(id, "trainmod:" + id);
        }
        item("wide_steel_girder", "item/wide_steel_girder");
        write("assets/trainmod/models/item/wide_steel_girder.json", Map.of("parent", "trainmod:block/wide_steel_girder",
            "display", Map.of("gui", Map.of("rotation", vector(30, 225, 0), "scale", vector(0.34, 0.34, 0.34)),
                "fixed", Map.of("scale", vector(0.31, 0.31, 0.31)))));
        write("data/trainmod/loot_table/blocks/wide_steel_girder.json", Map.of("type", "minecraft:block", "pools", List.of(
            Map.of("rolls", 1, "conditions", List.of(Map.of("condition", "minecraft:survives_explosion"),
                Map.of("condition", "minecraft:block_state_property", "block", "trainmod:wide_steel_girder",
                    "properties", Map.of("part", "1"))),
                "entries", List.of(Map.of("type", "minecraft:item", "name", "trainmod:wide_steel_girder"))))));
    }

    private void signalCabin() throws IOException {
        Map<String, Object> states = new HashMap<>();
        Map<String, String> textures = Map.of("panel", "trainmod:block/signal_cabin_panel",
            "door", "trainmod:block/signal_cabin_door", "roof", "trainmod:block/signal_cabin_roof",
            "trim", "trainmod:block/signal_cabin_trim", "hardware", "trainmod:block/signal_cabin_hardware",
            "lock", "trainmod:block/signal_cabin_lock", "particle", "trainmod:block/signal_cabin_panel");
        StringBuilder materials = new StringBuilder();
        for (String key : List.of("panel", "door", "roof", "trim", "hardware", "lock")) {
            materials.append("newmtl ").append(key).append("\nKd 1 1 1\nmap_Kd #").append(key).append("\n");
        }
        writeText("assets/trainmod/models/block/signal_cabin.mtl", materials.toString());
        texture("block/signal_cabin_panel", 0x878e92, 16);
        texture("block/signal_cabin_door", 0x717a7f, 16);
        texture("block/signal_cabin_roof", 0x5c6468, 16);
        texture("block/signal_cabin_trim", 0x51585d, 16);
        texture("block/signal_cabin_hardware", 0x383f44, 16);
        texture("block/signal_cabin_lock", 0xa79053, 16);
        for (String half : List.of("lower", "upper")) {
            boolean upper = half.equals("upper");
            for (String hinge : List.of("left", "right")) {
                for (boolean open : new boolean[]{false, true}) {
                    String name = "signal_cabin_" + half + "_" + hinge + (open ? "_open" : "_closed");
                    obj(name, SignalCabinMesh.create(!upper, upper, open, hinge.equals("right")), textures, false);
                    for (var facing : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        String variant = "facing=" + facing.getName() + ",half=" + half + ",hinge=" + hinge + ",open=" + open;
                        states.put(variant, directionRef(name, 90 * facing.get2DDataValue()));
                    }
                }
            }
        }
        write("assets/trainmod/blockstates/signal_cabin.json", Map.of("variants", states));
        obj("signal_cabin", SignalCabinMesh.create(true, true, false, true), textures, true);
        item("signal_cabin", "block/signal_cabin");
        write("data/trainmod/loot_table/blocks/signal_cabin.json", Map.of("type", "minecraft:block", "pools", List.of(
            Map.of("rolls", 1, "conditions", List.of(Map.of("condition", "minecraft:survives_explosion"),
                Map.of("condition", "minecraft:block_state_property", "block", "trainmod:signal_cabin",
                    "properties", Map.of("half", "lower"))),
                "entries", List.of(Map.of("type", "minecraft:item", "name", "trainmod:signal_cabin"))))));
    }

    private void bridgeModels() throws IOException {
        var timber = Map.of("wood", "trainmod:block/creosote", "deck", "minecraft:block/spruce_planks",
            "particle", "minecraft:block/spruce_planks");
        var trestleZ = new HashMap<String, Object>();
        var trestleX = new HashMap<String, Object>();
        for (int depth = 0; depth < TrestleGeometry.HEIGHT; depth++) {
            String id = depth == 0 ? "wood_trestle" : "wood_trestle_" + depth;
            var parts = new ArrayList<Map<String, Object>>();
            double top = TrestleGeometry.offset(depth);
            double bottom = TrestleGeometry.offset(depth + 1);
            double center = (top + bottom) / 2;
            double angle = Math.toDegrees(Math.atan2(bottom - top, 16));
            double halfLength = Math.hypot(bottom - top, 16) / 2;
            for (int side : new int[]{-1, 1}) {
                double x = 8 + side * center;
                for (int z : new int[]{3, 13}) {
                    double halfWidth = TrestleGeometry.LEG_HALF_WIDTH;
                    var leg = cube(x - halfWidth, 8 - halfLength, z - 1, x + halfWidth, 8 + halfLength, z + 1, "wood");
                    leg.put("rotation", Map.of("origin", vector(x, 8, z), "z", round(side * angle)));
                    parts.add(leg);
                }
                parts.add(cube(x - 0.65, 6, 3, x + 0.65, 7.35, 13, "wood"));
            }
            if (depth == 0) parts.add(cube(0, 13, 0, 16, 16, 16, "deck"));
            model(id, timber, parts);
            String key = depth == 0 ? "base" : "depth_" + depth;
            trestleZ.put(key, ref(id));
            trestleX.put(key, directionRef(id, 90));
        }
        write("assets/trainmod/blockstates/wood_trestle.json", Map.of("variants",
            Map.of("axis=z", bridgeVariant(trestleZ), "axis=x", bridgeVariant(trestleX))));

        var steel = Map.of("metal", "trainmod:block/steel_web", "flange", "trainmod:block/steel_web",
            "particle", "trainmod:block/steel_web");
        var girders = new ArrayList<Map<String, Object>>();
        var foundations = new ArrayList<Map<String, Object>>();
        var columns = new ArrayList<Map<String, Object>>();
        for (int x : new int[]{2, 12}) {
            girders.add(cube(x - 1, 8, 0, x + 3, 9.5, 16, "flange"));
            girders.add(cube(x, 9.5, 0, x + 2, 14.5, 16, "metal"));
            girders.add(cube(x - 1, 14.5, 0, x + 3, 16, 16, "flange"));
            foundations.add(cube(x - 1, 0, 5, x + 3, 1.5, 11, "flange"));
            foundations.add(cube(x, 1.5, 6, x + 2, 8, 10, "metal"));
            columns.add(cube(x - 1, 0, 5, x + 3, 16, 6, "metal"));
            columns.add(cube(x - 1, 0, 10, x + 3, 16, 11, "metal"));
            columns.add(cube(x + 0.5, 0, 6, x + 1.5, 16, 10, "metal"));
        }
        girders.add(cube(3, 10.5, 3, 13, 12, 4.75, "metal"));
        girders.add(cube(3, 10.5, 11.25, 13, 12, 13, "metal"));
        girders.add(rotated(1.3, 11.4, 7.1, 14.7, 12.5, 8.9, "metal", 8, 12, 8, 45, 0));
        girders.add(rotated(1.3, 11.7, 7.1, 14.7, 12.8, 8.9, "metal", 8, 12, 8, -45, 0));
        model("steel_girder", steel, girders);
        model("steel_girder_foot", steel, foundations);
        model("steel_girder_column", steel, columns);
        model("steel_girder_side", steel, List.of(cube(13, 10, 3, 16, 12.5, 4.75, "metal"),
            cube(13, 10, 11.25, 16, 12.5, 13, "metal")));
        write("assets/trainmod/blockstates/steel_girder.json", Map.of("variants", Map.of(
            "axis=z", bridgeVariant(Map.of("base", ref("steel_girder"), "foot", ref("steel_girder_foot"), "column", ref("steel_girder_column"),
                "east", ref("steel_girder_side"), "west", directionRef("steel_girder_side", 180))),
            "axis=x", bridgeVariant(Map.of("base", directionRef("steel_girder", 90), "foot", directionRef("steel_girder_foot", 90), "column", directionRef("steel_girder_column", 90),
                "south", directionRef("steel_girder_side", 90), "north", directionRef("steel_girder_side", 270))))));
        wideGirderModels(steel);

        var stone = Map.of("stone", "minecraft:block/stone_bricks", "particle", "minecraft:block/stone_bricks");
        model("stone_arch", stone, List.of(cube(0, 11, 0, 16, 16, 16, "stone")));
        model("stone_arch_column", stone, List.of(cube(0, 0, 0, 16, 16, 16, "stone")));
        Map<String, Object> stones = new HashMap<>();
        stones.put("base", ref("stone_arch"));
        stones.put("column", ref("stone_arch_column"));
        for (int i = 1; i <= 4; i++) {
            model("stone_arch_" + i, stone, List.of(cube(0, -12 + (i - 1) * 5, 0, 16, 11, 16, "stone")));
            stones.put("arch_" + i, ref("stone_arch_" + i));
        }
        write("assets/trainmod/blockstates/stone_arch.json", Map.of("variants", Map.of("", bridgeVariant(stones))));
    }

    private void wideGirderModels(Map<String, String> steel) throws IOException {
        var textures = new HashMap<>(steel);
        textures.put("tie", "trainmod:block/creosote");
        model("wide_steel_girder_empty", steel, List.of());
        var states = new HashMap<String, Object>();
        var inventory = new ArrayList<Map<String, Object>>();
        for (int part = 0; part < 3; part++) {
            String name = "wide_steel_girder_" + part;
            String foot = "wide_steel_girder_empty";
            String column = "wide_steel_girder_empty";
            if (part != WideGirder.CENTER) {
                foot = name + "_foot";
                column = name + "_column";
                model(foot, steel, wideGirderSupport(part, WideGirder.GIRDER_DEPTH));
                model(column, steel, wideGirderSupport(part, 16));
            }
            model(name, textures, wideGirderSection(part, 0));
            inventory.addAll(wideGirderSection(part, 16 * (part - WideGirder.CENTER)));
            states.put("axis=z,part=" + part, bridgeVariant(Map.of("base", ref(name), "foot", ref(foot), "column", ref(column))));
            states.put("axis=x,part=" + part, bridgeVariant(Map.of("base", directionRef(name, 90),
                "foot", directionRef(foot, 90), "column", directionRef(column, 90))));
        }
        model("wide_steel_girder", textures, inventory);
        write("assets/trainmod/blockstates/wide_steel_girder.json", Map.of("variants", states));
    }

    private static List<Map<String, Object>> wideGirderSupport(int part, int height) {
        int edge = part == 0 ? WideGirder.WEST_EDGE : WideGirder.EAST_EDGE;
        return List.of(cube(edge, 0, 5, edge + 4, height, 6, "flange"),
            cube(edge, 0, 10, edge + 4, height, 11, "flange"),
            cube(edge + 1.5, 0, 6, edge + 2.5, height, 10, "metal"));
    }

    private static List<Map<String, Object>> wideGirderSection(int part, int shift) {
        var parts = new ArrayList<Map<String, Object>>();
        double left = shift + (part == 0 ? 2 : 0);
        double right = shift + (part == 2 ? 14 : 16);
        double tieHalfWidth = 2;
        double tieBottom = 16 - 2 * tieHalfWidth;
        double spanOffset = 16 * (part - WideGirder.CENTER) - shift;
        if (part != WideGirder.CENTER) {
            double edge = shift + (part == 0 ? WideGirder.WEST_EDGE : WideGirder.EAST_EDGE);
            parts.add(cube(edge, WideGirder.GIRDER_DEPTH, 0, edge + 4, 9.5, 16, "flange"));
            parts.add(cube(edge + 1, 9.5, 0, edge + 3, 14.5, 16, "metal"));
            parts.add(cube(edge, 14.5, 0, edge + 4, 16, 16, "flange"));
        }
        for (int tie = 0; tie < 3; tie++) {
            double z = (tie + 0.5) * 16 / 3;
            parts.add(cube(left, tieBottom - 1.5, z - 0.65, right, tieBottom, z + 0.65, "metal"));
            addDeckTie(parts, left + (part == 0 ? 2 : 0), right - (part == 2 ? 2 : 0),
                spanOffset, tieBottom, z - tieHalfWidth, z + tieHalfWidth);
        }
        // Split each diagonal brace at the block boundaries so it follows the complete deck.
        double middle = (left + right) / 2;
        double braceHalfWidth = 0.55;
        double slopeMagnitude = (16 - 2 * braceHalfWidth) / 44;
        double halfLength = (right - left) * Math.hypot(1, slopeMagnitude) / 2;
        double angle = Math.toDegrees(Math.atan(slopeMagnitude));
        for (int slope : new int[]{-1, 1}) {
            double z = 8 + slope * (middle + spanOffset - 8) * slopeMagnitude;
            double height = tieBottom - 0.9 + slope * 0.18;
            double braceStart = middle - halfLength + (part == 0 ? 0 : 0.015);
            double braceEnd = middle + halfLength - (part == 2 ? 0 : 0.015);
            parts.add(rotated(braceStart, height - 0.6, z - braceHalfWidth,
                braceEnd, height + 0.6, z + braceHalfWidth, "metal", middle, height, z, -slope * angle, 0));
        }
        return parts;
    }

    private static void addDeckTie(List<Map<String, Object>> parts, double left, double right, double spanOffset,
                                   double bottom, double z1, double z2) {
        double size = round(z2 - z1);
        for (double x = left; x < right;) {
            // Rail ties tile once per block along the grain, with a shallow strip across the end.
            double along = x + spanOffset + 12;
            double start = along % 16;
            double end = Math.min(right, x + 16 - start);
            double uvEnd = start + end - x;
            var tie = cube(x, bottom, z1, end, 16, z2, "tie");
            var faces = new HashMap<String, Object>();
            var top = Map.of("texture", "#tie", "uv", List.of(start, 0, uvEnd, size));
            faces.put("up", top);
            faces.put("down", top);
            var sideUv = List.of(0, start, size, uvEnd);
            faces.put("north", Map.of("texture", "#tie", "uv", sideUv, "rotation", 90));
            faces.put("south", Map.of("texture", "#tie", "uv", sideUv, "rotation", 270));
            var endGrain = Map.of("texture", "#tie", "uv", List.of(0, 0, size, size));
            if (along == 0) faces.put("west", endGrain);
            if (end + spanOffset == 28) faces.put("east", endGrain);
            tie.put("faces", faces);
            parts.add(tie);
            x = end;
        }
    }

    private static Map<String, Object> directionRef(String model, int rotation) {
        return Map.of("model", "trainmod:block/" + model, "y", rotation);
    }

    private static Map<String, Object> bridgeVariant(Map<String, ?> models) {
        return Map.of("type", "trainmod:bridge", "models", models);
    }

    private void catenaryTowers() throws IOException {
        var steel = Map.of("metal", "trainmod:block/steel_web", "plate", "trainmod:block/steel_head",
            "concrete", "minecraft:block/light_gray_concrete", "particle", "minecraft:block/light_gray_concrete");
        for (String id : List.of("catenary_single", "catenary_double", "catenary_gantry")) {
            boolean gantry = id.endsWith("gantry");
            model(id + "_base", steel, gantry ? List.of(cube(6.25, 7, 6.25, 9.75, 9, 9.75, "metal"))
                : List.of());
            write("assets/trainmod/blockstates/" + id + ".json", Map.of("variants", Map.of("", ref(id + "_base"))));
            var kit = new ArrayList<Map<String, Object>>();
            if (gantry) {
                kit.add(cube(1, 5, 6, 15, 7.5, 10, "metal"));
                kit.add(cube(6.5, 2.5, 6.5, 9.5, 5, 9.5, "plate"));
            } else {
                kit.add(cube(2, 0, 5, 7, 2.6, 11, "concrete"));
                addTowerPost(kit, 4.5, 2.6, 22);
                kit.add(cube(3.5, 20, 7, 14, 22, 9, "metal"));
                kit.add(cube(11, 17, 6.5, 13, 20, 9.5, "plate"));
                if (id.endsWith("double")) {
                    kit.add(cube(11, 0, 5, 16, 2.6, 11, "concrete"));
                    addTowerPost(kit, 13.5, 2.6, 20);
                }
            }
            model(id, steel, kit);
        }
    }

    private static void addTowerPost(List<Map<String, Object>> parts, double x, double bottom, double top) {
        parts.add(cube(x - 1, bottom, 7, x + 1, top, 7.4, "metal"));
        parts.add(cube(x - 1, bottom, 8.6, x + 1, top, 9, "metal"));
        parts.add(cube(x - 0.24, bottom, 7.4, x + 0.24, top, 8.6, "metal"));
    }

    private void bufferStops() throws IOException {
        Map<String, String> materials = Map.of("metal", "trainmod:block/cast_iron",
            "timber", "trainmod:block/creosote", "red", "trainmod:block/red_enamel",
            "white", "trainmod:block/white_enamel", "particle", "trainmod:block/cast_iron");
        Map<String, Object> states = new HashMap<>();
        for (boolean diagonal : new boolean[]{false, true}) {
            String name = diagonal ? "buffer_stop_diagonal" : "buffer_stop";
            double front = diagonal ? BufferStop.DIAGONAL_FACE : BufferStop.CARDINAL_FACE;
            double yaw = diagonal ? 45 : 0;
            double bottom = BufferStop.BLOCK_BEAM_BOTTOM;
            double top = BufferStop.BLOCK_BEAM_TOP;
            List<Map<String, Object>> boxes = new ArrayList<>();
            for (int side : new int[]{3, 10}) {
                boxes.add(bufferPart(side - 0.5, 0, 2, side + 3.5, 2, front - 1.5, "timber", yaw));
                for (int step = 0; step < 3; step++) {
                    double z = front - 11.75 + step * 2;
                    boxes.add(bufferPart(side, 2.05, z, side + 3, 4 + step * 1.25, z + 2,
                        "metal", yaw));
                }
                boxes.add(bufferPart(side, 2.05, front - 5.75, side + 3, bottom + 1, front - 3,
                    "metal", yaw));
            }
            boxes.add(bufferPart(1.85, bottom, front - 3, 14.15, top, front - 0.65, "red", yaw));
            boxes.add(bufferPart(6, bottom + 0.5, front - 0.64, 10, top - 0.5, front - 0.43, "white", yaw));
            for (int pad : new int[]{3, 10}) {
                boxes.add(bufferPart(pad, bottom + 0.25, front - 0.64, pad + 3, top - 0.25, front, "metal", yaw));
            }
            model(name, materials, boxes);
        }
        for (int rotation = 0; rotation < 16; rotation++) {
            int snapped = ((rotation + 1) & 15) / 2 * 2;
            boolean diagonal = snapped % 4 == 2;
            int quarter = diagonal ? ((snapped + 2) & 15) / 4 : snapped / 4;
            states.put("rotation=" + rotation, Map.of("model",
                "trainmod:block/buffer_stop" + (diagonal ? "_diagonal" : ""), "y", quarter * 90));
        }
        write("assets/trainmod/blockstates/buffer_stop.json", Map.of("variants", states));
        item("buffer_stop", "block/buffer_stop");
        loot("buffer_stop", "trainmod:buffer_stop");
    }

    private static Map<String, Object> bufferPart(double x1, double y1, double z1, double x2, double y2,
                                                    double z2, String material, double yaw) {
        return yaw == 0 ? cube(x1, y1, z1, x2, y2, z2, material)
            : rotated(x1, y1, z1, x2, y2, z2, material, 8, 8, 8, yaw, 0);
    }

    private void switchStands() throws IOException {
        var textures = Map.of("metal", "trainmod:block/cast_iron", "white", "trainmod:block/white_enamel",
            "red", "trainmod:block/red_enamel", "particle", "trainmod:block/cast_iron");
        var base = SwitchStandModel.base();
        var preview = new ArrayList<>(base);
        preview.addAll(SwitchStandModel.head());
        model("switch_stand_base", textures, base.stream().map(TrainAssets::standBox).toList());
        model("switch_stand_straight", textures, preview.stream().map(TrainAssets::standBox).toList());
        Map<String, Object> states = new HashMap<>();
        List<String> facings = List.of("south", "west", "north", "east");
        for (int i = 0; i < facings.size(); i++) {
            states.put("facing=" + facings.get(i), Map.of("model", "trainmod:block/switch_stand_base", "y", i * 90));
        }
        write("assets/trainmod/blockstates/switch_stand.json", Map.of("variants", states));
    }

    private static Map<String, Object> standBox(VehicleModels.Box part) {
        String paint = switch (part.color()) {
            case SwitchStandModel.WHITE -> "white";
            case SwitchStandModel.RED -> "red";
            default -> "metal";
        };
        return cube(part.x() + 8, part.y(), part.z() + 8, part.x() + part.width() + 8,
            part.y() + part.height(), part.z() + part.length() + 8, paint);
    }

    private void vehicles() throws IOException {
        Set<String> panelTextures = new HashSet<>();
        for (TrainVehicle.Kind kind : TrainVehicle.Kind.values()) {
            List<Map<String, Object>> boxes = new ArrayList<>();
            Map<String, String> textures = new HashMap<>();
            var model = new ArrayList<>(VehicleModels.forKind(kind));
            if (kind == TrainVehicle.Kind.STEAM_LOCOMOTIVE) model.addAll(VehicleModels.posedSteamGear(kind));
            if (kind == TrainVehicle.Kind.LOCOMOTIVE) {
                model.addAll(VehicleModels.headlight(kind, -1, false));
                model.addAll(VehicleModels.headlight(kind, 1, false));
            }
            if (kind == TrainVehicle.Kind.BOXCAR) {
                model.addAll(VehicleModels.doors(kind, true));
                model.addAll(VehicleModels.doors(kind, false));
            }
            for (var face : VehicleModels.visibleFaces(model)) {
                var box = face.bounds();
                int color = box.color();
                String key = Integer.toHexString(color);
                var panel = face.panel();
                Map<String, Object> faceModel = new HashMap<>();
                if (panel == null) {
                    if (paint.add(color)) texture("block/paint/" + key, color, 16);
                } else {
                    var pattern = VehiclePaint.Pattern.of(face.source());
                    var region = VehiclePaint.REGIONS.get(pattern);
                    key += "_" + panel.name().toLowerCase(Locale.ROOT) + "_" + region.x() + "_" + region.y();
                    if (panelTextures.add(key)) vehiclePanelTexture(key, pattern, color);
                    float u0 = pattern.itemU(face.panelU(box.x(), box.z()));
                    float u1 = pattern.itemU(face.panelU(box.x() + box.width(), box.z() + box.length()));
                    float v0 = pattern.itemV(face.panelV(box.y(), box.z()));
                    float v1 = pattern.itemV(face.panelV(box.y() + box.height(), box.z() + box.length()));
                    faceModel.put("uv", List.of(Math.min(u0, u1), Math.min(v0, v1), Math.max(u0, u1), Math.max(v0, v1)));
                }
                textures.put(key, "trainmod:block/paint/" + key);
                faceModel.put("texture", "#" + key);
                float scale = 0.43f;
                double x = box.x() * scale + 8;
                double y = box.y() * scale;
                double z = box.z() * scale + 8;
                boxes.add(Map.of("from", List.of(x, y, z),
                    "to", List.of(x + box.width() * scale, y + box.height() * scale, z + box.length() * scale),
                    "faces", Map.of(face.direction().getName(), faceModel)));
            }
            textures.put("particle", textures.values().iterator().next());
            write("assets/trainmod/models/item/" + kind.id() + ".json",
                Map.of("parent", "minecraft:block/block", "textures", textures, "elements", boxes));
            item(kind.id(), "item/" + kind.id());
        }
        texture("entity/paint", 0xf8f8f8, 128);
        vehiclePaintAtlas();
    }

    private void vehiclePanelTexture(String key, VehiclePaint.Pattern pattern, int color) throws IOException {
        int width = pattern.itemWidth(), height = pattern.itemHeight(), padding = VehiclePaint.PADDING;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int shade = panelShade(pattern, x - padding, y - padding);
                image.setRGB(x, y, 0xff000000 | (color >> 16) * shade / 255 << 16
                    | ((color >> 8) & 255) * shade / 255 << 8 | (color & 255) * shade / 255);
            }
        }
        saveImage("block/paint/" + key, image);
    }

    private void vehiclePaintAtlas() throws IOException {
        int width = VehiclePaint.ATLAS_WIDTH, height = VehiclePaint.ATLAS_HEIGHT, padding = VehiclePaint.PADDING;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < 128; x++) image.setRGB(x, y, grey(248 + paintNoise(x, y)));
        }
        for (var entry : VehiclePaint.REGIONS.entrySet()) {
            var pattern = entry.getKey();
            var region = entry.getValue();
            // Repeat edge pixels in the gaps between panels for texture filtering.
            for (int y = -padding; y < pattern.pixelHeight() + padding; y++) {
                for (int x = -padding; x < pattern.pixelWidth() + padding; x++) {
                    image.setRGB(region.x() + x, region.y() + y, grey(panelShade(pattern, x, y)));
                }
            }
        }
        saveImage("entity/vehicle_paint", image);
    }

    private static int panelShade(VehiclePaint.Pattern pattern, int x, int y) {
        x = Math.clamp(x, 0, pattern.pixelWidth() - 1);
        y = Math.clamp(y, 0, pattern.pixelHeight() - 1);
        double u = Math.min(1, (x + 0.5) / (pattern.width() * VehiclePaint.TEXELS_PER_UNIT));
        double v = Math.min(1, (y + 0.5) / (pattern.height() * VehiclePaint.TEXELS_PER_UNIT));
        int noise = paintNoise(x, y);
        int shade = switch (pattern.panel()) {
            case FLAT_SILL -> 241 + rib(u * 36, 1.0, 5, 15, 24, 33) - (v < 0.10 || v > 0.84 ? 16 : 0);
            case GONDOLA_WALL -> v < 0.70 ? 239 + rib(u * 36, 0.48, 3.45, 8.45, 13.45, 18.45, 23.45, 28.45, 33.45)
                : 221 + rib(u * 36, 1.0, 5, 15, 24, 33) - (v > 0.92 ? 16 : 0);
            case BOXCAR_WALL -> 232 + rib(u * 11, 0.38, 1.55, 6.25) - (v < 0.08 || v > 0.92 ? 16 : 0);
            case BOXCAR_DOOR -> 241 + rib(u * 11.9, 0.24, 1, 3, 5, 7, 9, 11) - (v < 0.08 || v > 0.91 ? 22 : 0);
            case DIESEL_VENTS -> v > 0.19 && v < 0.72 ? 238 + rib(u * 13, 0.34, 1.28, 3.28, 5.28, 7.28, 9.28) * 2 : 248;
            case DECK -> {
                double plank = v * 12;
                yield (plank % 1 < 0.13 ? 148 : (int) plank % 2 == 0 ? 248 : 222)
                    + 2 * Math.floorMod(x / 8 ^ y * 11, 7) - 6;
            }
            case TENDER_COAL -> 112 + Math.floorMod(x / 4 * 17 ^ y / 2 * 41, 112) + 4 * noise;
            case TENDER_SIDE -> v > 0.55 && v < 0.97 ? 242 : 194;
        };
        return Math.clamp(shade + noise, 0, 255);
    }

    private static int rib(double along, double halfWidth, double... centers) {
        for (double center : centers) {
            double offset = (along - center) / halfWidth;
            if (Math.abs(offset) <= 1) return offset < -0.46 ? 16 : offset > 0.30 ? -74 : -22;
        }
        return 0;
    }

    private static int grey(int shade) {
        return 0xff000000 | shade << 16 | shade << 8 | shade;
    }

    private static int paintNoise(int x, int y) {
        return Math.floorMod(x * 23 ^ y * 59 ^ (x / 3 + y / 3) * 17, 11) - 5;
    }

    private void tools() throws IOException {
        tool("track_builder", List.of("................", "..........dd....", ".........dssdd..", "........dshsd...",
            ".......dshsd....", "......dshsd.....", ".....dshsd......", "....dshsd.......",
            "...dshsd........", "..dshsd.........", ".dshsd..........", ".dsdd...........",
            "..dd............", "................", "................", "................"));
        tool("coupler", List.of("................", "................", "..........ddd...",
            ".........dsshd..", ".........sh..d..", ".........sh..d..", "........dsshd...",
            "......dsshd.....", ".....dshd.......", "...dsshd........", "..dshd..........", "..dsd...........",
            "...d............", "................", "................", "................"));
        tool("rail", List.of("................", "............hh..", "..........hhhs..", ".........hhssd..",
            "........hsssd...", ".......hsssd....", "......hsssd.....", ".....hsssd......",
            "....hsssd.......", "...hsssd........", "..hsssd.........", ".hsssd..........",
            ".sssd...........", ".ddsd...........", "..dd............", "................"));
        BufferedImage ties = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        var graphics = ties.createGraphics();
        for (int i = 0; i < 4; i++) {
            int x = 2 + i, y = 10 - 3 * i;
            graphics.setColor(new java.awt.Color(0x473226));
            graphics.fillRect(x, y, 10, 4);
            graphics.setColor(new java.awt.Color(0x906345));
            graphics.fillRect(x + 1, y, 9, 2);
        }
        graphics.setColor(new java.awt.Color(0xc2b599));
        graphics.drawLine(5, 12, 8, 1);
        graphics.drawLine(9, 12, 12, 1);
        graphics.dispose();
        generatedItem("tie_bundle", ties);
        tool("track_scrap", List.of("................", "................", "..ss............", "..dhd..hh.......",
            "...ds.shsd......", "..ddshsssd......", "..hdshsdd..h....", "...dssdd..hsd...",
            "...sddd..hsd....", "....dd..hsdd....", "......dhsdd.....", "....shsdd.......",
            "...dssdd........", "....ddd.........", "................", "................"));
    }

    private void tool(String id, List<String> pixels) throws IOException {
        generatedItem(id, toolPixels(pixels));
    }

    private static BufferedImage toolPixels(List<String> pixels) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Map<Character, Integer> colors = Map.of('d', 0xff393743, 's', 0xff8a8e96, 'h', 0xffddd7bd);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, colors.getOrDefault(pixels.get(y).charAt(x), 0));
            }
        }
        return image;
    }

    private void generatedItem(String id, BufferedImage image) throws IOException {
        saveImage("item/" + id, image);
        write("assets/trainmod/models/item/" + id + ".json",
            Map.of("parent", "minecraft:item/generated", "textures", Map.of("layer0", "trainmod:item/" + id)));
        item(id, "item/" + id);
    }

    private void recipes() throws IOException {
        shapeless("ballast", 2, "minecraft:gravel");
        stonecutting("ballast", 2, "minecraft:gravel");
        write("data/trainmod/tags/item/diesel_buckets.json", Map.of("values",
            List.of(Map.of("id", "#" + VehicleFuel.EXTERNAL_DIESEL.location(), "required", false), "trainmod:diesel_bucket")));
        write("data/trainmod/recipe/diesel_bucket.json", Map.of("type", "trainmod:diesel",
            "neoforge:conditions", List.of(Map.of("type", "neoforge:tag_empty",
                "tag", VehicleFuel.EXTERNAL_DIESEL.location().toString()))));
        write("assets/trainmod/models/item/diesel_bucket.json", Map.of("parent", "minecraft:item/generated",
            "textures", Map.of("layer0", "minecraft:item/bucket", "layer1", "trainmod:item/diesel")));
        saveImage("item/diesel", toolPixels(List.of("................", "................", "................", "................",
            "................", "...dddddddddd...", "....dsssssdd....", ".....ddddd......",
            "................", "................", "................", "................",
            "................", "................", "................", "................")));
        item("diesel_bucket", "item/diesel_bucket");
        recipe("rail_extruder", 1, List.of("PFP", "CRC", "III"), Map.of("P", "piston", "F", "furnace",
            "C", "copper_ingot", "R", "redstone", "I", "iron_ingot"));
        recipe("tie_bundle", 2, List.of("P ", " P"), Map.of("P", "#minecraft:planks"));
        stonecutting("tie_bundle", 1, "#minecraft:planks");
        trackRecipes();
        write("data/trainmod/recipe/recycle_track.json", Map.of("type", "trainmod:recycle_track"));
        unlockRecipe("recycle_track", "trainmod:track_scrap");
        recipe("buffer_stop", 1, List.of("IRI", "P P", "PPP"),
            Map.of("I", "iron_ingot", "R", "red_dye", "P", "spruce_planks"));
        recipe("coupler", 1, List.of(" II", " CI", "S  "), Map.of("I", "iron_ingot", "C", "iron_chain", "S", "stick"));
        recipe("switch_stand", 1, List.of(" T ", " L ", " I "), Map.of("T", "redstone_torch", "L", "lever", "I", "iron_ingot"));
        recipe("signal_cabin", 1, List.of("III", "IDI", "III"), Map.of("I", "iron_ingot", "D", "iron_door"));
        recipe("steel_girder", 4, List.of("III", " I ", "III"), Map.of("I", "iron_ingot"));
        recipe("wide_steel_girder", 1, List.of(" T ", "G G"), Map.of("T", "trainmod:tie_bundle", "G", "trainmod:steel_girder"));
        recipe("catenary_single", 2, List.of("IIC", "I  ", "I  "), Map.of("I", "iron_ingot", "C", "copper_ingot"));
        recipe("catenary_double", 2, List.of("CIC", "I I", "I I"), Map.of("I", "iron_ingot", "C", "copper_ingot"));
        recipe("catenary_gantry", 4, List.of("III", " C "), Map.of("I", "iron_ingot", "C", "copper_ingot"));
        recipe("steam_locomotive", 1, List.of(" BF", "IBI", "MMM"),
            Map.of("B", "iron_block", "F", "furnace", "I", "iron_ingot", "M", "minecart"));
        recipe("tender", 1, List.of("CWC", "IFI"), Map.of("C", "chest", "W", "cauldron", "I", "iron_ingot", "F", "trainmod:flatcar"));
        recipe("electric_shunter", 1, List.of(" C ", "IRI", "MMM"),
            Map.of("C", "copper_ingot", "I", "iron_ingot", "R", "redstone_block", "M", "minecart"));
        recipe("locomotive", 1, List.of(" F ", "IBI", "MMM"), Map.of("F", "furnace", "I", "iron_ingot", "B", "iron_block", "M", "minecart"));
        recipe("flatcar", 1, List.of("PPP", "IMI"), Map.of("P", "spruce_planks", "I", "iron_ingot", "M", "minecart"));
        recipe("gondola", 1, List.of("P P", "PFP"), Map.of("P", "spruce_planks", "F", "trainmod:flatcar"));
        recipe("boxcar", 1, List.of("PPP", "PCP", " F "), Map.of("P", "spruce_planks", "C", "chest", "F", "trainmod:flatcar"));
        recipe("tunnel_bore", 1, List.of("IBI", "PHP", "MLM"), Map.of("I", "iron_block", "B", "diamond_pickaxe",
            "P", "piston", "H", "hopper", "M", "minecart", "L", "trainmod:locomotive"));
        recipe("ballast_car", 1, List.of(" H ", "PFP"), Map.of("H", "hopper", "P", "piston", "F", "trainmod:flatcar"));
        recipe("treecutters_passenger_car", 1, List.of("PPP", "F F", " C "),
            Map.of("P", "spruce_planks", "F", "spruce_fence", "C", "trainmod:flatcar"));
    }

    private void trackRecipes() throws IOException {
        for (var kind : TrackGeometry.Kind.values()) {
            if (!kind.hasKit()) continue;
            String id = kind.id() + "_rail";
            var pattern = TrackMaterials.pattern(kind);
            if (pattern.isEmpty()) shapeless(id, 1, TrackMaterials.ingredients(kind).toArray(String[]::new));
            else recipe(id, kind == TrackGeometry.Kind.STRAIGHT ? TrackMaterials.STRAIGHT_YIELD : 1, pattern,
                TrackMaterials.symbols(kind));
        }
    }

    private void shapeless(String id, int count, String... ingredients) throws IOException {
        write("data/trainmod/recipe/" + id + ".json", Map.of("type", "minecraft:crafting_shapeless",
            "ingredients", List.of(ingredients), "result", Map.of("id", "trainmod:" + id, "count", count)));
        unlockRecipe(id, ingredients[0]);
    }

    private void stonecutting(String id, int count, String ingredient) throws IOException {
        write("data/trainmod/recipe/" + id + "_stonecutter.json", Map.of("type", "minecraft:stonecutting",
            "ingredient", ingredient, "result", Map.of("id", "trainmod:" + id, "count", count)));
    }

    private void recipe(String id, int count, List<String> pattern, Map<String, String> ingredients) throws IOException {
        Map<String, String> key = new HashMap<>();
        ingredients.forEach((symbol, item) -> key.put(symbol, item.contains(":") ? item : "minecraft:" + item));
        write("data/trainmod/recipe/" + id + ".json", Map.of("type", "minecraft:crafting_shaped",
            "pattern", pattern, "key", key, "result", Map.of("id", "trainmod:" + id, "count", count)));
        String firstSymbol = pattern.getFirst().trim().substring(0, 1);
        unlockRecipe(id, key.get(firstSymbol));
    }

    private void unlockRecipe(String id, String item) throws IOException {
        var ingredient = Map.of("trigger", "minecraft:inventory_changed",
            "conditions", Map.of("items", List.of(Map.of("items", item))));
        var known = Map.of("trigger", "minecraft:recipe_unlocked", "conditions", Map.of("recipes", "trainmod:" + id));
        write("data/trainmod/advancement/recipes/" + id + ".json", Map.of("parent", "minecraft:recipes/root",
            "criteria", Map.of("has_ingredient", ingredient, "has_recipe", known),
            "requirements", List.of(List.of("has_ingredient", "has_recipe")),
            "rewards", Map.of("recipes", List.of("trainmod:" + id))));
    }

    private void loot(String id, String drop) throws IOException {
        write("data/trainmod/loot_table/blocks/" + id + ".json", Map.of("type", "minecraft:block", "pools",
            List.of(Map.of("rolls", 1, "conditions", List.of(Map.of("condition", "minecraft:survives_explosion")),
                "entries", List.of(Map.of("type", "minecraft:item", "name", drop))))));
    }

    private void model(String id, Map<String, String> textures, List<Map<String, Object>> boxes) throws IOException {
        var elements = new ArrayList<Map<String, Object>>();
        for (var box : boxes) addTiledCuboid(elements, box);
        write("assets/trainmod/models/block/" + id + ".json",
            Map.of("parent", "minecraft:block/block", "ambientocclusion", false, "textures", textures, "elements", elements));
    }

    private void item(String id, String model) throws IOException {
        write("assets/trainmod/items/" + id + ".json",
            Map.of("model", Map.of("type", "minecraft:model", "model", "trainmod:" + model)));
    }

    private static Map<String, Object> ref(String name) {
        return Map.of("model", "trainmod:block/" + name);
    }

    private static Map<String, Object> cube(double x1, double y1, double z1, double x2, double y2, double z2,
                                             String texture) {
        Map<String, Object> faces = new HashMap<>();
        FACES.forEach(side -> faces.put(side, Map.of("texture", "#" + texture)));
        Map<String, Object> result = new HashMap<>();
        result.put("from", vector(x1, y1, z1));
        result.put("to", vector(x2, y2, z2));
        result.put("faces", faces);
        return result;
    }

    private static void addTiledCuboid(List<Map<String, Object>> elements, Map<String, Object> box) {
        var faces = new HashMap<String, Object>();
        var from = coordinates(box.get("from"));
        var to = coordinates(box.get("to"));
        var sides = (Map<?, ?>) box.get("faces");
        for (Direction side : Direction.values()) {
            var face = (Map<?, ?>) sides.get(side.getName());
            if (face == null) continue;
            if (face.containsKey("uv")) {
                faces.put(side.getName(), face);
                continue;
            }
            int axis = side.getAxis().ordinal();
            int u = axis == 0 ? 2 : 0, v = axis == 1 ? 2 : 1;
            double width = to[u] - from[u], height = to[v] - from[v];
            if (width <= 16 && height <= 16) {
                faces.put(side.getName(), cubeUv(face.get("texture"), width, height));
                continue;
            }
            // Keep only the exterior face on each tile so internal cuts never render.
            for (double left = from[u]; left < to[u]; left += 16) {
                for (double bottom = from[v]; bottom < to[v]; bottom += 16) {
                    var min = from.clone();
                    var max = to.clone();
                    min[axis] = max[axis] = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? to[axis] : from[axis];
                    min[u] = left;
                    min[v] = bottom;
                    max[u] = Math.min(left + 16, to[u]);
                    max[v] = Math.min(bottom + 16, to[v]);
                    var tile = new HashMap<>(box);
                    tile.put("from", vector(min[0], min[1], min[2]));
                    tile.put("to", vector(max[0], max[1], max[2]));
                    tile.put("faces", Map.of(side.getName(), cubeUv(face.get("texture"), max[u] - min[u], max[v] - min[v])));
                    elements.add(tile);
                }
            }
        }
        if (!faces.isEmpty()) {
            var result = new HashMap<>(box);
            result.put("faces", faces);
            elements.add(result);
        }
    }

    private static double[] coordinates(Object vertex) {
        var coordinates = (List<?>) vertex;
        return new double[]{((Number) coordinates.get(0)).doubleValue(), ((Number) coordinates.get(1)).doubleValue(),
            ((Number) coordinates.get(2)).doubleValue()};
    }

    private static Map<String, Object> cubeUv(Object texture, double width, double height) {
        return Map.of("texture", texture, "uv", List.of(0, 0, round(width), round(height)));
    }

    private static Map<String, Object> rotated(double x1, double y1, double z1, double x2, double y2, double z2,
                                                String texture, double x, double y, double z, double yaw, double pitch) {
        Map<String, Object> box = cube(x1, y1, z1, x2, y2, z2, texture);
        box.put("rotation", Map.of("origin", vector(x, y, z), "x", round(pitch), "y", round(yaw)));
        return box;
    }

    private static List<Double> vector(double x, double y, double z) { return List.of(round(x), round(y), round(z)); }

    private static double round(double number) { return Math.round(number * 1000) / 1000.0; }

    private void write(String path, Object value) throws IOException {
        writeText(path, JSON.toJson(value) + "\n");
    }

    private void writeText(String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private void texture(String path, int color, int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int difference = path.equals("block/creosote")
                    ? (y % 4) * 4 - 6 + Math.floorMod(x / 3 * 23 ^ (y % 4) * 59, 7) - 3
                    : path.equals("block/signal_cabin_panel") ? (x % 4 == 0 ? 16 : x % 4 == 3 ? -17 : 0)
                        + Math.floorMod(x * 13 ^ y * 11, 5) - 2
                    : paintNoise(x, y);
                int r = Math.clamp((color >> 16) + difference, 0, 255);
                int g = Math.clamp(((color >> 8) & 255) + difference, 0, 255);
                int b = Math.clamp((color & 255) + difference, 0, 255);
                image.setRGB(x, y, 0xff000000 | r << 16 | g << 8 | b);
            }
        }
        saveImage(path, image);
    }

    private void saveImage(String path, BufferedImage image) throws IOException {
        if ((path.startsWith("block/") || path.startsWith("item/")) && ((image.getWidth() | image.getHeight()) & 15) != 0) {
            throw new IOException("Texture " + path + " needs dimensions divisible by 16 for mip level 4");
        }
        Path file = root.resolve("assets/trainmod/textures/" + path + ".png");
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }
}
