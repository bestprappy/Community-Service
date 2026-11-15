package com.navio.communityservice.dto;
import jakarta.validation.constraints.*;
/**
 * A flair is a label, any {@code #rrggbb} color and white or black text. {@code flairType},
 * {@code tone} and {@code colorId} remain optional for older clients: missing type means a post
 * flair, and a missing color is derived from the palette id or tone.
 */
public record FlairRequest(@Pattern(regexp = "post|user") String flairType, @NotBlank @Size(max = 80) String label,
        @Pattern(regexp = "reliable|question|unsourced|speculation|itinerary|food|ev") String tone,
        @Pattern(regexp = "[a-z][a-z0-9-]{0,39}", message = "Choose a color from the palette.") String colorId,
        @Pattern(regexp = "#[0-9a-fA-F]{6}", message = "Choose a color as #rrggbb.") String color,
        @Pattern(regexp = "white|black", message = "Text color must be white or black.") String textColor) { }
