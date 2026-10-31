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
    private static final Set<String> ADMINISTRATOR_ROLES = Set.of("ADMIN", "OWNER");

    private PlatformModerator() {
    }

    public static boolean is(Jwt jwt, List<String> acceptedAudiences) {
        return holdsAny(jwt, acceptedAudiences, ROLES);
    }

    /** Platform administrators only (ADMIN or OWNER): operational actions such as event replay. */
    public static boolean isAdministrator(Jwt jwt, List<String> acceptedAudiences) {
        return holdsAny(jwt, acceptedAudiences, ADMINISTRATOR_ROLES);
    }

    private static boolean holdsAny(Jwt jwt, List<String> acceptedAudiences, Set<String> wanted) {
        if (jwt == null) return false;
        if (hasRole(jwt.getClaims().get("realm_access"), wanted)) return true;
        if (jwt.getClaims().get("resource_access") instanceof Map<?, ?> clients) {
            for (String audience : acceptedAudiences) {
                if (hasRole(clients.get(audience), wanted)) return true;
            }
        }
        return false;
    }

    private static boolean hasRole(Object access, Set<String> wanted) {
        if (!(access instanceof Map<?, ?> map) || !(map.get("roles") instanceof Collection<?> roles)) return false;
        return roles.stream().anyMatch(role -> role instanceof String name
                && wanted.contains(name.trim().toUpperCase(Locale.ROOT)));
    }
}
