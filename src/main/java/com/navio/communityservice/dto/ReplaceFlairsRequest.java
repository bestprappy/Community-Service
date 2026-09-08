package com.navio.communityservice.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public record ReplaceFlairsRequest(@NotNull List<@NotNull @Valid FlairRequest> flairs) { }
