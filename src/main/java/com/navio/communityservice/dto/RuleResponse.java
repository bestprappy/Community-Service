package com.navio.communityservice.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public record RuleResponse(UUID id, String title, String description, int displayOrder) { }
