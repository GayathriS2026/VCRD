package com.vcrd;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.vcrd.constants.Role;
import com.vcrd.util.KeycloakClaims;

class KeycloakClaimsTest {

    private static Jwt token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    void readsClientRolesOfConfiguredClientOnly() {
        Jwt jwt = token(Map.of(
                "preferred_username", "gayathri",
                "resource_access", Map.of(
                        "hse", Map.of("roles", List.of("pmo", "SITE-USER", "ROLE_APPROVER", "unrelated-role")),
                        "other-app", Map.of("roles", List.of("PORTAL_ADMIN"))),
                "realm_access", Map.of("roles", List.of("AUDITOR"))));

        assertThat(KeycloakClaims.roles(jwt, "hse", true))
                .containsExactlyInAnyOrder(Role.PMO, Role.SITE_USER, Role.APPROVER);
    }

    @Test
    void readsRealmRolesWhenResourceMappingIsOff() {
        Jwt jwt = token(Map.of("realm_access", Map.of("roles", List.of("auditor"))));
        assertThat(KeycloakClaims.roles(jwt, "hse", false)).containsExactly(Role.AUDITOR);
    }

    @Test
    void noRolesWhenClientMissingFromToken() {
        Jwt jwt = token(Map.of("resource_access", Map.of("other-app", Map.of("roles", List.of("PMO")))));
        assertThat(KeycloakClaims.roles(jwt, "hse", true)).isEmpty();
    }

    @Test
    void sitesClaimIsOptional() {
        assertThat(KeycloakClaims.sites(token(Map.of("sub", "x")))).isNull();
        assertThat(KeycloakClaims.sites(token(Map.of("sites", List.of("SITE-01"))))).containsExactly("SITE-01");
    }
}
