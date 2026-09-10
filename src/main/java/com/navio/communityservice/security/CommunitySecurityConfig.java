package com.navio.communityservice.security;

import com.navio.communityservice.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

@Configuration
@EnableWebSecurity
public class CommunitySecurityConfig {
    @Bean
    SecurityFilterChain communitySecurity(HttpSecurity http, JwtDecoder decoder, ObjectMapper mapper) throws Exception {
        return http.csrf(c -> c.disable()).cors(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/groups/mine").authenticated()
                        .requestMatchers(HttpMethod.GET, "/v1/groups", "/v1/groups/search", "/v1/groups/{slug}", "/v1/groups/{slug}/banner").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder))
                        .authenticationEntryPoint((r, s, e) -> error(s, mapper, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((r, s, e) -> error(s, mapper, HttpStatus.FORBIDDEN)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((r, s, x) -> error(s, mapper, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((r, s, x) -> error(s, mapper, HttpStatus.FORBIDDEN)))
                .build();
    }
    @Bean
    JwtDecoder communityJwtDecoder(@Value("${navio.security.keycloak.issuer-uri}") String issuer,
            @Value("${navio.security.keycloak.jwk-set-uri}") String keys,
            @Value("${navio.security.keycloak.audiences}") List<String> audiences) {
        if (issuer.isBlank() || audiences.isEmpty() || audiences.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Issuer and accepted audiences are required");
        }
        NimbusJwtDecoder decoder = keys.isBlank() ? (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer)
                : NimbusJwtDecoder.withJwkSetUri(keys).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                claimsValidator(audiences)));
        return decoder;
    }
    static OAuth2TokenValidator<Jwt> claimsValidator(List<String> audiences) {
        return jwt -> {
            try {
                if (jwt.getExpiresAt() == null || jwt.getAudience() == null
                        || Collections.disjoint(jwt.getAudience(), audiences)
                        || !UUID.fromString(jwt.getSubject()).toString().equalsIgnoreCase(jwt.getSubject())) {
                    throw new IllegalArgumentException();
                }
                return OAuth2TokenValidatorResult.success();
            } catch (IllegalArgumentException | NullPointerException e) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token claims", null));
            }
        };
    }
    private static void error(HttpServletResponse response, ObjectMapper mapper, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        mapper.writeValue(response.getOutputStream(), new ErrorResponse(Instant.now(), status.value(),
                status == HttpStatus.UNAUTHORIZED ? "Authentication is required" : "Access denied", status.getReasonPhrase(), null));
    }
}
