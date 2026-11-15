package com.navio.communityservice.service;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves a flair's color for rows and requests from before free colors.
 * Mirrors migrations V11 (tone to palette id) and V12 (palette id to hex).
 */
final class FlairColors {
    static final String DEFAULT_TONE = "reliable";
    static final String DEFAULT_TEXT = "white";
    private static final String DEFAULT_COLOR_ID = "teal";
    private static final String DEFAULT_HEX = "#00ae84";
    private static final Map<String, String> ID_BY_TONE = Map.of(
            "reliable", "emerald", "question", "blue", "unsourced", "graphite",
            "speculation", "amber", "itinerary", "teal", "food", "coral", "ev", "lime");
    private static final Map<String, String> HEX_BY_ID = Map.ofEntries(
            Map.entry("teal", "#00ae84"), Map.entry("cyan", "#00b2b2"), Map.entry("blue", "#008cdf"),
            Map.entry("indigo", "#5d67ee"), Map.entry("violet", "#8654cc"), Map.entry("rose", "#d83e89"),
            Map.entry("coral", "#f0584b"), Map.entry("amber", "#ea8a18"), Map.entry("emerald", "#009342"),
            Map.entry("pine", "#00603c"), Map.entry("navy", "#005189"), Map.entry("slate", "#3b4372"),
            Map.entry("plum", "#613888"), Map.entry("wine", "#972767"), Map.entry("brick", "#ac312a"),
            Map.entry("rust", "#a94700"), Map.entry("lime", "#56932b"), Map.entry("olive", "#677014"),
            Map.entry("gold", "#aa7e00"), Map.entry("sky", "#089ac3"), Map.entry("fuchsia", "#b243ba"),
            Map.entry("mocha", "#7c5336"), Map.entry("stone", "#766d62"), Map.entry("graphite", "#4e535b"));

    private FlairColors() { }

    /** The saved palette id, or the one the flair's tone used to stand for. */
    static String resolveId(String colorId, String tone) {
        if (colorId != null && !colorId.isBlank()) return colorId;
        return tone == null ? DEFAULT_COLOR_ID : ID_BY_TONE.getOrDefault(tone, DEFAULT_COLOR_ID);
    }

    /** The saved hex, else the hex of the palette id or tone it came from. Always lower case. */
    static String resolveHex(String hex, String colorId, String tone) {
        if (hex != null && !hex.isBlank()) return hex.toLowerCase(Locale.ROOT);
        return HEX_BY_ID.getOrDefault(resolveId(colorId, tone), DEFAULT_HEX);
    }

    static String resolveText(String textColor) {
        return "black".equals(textColor) ? "black" : DEFAULT_TEXT;
    }
}
