package com.navio.communityservice.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public record RuleRequest(@NotBlank @Size(max = 120) String title, @NotBlank String description) { }
