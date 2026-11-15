package com.navio.communityservice.dto;
import java.util.*;
/** {@code tone} stays for clients from before palette colors. */
public record FlairResponse(UUID id, String label, String tone, String colorId, int displayOrder) { }
