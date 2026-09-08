package com.navio.communityservice.exception;
import lombok.Getter;
import org.springframework.http.HttpStatus;
@Getter
public class GroupException extends RuntimeException {
    private final HttpStatus status;
    public GroupException(HttpStatus status, String message) { super(message); this.status = status; }
    public static GroupException invalid(String message) { return new GroupException(HttpStatus.BAD_REQUEST, message); }
    public static GroupException conflict(String message) { return new GroupException(HttpStatus.CONFLICT, message); }
    public static GroupException forbidden(String message) { return new GroupException(HttpStatus.FORBIDDEN, message); }
    public static GroupException notFound() { return new GroupException(HttpStatus.NOT_FOUND, "Group not found"); }
}
