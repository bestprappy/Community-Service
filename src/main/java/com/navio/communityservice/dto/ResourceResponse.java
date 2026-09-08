package com.navio.communityservice.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public record ResourceResponse(UUID id, String label, String url, int displayOrder) { }
