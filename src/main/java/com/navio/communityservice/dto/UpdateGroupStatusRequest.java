package com.navio.communityservice.dto;
import jakarta.validation.constraints.*;
public record UpdateGroupStatusRequest(@NotNull @Pattern(regexp = "active|archived") String status) { }
