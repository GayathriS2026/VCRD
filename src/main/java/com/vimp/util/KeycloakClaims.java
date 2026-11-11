package com.vimp.util;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.security.oauth2.jwt.Jwt;

import com.vimp.constants.Role;

/** Reads roles and sites from a Keycloak access token. */
public final class KeycloakClaims {

    /** Optional custom claim (user attribute mapper) listing the user's site codes. */
    public static final String SITES_CLAIM = "sites";

    private KeycloakClaims() {
    }

    /**
     * With resource-role mappings, roles come from resource_access.&lt;client&gt;.roles (client roles);
     * otherwise from realm_access.roles. Names are matched to {@link Role} ignoring case, '-' vs '_' and
     * a "ROLE_" prefix; unknown roles are ignored.
     */
    public static Set<Role> roles(Jwt jwt, String clientId, boolean useResourceRoleMappings) {
        return roles(jwt, clientId, useResourceRoleMappings, Map.of());
    }

    /** As above, but Keycloak role names found in {@code aliases} (normalized keys) also grant the mapped role. */
    public static Set<Role> roles(Jwt jwt, String clientId, boolean useResourceRoleMappings,
            Map<String, Role> aliases) {
        Collection<?> names = useResourceRoleMappings
                ? nestedList(jwt.getClaimAsMap("resource_access"), clientId)
                : listIn(jwt.getClaimAsMap("realm_access"));
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (Object name : names) {
            String normalized = normalize(String.valueOf(name));
            Role alias = aliases.get(normalized);
            if (alias != null) {
                roles.add(alias);
            }
            for (Role role : Role.values()) {
                if (role.name().equals(normalized)) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }

    /**
     * Parses "KEYCLOAK_ROLE=APP_ROLE,..." (e.g. "DEVELOPER=PORTAL_ADMIN"). Blank means no aliases;
     * an unknown app role fails startup rather than silently granting nothing.
     */
    public static Map<String, Role> parseAliases(String spec) {
        Map<String, Role> aliases = new HashMap<>();
        if (spec == null || spec.isBlank()) {
            return aliases;
        }
        for (String pair : spec.split(",")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2 || parts[0].isBlank()) {
                throw new IllegalArgumentException("Bad keycloak.role-aliases entry: " + pair);
            }
            aliases.put(normalize(parts[0]), Role.valueOf(normalize(parts[1])));
        }
        return aliases;
    }

    private static String normalize(String name) {
        String normalized = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return normalized.startsWith("ROLE_") ? normalized.substring(5) : normalized;
    }

    /** Site codes from the token, or null when the token carries no site claim. */
    public static Set<String> sites(Jwt jwt) {
        List<String> sites = jwt.getClaimAsStringList(SITES_CLAIM);
        return sites == null ? null : Set.copyOf(sites);
    }

    private static Collection<?> nestedList(Map<String, Object> resourceAccess, String clientId) {
        if (resourceAccess == null || !(resourceAccess.get(clientId) instanceof Map<?, ?> client)) {
            return List.of();
        }
        return client.get("roles") instanceof Collection<?> c ? c : List.of();
    }

    private static Collection<?> listIn(Map<String, Object> realmAccess) {
        if (realmAccess == null) {
            return List.of();
        }
        return realmAccess.get("roles") instanceof Collection<?> c ? c : List.of();
    }
}
