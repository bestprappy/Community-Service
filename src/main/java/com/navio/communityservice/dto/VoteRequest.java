package com.navio.communityservice.dto;

import jakarta.validation.constraints.*;

public record VoteRequest(@NotNull @Min(-1) @Max(1) Integer value) { }
