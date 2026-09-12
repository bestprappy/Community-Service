package com.navio.communityservice.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record CreateCommentRequest(@NotBlank @Size(max = 10000) String body, UUID parentCommentId) { }
