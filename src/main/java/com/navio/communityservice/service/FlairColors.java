package com.navio.communityservice.service;

import java.util.Map;

/** Maps the retired flair tones onto admin palette colors. Mirrors migration V11. */
final class FlairColors {
    static final String DEFAULT_TONE = "reliable";
    private static final String DEFAULT_COLOR = "teal";
    private static final Map<String, String> BY_TONE = Map.of(
            "reliable", "emerald", "question", "blue", "unsourced", "graphite",
            "speculation", "amber", "itinerary", "teal", "food", "coral", "ev", "lime");

    private FlairColors() { }

    /** The saved color, or the color the flair's tone used to stand for. */
    static String resolve(String colorId, String tone) {
        if (colorId != null && !colorId.isBlank()) return colorId;
        return tone == null ? DEFAULT_COLOR : BY_TONE.getOrDefault(tone, DEFAULT_COLOR);
    }
}
