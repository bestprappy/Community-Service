package com.navio.communityservice.dto;

import jakarta.validation.constraints.*;

public record UpdatePostRequest(@NotBlank @Size(max = 300) String title,
        @NotNull @Size(max = 40000) String body) { }
