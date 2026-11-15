package com.navio.communityservice.dto;
import java.util.*;
/** {@code tone} and {@code colorId} stay for clients from before free colors. */
public record FlairResponse(UUID id, String label, String tone, String colorId, String color, String textColor,
        int displayOrder) { }
