package com.navio.communityservice.security;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.lang.annotation.*;
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@AuthenticationPrincipal(expression = "#this instanceof T(org.springframework.security.oauth2.jwt.Jwt) ? T(java.util.UUID).fromString(subject) : null")
public @interface CurrentUser { }
