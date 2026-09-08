package com.navio.communityservice.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public record ReplaceResourcesRequest(@NotNull List<@NotNull @Valid ResourceRequest> resources) { }
