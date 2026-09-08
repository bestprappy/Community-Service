package com.navio.communityservice.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public record CreateGroupRequest(@NotBlank @Size(max = 120) String name, @NotBlank String description, @Size(max = 120) String country, List<@NotNull String> places, List<@NotNull String> tags) { }
