package com.navio.communityservice.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Whether a validated token carries a platform moderation role.
 *
 * <p>Mirrors the user management service's reading of Keycloak roles: realm
 * roles from {@code realm_access.roles}, plus client roles under
 * {@code resource_access.<audience>.roles} only for audiences this API accepts.
 * Community-group moderators are a different, per-group role and do not count.
 */
public final class PlatformModerator {

    private static final Set<String> ROLES = Set.of("MODERATOR", "ADMIN", "OWNER");

    private PlatformModerator() {
    }

    public static boolean is(Jwt jwt, List<String> acceptedAudiences) {
        if (jwt == null) return false;
        if (hasRole(jwt.getClaims().get("realm_access"))) return true;
        if (jwt.getClaims().get("resource_access") instanceof Map<?, ?> clients) {
            for (String audience : acceptedAudiences) {
                if (hasRole(clients.get(audience))) return true;
            }
        }
        return false;
    }

    private static boolean hasRole(Object access) {
        if (!(access instanceof Map<?, ?> map) || !(map.get("roles") instanceof Collection<?> roles)) return false;
        return roles.stream().anyMatch(role -> role instanceof String name
                && ROLES.contains(name.trim().toUpperCase(Locale.ROOT)));
    }
}
