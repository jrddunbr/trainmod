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
            for (int rotation : kind.rotations()) {
                List<Integer> acceptedRotations = new ArrayList<>();
                for (int state = 0; state < 16; state++) {
                    if (kind.shape(state, true) == kind.shape(rotation, true)) {
                        acceptedRotations.add(state);
                    }
                }
                boolean handed = kind.mirrored();
                for (boolean left : handed ? new boolean[]{true, false} : new boolean[]{true}) {
                    TrackGeometry.Shape shape = kind.shape(rotation, left);
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
                    String prefix = "track/" + id + "/" + rotation + (left ? "_l" : "_r");
                    String filter = String.join("|", acceptedRotations.stream().map(Object::toString).toList());
                    String hand = handed ? (left ? "left" : "right") : "left|right";
                    for (int i = 0; i < shape.cells().size(); i++) {
                        BlockPos cell = shape.cells().get(i);
                        String name = prefix + "_" + i;
                        var condition = Map.of("rotation", filter, "type", hand, "segment", Integer.toString(i));
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
            write("assets/trainmod/blockstates/" + id + ".json", Map.of("multipart", parts));
            if (kind != TrackGeometry.Kind.DIAGONAL) trackItem(kind, id);
            trackLoot(id, kind == TrackGeometry.Kind.DIAGONAL ? "straight_rail" : id);
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
        String path = "models/block/" + name;
        Map<String, String> textures = TRACK_TEXTURES;
        if (!hasBallast) {
            textures = new HashMap<>(TRACK_TEXTURES);
            textures.put("particle", TRACK_TEXTURES.get("tie"));
        }
        writeText("assets/trainmod/" + path + ".obj", mesh.contents());
        write("assets/trainmod/" + path + ".json", Map.of("loader", "neoforge:obj",
            "model", "trainmod:" + path + ".obj", "textures", textures,
            "ambientocclusion", false, "automatic_culling", false, "flip_v", true, "emissive_ambient", false,
            "shade_quads", mesh.shadeQuads()));
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
        texture("block/signal_green", 0x66b454, 16);
        Map<String, Object> signalStates = new HashMap<>();
        for (boolean powered : new boolean[]{false, true}) {
            String signal = "signal_" + powered;
            String lamp = powered ? "trainmod:block/red_enamel" : "trainmod:block/signal_green";
            model(signal, Map.of("metal", "trainmod:block/cast_iron", "lamp", lamp,
                "particle", "trainmod:block/cast_iron"), List.of(cube(3, 0, 3, 13, 2, 13, "metal"),
                    cube(7, 2, 7, 9, 13, 9, "metal"), cube(4.5, 9, 5.5, 11.5, 16, 10.5, "metal"),
                    glowing(6, 10.5, 5.1, 10, 14.5, 5.6, "lamp"),
                    glowing(6, 10.5, 10.4, 10, 14.5, 10.9, "lamp")));
            List<String> facing = List.of("north", "east", "south", "west");
            for (int i = 0; i < 4; i++) {
                signalStates.put("facing=" + facing.get(i) + ",powered=" + powered,
                    Map.of("model", "trainmod:block/" + signal, "y", i * 90));
            }
        }
        write("assets/trainmod/blockstates/signal.json", Map.of("variants", signalStates));
        switchStands();
        bufferStops();
        item("switch_stand", "block/switch_stand_straight");
        item("signal", "block/signal_false");
        loot("switch_stand", "trainmod:switch_stand");
        loot("signal", "trainmod:signal");
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
    }

    private void tool(String id, List<String> pixels) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Map<Character, Integer> colors = Map.of('d', 0xff393743, 's', 0xff8a8e96, 'h', 0xffddd7bd);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, colors.getOrDefault(pixels.get(y).charAt(x), 0));
            }
        }
        generatedItem(id, image);
    }

    private void generatedItem(String id, BufferedImage image) throws IOException {
        saveImage("item/" + id, image);
        write("assets/trainmod/models/item/" + id + ".json",
            Map.of("parent", "minecraft:item/generated", "textures", Map.of("layer0", "trainmod:item/" + id)));
        item(id, "item/" + id);
    }

    private void recipes() throws IOException {
        recipe("ballast", 4, List.of("GGG", "CCC"), Map.of("G", "gravel", "C", "cobblestone"));
        var rails = Map.of("R", "rail", "G", "gravel", "S", "stick", "I", "iron_ingot", "T", "redstone_torch");
        recipe("straight_rail", 1, List.of("R", "G"), Map.of("R", "rail", "G", "gravel"));
        recipe("bridge_straight_rail", 1, List.of("R", "P"), Map.of("R", "rail", "P", "spruce_planks"));
        recipe("straight_3_rail", 1, List.of("RRR", "GGG"), rails, "RG");
        recipe("straight_6_rail", 1, List.of("RRR", "GGG", "RRR"), rails, "RG");
        recipe("straight_8_rail", 1, List.of("RRR", "RGR", "RRR"), rails, "RG");
        recipe("straight_12_rail", 1, List.of("RRR", "GGG", "RRR"),
            Map.of("R", "trainmod:straight_rail", "G", "gravel"));
        recipe("crossover_90_rail", 1, List.of(" R ", "RGR", " R "), rails, "RG");
        recipe("crossover_45_rail", 1, List.of(" RR", "RGR", "RR "), rails, "RG");
        recipe("turnout_parallel_long_rail", 1, List.of("RRR", "RTR", "RIR"), rails, "RTI");
        recipe("scissors_rail", 1, List.of("RTR", "RIR", "RTR"), rails, "RTI");
        recipe("wye_medium_rail", 1, List.of("R R", "RTR", "RGR"), rails, "RTG");
        recipe("curve_small_rail", 1, List.of(" R", "RR", "GG"), rails, "RG");
        recipe("curve_medium_rail", 1, List.of("  R", " RR", "RRG"), rails, "RG");
        recipe("curve_large_rail", 1, List.of("  R", "RRR", "RRG"), rails, "RG");
        recipe("grade_6_rail", 1, List.of("RRR", "SSG"), rails, "RSG");
        recipe("grade_8_rail", 1, List.of("RRR", "SSG", " G "), rails, "RSG");
        recipe("turnout_45_rail", 1, List.of("R R", "RTR", " G "), rails, "RTG");
        recipe("turnout_parallel_rail", 1, List.of("RRR", "RTR", " I "), rails, "RTI");
        recipe("wye_rail", 1, List.of("R R", "RTR", " R "), rails, "RT");
        recipe("buffer_stop", 1, List.of("IRI", "P P", "PPP"),
            Map.of("I", "iron_ingot", "R", "red_dye", "P", "spruce_planks"));
        recipe("coupler", 1, List.of(" II", " CI", "S  "), Map.of("I", "iron_ingot", "C", "iron_chain", "S", "stick"));
        recipe("switch_stand", 1, List.of(" T ", " L ", " I "), Map.of("T", "redstone_torch", "L", "lever", "I", "iron_ingot"));
        recipe("signal", 1, List.of("RTR", " I ", " S "), Map.of("R", "redstone", "T", "redstone_torch", "I", "iron_ingot", "S", "stone"));
        recipe("locomotive", 1, List.of(" F ", "IBI", "MMM"), Map.of("F", "furnace", "I", "iron_ingot", "B", "iron_block", "M", "minecart"));
        recipe("flatcar", 1, List.of("PPP", "IMI"), Map.of("P", "spruce_planks", "I", "iron_ingot", "M", "minecart"));
        recipe("gondola", 1, List.of("P P", "PFP"), Map.of("P", "spruce_planks", "F", "trainmod:flatcar"));
        recipe("boxcar", 1, List.of("PPP", "PCP", " F "), Map.of("P", "spruce_planks", "C", "chest", "F", "trainmod:flatcar"));
        recipe("tunnel_bore", 1, List.of("IBI", "PHP", "MLM"), Map.of("I", "iron_block", "B", "diamond_pickaxe",
            "P", "piston", "H", "hopper", "M", "minecart", "L", "trainmod:locomotive"));
        recipe("ballast_car", 1, List.of(" H ", "PFP"), Map.of("H", "hopper", "P", "piston", "F", "trainmod:flatcar"));
    }

    private void recipe(String id, int count, List<String> pattern, Map<String, String> pool, String required) throws IOException {
        Map<String, String> ingredients = new HashMap<>();
        for (char symbol : required.toCharArray()) {
            String key = Character.toString(symbol);
            ingredients.put(key, pool.get(key));
        }
        recipe(id, count, pattern, ingredients);
    }

    private void recipe(String id, int count, List<String> pattern, Map<String, String> ingredients) throws IOException {
        Map<String, String> key = new HashMap<>();
        ingredients.forEach((symbol, item) -> key.put(symbol, item.contains(":") ? item : "minecraft:" + item));
        write("data/trainmod/recipe/" + id + ".json", Map.of("type", "minecraft:crafting_shaped",
            "pattern", pattern, "key", key, "result", Map.of("id", "trainmod:" + id, "count", count)));
        String firstSymbol = pattern.getFirst().trim().substring(0, 1);
        var ingredient = Map.of("trigger", "minecraft:inventory_changed",
            "conditions", Map.of("items", List.of(Map.of("items", key.get(firstSymbol)))));
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

    private static Map<String, Object> glowing(double x1, double y1, double z1, double x2, double y2, double z2,
                                                String texture) {
        Map<String, Object> cube = cube(x1, y1, z1, x2, y2, z2, texture);
        cube.put("light_emission", 8);
        return cube;
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
