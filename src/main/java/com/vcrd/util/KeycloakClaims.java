package com.vcrd.util;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.security.oauth2.jwt.Jwt;

import com.vcrd.constants.Role;

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
        Collection<?> names = useResourceRoleMappings
                ? nestedList(jwt.getClaimAsMap("resource_access"), clientId)
                : listIn(jwt.getClaimAsMap("realm_access"));
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (Object name : names) {
            String normalized = String.valueOf(name).toUpperCase(Locale.ROOT).replace('-', '_');
            if (normalized.startsWith("ROLE_")) {
                normalized = normalized.substring(5);
            }
            for (Role role : Role.values()) {
                if (role.name().equals(normalized)) {
                    roles.add(role);
                }
            }
        }
        return roles;
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
