package com.navio.communityservice.exception;
import java.time.Instant;
import java.util.Map;
public record ErrorResponse(Instant timestamp, int status, String message, String error, Map<String, String> validationErrors) { }
