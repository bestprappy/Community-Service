package com.navio.communityservice.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public record MemberResponse(UUID userId, String role, String state, java.time.Instant joinedAt, java.time.Instant updatedAt) { }
