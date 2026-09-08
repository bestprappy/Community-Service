package com.navio.communityservice.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public record ResourceRequest(@NotBlank @Size(max = 120) String label, @Pattern(regexp = "https?://[^\\s]+", message = "must be an HTTP(S) URL") String url) { }
