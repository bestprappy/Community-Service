package com.navio.communityservice.dto;
import jakarta.validation.constraints.*;
/**
 * A flair is a label and an admin palette color. {@code flairType} and {@code tone} are optional
 * for clients from before palette colors: missing type means a post flair, and a missing color is
 * derived from the tone.
 */
public record FlairRequest(@Pattern(regexp = "post|user") String flairType, @NotBlank @Size(max = 80) String label,
        @Pattern(regexp = "reliable|question|unsourced|speculation|itinerary|food|ev") String tone,
        @Pattern(regexp = "[a-z][a-z0-9-]{0,39}", message = "Choose a color from the palette.") String colorId) { }
