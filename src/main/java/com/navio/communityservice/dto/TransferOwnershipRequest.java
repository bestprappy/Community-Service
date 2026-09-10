package com.navio.communityservice.dto;
import jakarta.validation.constraints.*;
import java.util.UUID;
public record TransferOwnershipRequest(@NotNull UUID userId) { }
