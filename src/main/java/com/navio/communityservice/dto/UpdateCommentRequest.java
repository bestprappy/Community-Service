package com.navio.communityservice.dto;

import jakarta.validation.constraints.*;

public record UpdateCommentRequest(@NotBlank @Size(max = 10000) String body) { }
