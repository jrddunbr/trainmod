package org.ja4.trainmod;

import java.util.List;
import java.util.Map;

/** The built-in kit recipes also determine what a damaged kit yields when recycled. */
final class TrackMaterials {
    private static final String RAIL = "trainmod:rail", TIES = "trainmod:tie_bundle";
    private static final String STRAIGHT = "trainmod:straight_rail", STRAIGHT_3 = "trainmod:straight_3_rail";
    private static final String STRAIGHT_6 = "trainmod:straight_6_rail", STRAIGHT_8 = "trainmod:straight_8_rail";
    private static final String STRAIGHT_12 = "trainmod:straight_12_rail";
    private static final String SMALL = "trainmod:curve_small_rail", MEDIUM = "trainmod:curve_medium_rail";
    private static final String LARGE = "trainmod:curve_large_rail";
    private static final String STONE = "minecraft:stone", TORCH = "minecraft:redstone_torch";
    static final int STRAIGHT_YIELD = 4;

    private TrackMaterials() {}

    static int railsFor(TrackGeometry.Kind kind) {
        return (int) ingredients(kind).stream().filter(RAIL::equals).count();
    }

    static List<String> salvage(TrackGeometry.Kind kind) {
        return kind == TrackGeometry.Kind.STRAIGHT || kind == TrackGeometry.Kind.DIAGONAL
            ? List.of("trainmod:track_scrap") : ingredients(kind);
    }

    static List<String> pattern(TrackGeometry.Kind kind) {
        return switch (kind) {
            case STRAIGHT -> List.of("R R", " T ");
            case BRIDGE_STRAIGHT -> List.of("R R", " T ", "R R");
            case CROSSOVER_90 -> List.of(" S ", " T ", " S ");
            case CROSSOVER_45 -> List.of("  S", " T ", "S  ");
            default -> List.of();
        };
    }

    static Map<String, String> symbols(TrackGeometry.Kind kind) {
        return switch (kind) {
            case STRAIGHT, BRIDGE_STRAIGHT -> Map.of("R", RAIL, "T", TIES);
            case CROSSOVER_90, CROSSOVER_45 -> Map.of("S", STRAIGHT_3, "T", TIES);
            default -> Map.of();
        };
    }

    static List<String> ingredients(TrackGeometry.Kind kind) {
        var pattern = pattern(kind);
        if (!pattern.isEmpty()) {
            var symbols = symbols(kind);
            return pattern.stream().flatMap(row -> row.chars().filter(symbol -> symbol != ' ')
                .mapToObj(symbol -> symbols.get(Character.toString(symbol)))).toList();
        }
        return switch (kind) {
            case DIAGONAL, UNBALLASTED_STRAIGHT -> ingredients(TrackGeometry.Kind.STRAIGHT);
            case STRAIGHT_3 -> List.of(STRAIGHT, STRAIGHT, STRAIGHT);
            case STRAIGHT_6 -> List.of(STRAIGHT_3, STRAIGHT_3);
            case STRAIGHT_8 -> List.of(STRAIGHT_6, STRAIGHT, STRAIGHT);
            case STRAIGHT_12 -> List.of(STRAIGHT_6, STRAIGHT_6);
            case CURVE_SMALL -> List.of(STRAIGHT_3, STRAIGHT, STRAIGHT, TIES);
            case CURVE_MEDIUM -> List.of(STRAIGHT_8, STRAIGHT, TIES);
            case CURVE_LARGE -> List.of(STRAIGHT_12, STRAIGHT, TIES);
            case CURVE_MEDIUM_GRADE -> List.of(MEDIUM, STONE);
            case CURVE_LARGE_GRADE -> List.of(LARGE, STONE);
            case GRADE_6 -> List.of(STRAIGHT_6, STONE, STONE);
            case GRADE_8 -> List.of(STRAIGHT_8, STONE, STONE);
            case TURNOUT_45 -> List.of(STRAIGHT_3, STRAIGHT, SMALL, TORCH);
            case TURNOUT_45_COMPACT -> List.of(STRAIGHT_3, STRAIGHT, RAIL, TIES, TORCH);
            case TURNOUT_45_MEDIUM, TURNOUT_PARALLEL -> List.of(STRAIGHT_8, MEDIUM, TORCH);
            case TURNOUT_45_LARGE -> List.of(STRAIGHT_12, LARGE, TORCH);
            case TURNOUT_PARALLEL_LONG -> List.of(STRAIGHT_12, STRAIGHT_3, LARGE, TORCH);
            case WYE -> List.of(SMALL, SMALL, TORCH);
            case WYE_MEDIUM -> List.of(MEDIUM, MEDIUM, TORCH);
            case SCISSORS -> List.of(STRAIGHT_12, STRAIGHT_12, MEDIUM, MEDIUM, TORCH, TORCH, TORCH, TORCH);
            case SCISSORS_LONG -> List.of("trainmod:scissors_rail", STRAIGHT_8, LARGE, LARGE);
            default -> throw new IllegalArgumentException("Missing track recipe for " + kind);
        };
    }
}
