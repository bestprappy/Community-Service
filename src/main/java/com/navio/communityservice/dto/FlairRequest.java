package com.navio.communityservice.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public record FlairRequest(@NotNull @Pattern(regexp = "post|user") String flairType, @NotBlank @Size(max = 80) String label, @NotNull @Pattern(regexp = "reliable|question|unsourced|speculation|itinerary|food|ev") String tone) { }
