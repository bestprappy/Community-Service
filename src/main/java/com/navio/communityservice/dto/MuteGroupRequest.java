package com.navio.communityservice.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public record MuteGroupRequest(@NotNull Boolean muted) { }
