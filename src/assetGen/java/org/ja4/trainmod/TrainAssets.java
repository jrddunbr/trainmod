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
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
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

    private static final Map<String, String> TRACK_TEXTURES = Map.of(
        "ballast", "minecraft:block/gravel", "tie", "trainmod:block/creosote",
        "steel", "trainmod:block/steel_head", "web", "trainmod:block/steel_web",
        "frog", "trainmod:block/steel_head", "guard", "trainmod:block/steel_head",
        "particle", "minecraft:block/gravel");

    private void rails() throws IOException {
        StringBuilder material = new StringBuilder();
        for (String name : List.of("ballast", "tie", "steel", "web", "frog", "guard")) {
            material.append("newmtl ").append(name).append("\nKd 1 1 1\nmap_Kd #").append(name).append("\n");
        }
        writeText("assets/trainmod/models/block/railway.mtl", material.toString());
        texture("block/creosote", 0x604435, 16);
        texture("block/steel_head", 0xa4a6a5, 16);
        texture("block/steel_web", 0x524948, 16);
        model("track/end_material", Map.of("particle", "trainmod:block/steel_head"), List.of());
        for (TrackGeometry.Kind kind : TrackGeometry.Kind.values()) {
            String id = kind.id() + "_rail";
            JsonArray parts = new JsonArray();
            trackModels(kind, id, true, false, parts);
            if (kind.crossing() || kind.parallelTurnout()) trackModels(kind, id, false, false, parts);
            if (kind.gradedCurve()) trackModels(kind, id, true, true, parts);
            write("assets/trainmod/blockstates/" + id + ".json", Map.of("multipart", parts));
            if (kind.hasKit()) trackItem(kind, id);
            trackLoot(id, switch (kind) {
                case DIAGONAL -> "straight_rail";
                case UNBALLASTED_STRAIGHT -> "bridge_straight_rail";
                default -> id;
            });
        }
    }

    private void trackModels(TrackGeometry.Kind kind, String id, boolean compact, boolean diagonalLow, JsonArray parts) throws IOException {
        for (int rotation : kind.rotations()) {
            if (!compact && !kind.compactGeometry(rotation)) continue;
            List<Integer> acceptedRotations = new ArrayList<>();
            for (int state = 0; state < 16; state++) {
                if (kind.shape(state, true, compact, diagonalLow) == kind.shape(rotation, true, compact, diagonalLow)) {
                    acceptedRotations.add(state);
                }
            }
            boolean handed = kind.mirrored();
            for (boolean left : handed ? new boolean[]{true, false} : new boolean[]{true}) {
                TrackGeometry.Shape shape = kind.shape(rotation, left, compact, diagonalLow);
                var ties = RailMesh.ties(kind, shape);
                var tiePatterns = new ArrayList<List<Map<BlockPos, RailMesh.Mesh>>>();
                for (int pattern = 0; pattern < TieVariation.PATTERNS; pattern++) tiePatterns.add(new ArrayList<>());
                var meshes = new ArrayList<Map<BlockPos, RailMesh.Mesh>>();
                var beds = new ArrayList<Map<BlockPos, RailMesh.Mesh>>();
                var joinedBeds = new ArrayList<Map<BlockPos, RailMesh.Mesh>>();
                int variants = kind.bankedCurve() ? CurveBanking.VARIANTS : 1;
                for (int variant = 0; variant < variants; variant++) {
                    int banking = kind.bankedCurve() ? variant : -1;
                    meshes.add(RailMesh.fixed(kind, shape, banking, ties));
                    for (int pattern = 0; pattern < TieVariation.PATTERNS; pattern++) {
                        tiePatterns.get(pattern).add(RailMesh.woodTies(kind, shape, ties, banking, pattern));
                    }
                    if (kind.hasBallast()) {
                        beds.add(RailMesh.roadbed(kind, shape, false, banking));
                        joinedBeds.add(RailMesh.roadbed(kind, shape, true, banking));
                    }
                }
                String prefix = "track/" + id + "/" + rotation + (left ? "_l" : "_r") + (compact ? "" : "_legacy") + (diagonalLow ? "_diagonal_low" : "");
                String filter = String.join("|", acceptedRotations.stream().map(Object::toString).toList());
                String hand = handed ? (left ? "left" : "right") : "left|right";
                for (int i = 0; i < shape.cells().size(); i++) {
                    BlockPos cell = shape.cells().get(i);
                    String name = prefix + "_" + i;
                    var condition = new HashMap<>(Map.of("rotation", filter, "type", hand, "segment", Integer.toString(i)));
                    if (kind.compactGeometry(rotation)) condition.put("compact", Boolean.toString(compact));
                    if (kind.gradedCurve()) condition.put("diagonal_low", Boolean.toString(diagonalLow));
                    if (tiePatterns.getFirst().getFirst().containsKey(cell)) {
                        var patterns = new ArrayList<List<Map<String, Object>>>();
                        for (int pattern = 0; pattern < TieVariation.PATTERNS; pattern++) {
                            var options = tiePatterns.get(pattern);
                            String tieName = name + "_ties_" + pattern;
                            if (kind.bankedCurve()) {
                                patterns.add(variants(tieName, options, cell));
                            } else {
                                obj(tieName, options.getFirst().get(cell), kind.hasBallast());
                                patterns.add(List.of(ref(tieName)));
                            }
                        }
                        parts.add(JSON.toJsonTree(Map.of("when", condition, "apply",
                            Map.of("type", "trainmod:track_ties", "patterns", patterns))));
                    }
                    if (kind.bankedCurve()) {
                        var rails = variants(name, meshes, cell);
                        var normal = variants(name + "_bed", beds, cell);
                        var raised = variants(name + "_joined_bed", joinedBeds, cell);
                        parts.add(JSON.toJsonTree(Map.of("when", condition, "apply",
                            Map.of("type", "trainmod:banked_curve", "models", rails))));
                        parts.add(JSON.toJsonTree(Map.of("when", condition, "apply",
                            Map.of("type", "trainmod:banked_curve", "models", normal, "raised", raised, "grounded", true))));
                    } else {
                        RailMesh.Mesh mesh = meshes.getFirst().get(cell);
                        if (mesh != null) {
                            obj(name, mesh, kind.hasBallast());
                            parts.add(JSON.toJsonTree(Map.of("when", condition, "apply", ref(name))));
                        }
                        if (kind.hasBallast() && beds.getFirst().containsKey(cell)) {
                            obj(name + "_bed", beds.getFirst().get(cell));
                            obj(name + "_joined_bed", joinedBeds.getFirst().get(cell));
                            parts.add(JSON.toJsonTree(Map.of("when", condition, "apply",
                                Map.of("type", "trainmod:adjacent_ballast", "separate", ref(name + "_bed"),
                                    "joined", ref(name + "_joined_bed")))));
                        }
                    }
                    if (!TrackEnds.ports(shape, cell).isEmpty()) {
                        parts.add(JSON.toJsonTree(Map.of("when", condition, "apply",
                            Map.of("type", "trainmod:rail_ends", "base", ref("track/end_material")))));
                    }
                }

            }
        }
    }

    private List<Map<String, Object>> variants(String name, List<Map<BlockPos, RailMesh.Mesh>> options,
                                                BlockPos cell) throws IOException {
        Map<String, Map<String, Object>> unique = new HashMap<>();
        var refs = new ArrayList<Map<String, Object>>();
        for (int variant = 0; variant < options.size(); variant++) {
            RailMesh.Mesh mesh = options.get(variant).get(cell);
            if (mesh == null) {
                var empty = "track/empty";
                model(empty, Map.of("particle", "minecraft:block/gravel"), List.of());
                refs.add(ref(empty));
                continue;
            }
            String contents = mesh.contents();
            var entry = unique.get(contents);
            if (entry == null) {
                String id = name + "_bank_" + variant;
                obj(id, mesh);
                entry = ref(id);
                unique.put(contents, entry);
            }
            refs.add(entry);
        }
        return refs;
    }

    private void obj(String name, RailMesh.Mesh mesh) throws IOException {
        obj(name, mesh, true);
    }

    private void obj(String name, RailMesh.Mesh mesh, boolean hasBallast) throws IOException {
        Map<String, String> textures = TRACK_TEXTURES;
        if (!hasBallast) {
            textures = new HashMap<>(TRACK_TEXTURES);
            textures.put("particle", TRACK_TEXTURES.get("tie"));
        }
        obj(name, mesh, textures, false);
    }

    private void obj(String name, RailMesh.Mesh mesh, Map<String, String> textures, boolean inventory) throws IOException {
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
        for (int layer = 0; layer < 3; layer++) {
            if (layer == 0 && !kind.hasBallast()) continue;
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
                Map.of("condition", "minecraft:block_state_property", "block", "trainmod:" + id, "properties", Map.of("segment", "0"))),
                "entries", List.of(Map.of("type", "minecraft:item", "name", "trainmod:" + item))))));
    }

    private void railwayBlocks() throws IOException {
        model("ballast_surface", Map.of("particle", "minecraft:block/gravel"), List.of());
        write("assets/trainmod/blockstates/ballast.json", Map.of("variants", Map.of("", Map.of(
            "type", "trainmod:adjacent_ballast", "foundation", true, "separate", ref("ballast_surface")))));
        model("ballast_item", Map.of("rock", "minecraft:block/gravel", "particle", "minecraft:block/gravel"),
            List.of(cube(0, 0, 0, 16, 5, 16, "rock"), cube(2, 5, 0, 14, 10, 16, "rock"),
                cube(4, 10, 0, 12, 15, 16, "rock")));
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
        textures.put("tie", TRACK_TEXTURES.get("tie"));
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
        double tieHalfWidth = 2 * RailMesh.TIE_HALF_WIDTH * 16;
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
                textures.put(key, "trainmod:block/paint/" + key);
                if (paint.add(color)) {
                    texture("block/paint/" + key, color, 16);
                }
                float scale = 0.43f;
                double x = box.x() * scale + 8;
                double y = box.y() * scale;
                double z = box.z() * scale + 8;
                boxes.add(Map.of("from", List.of(x, y, z),
                    "to", List.of(x + box.width() * scale, y + box.height() * scale, z + box.length() * scale),
                    "faces", Map.of(face.direction().getName(), Map.of("texture", "#" + key))));
            }
            textures.put("particle", textures.values().iterator().next());
            write("assets/trainmod/models/item/" + kind.id() + ".json",
                Map.of("parent", "minecraft:block/block", "textures", textures, "elements", boxes));
            item(kind.id(), "item/" + kind.id());
        }
        texture("entity/paint", 0xf8f8f8, 128);
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
    }

    private void trackRecipes() throws IOException {
        String rail = "trainmod:rail", ties = "trainmod:tie_bundle";
        String straight = "trainmod:straight_rail", straight3 = "trainmod:straight_3_rail", straight6 = "trainmod:straight_6_rail";
        String straight8 = "trainmod:straight_8_rail", straight12 = "trainmod:straight_12_rail";
        String small = "trainmod:curve_small_rail", medium = "trainmod:curve_medium_rail", large = "trainmod:curve_large_rail";
        String stone = "minecraft:stone", torch = "minecraft:redstone_torch";

        recipe("straight_rail", 1, List.of("R R", " T ", " B "), Map.of("R", rail, "T", ties, "B", "trainmod:ballast"));
        recipe("bridge_straight_rail", 1, List.of("R R", " T "), Map.of("R", rail, "T", ties));
        shapeless("straight_3_rail", 1, straight, straight, straight);
        shapeless("straight_6_rail", 1, straight3, straight3);
        shapeless("straight_8_rail", 1, straight6, straight, straight);
        shapeless("straight_12_rail", 1, straight6, straight6);

        shapeless("curve_small_rail", 1, straight3, straight, straight, ties);
        shapeless("curve_medium_rail", 1, straight8, straight, ties);
        shapeless("curve_large_rail", 1, straight12, straight, ties);
        shapeless("curve_medium_grade_rail", 1, medium, stone);
        shapeless("curve_large_grade_rail", 1, large, stone);
        shapeless("grade_6_rail", 1, straight6, stone, stone);
        shapeless("grade_8_rail", 1, straight8, stone, stone);

        var crossing = Map.of("S", straight3, "T", ties);
        recipe("crossover_90_rail", 1, List.of(" S ", " T ", " S "), crossing);
        recipe("crossover_45_rail", 1, List.of("  S", " T ", "S  "), crossing);
        shapeless("turnout_45_rail", 1, straight3, straight, small, torch);
        shapeless("turnout_45_medium_rail", 1, straight8, medium, torch);
        shapeless("turnout_45_large_rail", 1, straight12, large, torch);
        shapeless("turnout_parallel_rail", 1, straight8, medium, torch);
        shapeless("turnout_parallel_long_rail", 1, straight12, straight3, large, torch);
        shapeless("wye_rail", 1, small, small, torch);
        shapeless("wye_medium_rail", 1, medium, medium, torch);
        shapeless("scissors_rail", 1, straight12, straight12, medium, medium, torch, torch, torch, torch);
        shapeless("scissors_long_rail", 1, "trainmod:scissors_rail", straight8, large, large);
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
        write("assets/trainmod/models/block/" + id + ".json",
            Map.of("parent", "minecraft:block/block", "ambientocclusion", false, "textures", textures, "elements", boxes));
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
        FACES.forEach(side -> faces.put(side, Map.of("texture", "#" + texture, "uv", List.of(0, 0, 16, 16))));
        Map<String, Object> result = new HashMap<>();
        result.put("from", vector(x1, y1, z1));
        result.put("to", vector(x2, y2, z2));
        result.put("faces", faces);
        return result;
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
                    : Math.floorMod(x * 23 ^ y * 59 ^ (x / 3 + y / 3) * 17, 11) - 5;
                int r = Math.clamp((color >> 16) + difference, 0, 255);
                int g = Math.clamp(((color >> 8) & 255) + difference, 0, 255);
                int b = Math.clamp((color & 255) + difference, 0, 255);
                image.setRGB(x, y, 0xff000000 | r << 16 | g << 8 | b);
            }
        }
        saveImage(path, image);
    }

    private void saveImage(String path, BufferedImage image) throws IOException {
        Path file = root.resolve("assets/trainmod/textures/" + path + ".png");
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }
}
