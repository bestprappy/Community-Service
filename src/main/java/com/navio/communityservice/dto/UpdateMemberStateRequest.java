package com.navio.communityservice.dto;
import jakarta.validation.constraints.*;
public record UpdateMemberStateRequest(@NotNull Boolean banned) { }
