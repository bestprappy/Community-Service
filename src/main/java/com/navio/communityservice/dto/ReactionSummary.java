package com.navio.communityservice.dto;

/** One reaction on a comment: how many members left it and whether the viewer is one of them. */
public record ReactionSummary(String reaction, int count, boolean reacted) { }
