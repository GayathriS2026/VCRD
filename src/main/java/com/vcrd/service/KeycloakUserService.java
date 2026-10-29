package com.vcrd.service;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.vcrd.constants.Role;
import com.vcrd.dto.CurrentUser;
import com.vcrd.entity.AppUser;
import com.vcrd.repository.AppUserRepository;
import com.vcrd.util.KeycloakClaims;

/**
 * Maps a Keycloak user to the local user record that invoices, documents and history refer to.
 * The record is created on first sign-in and its roles kept in line with the token.
 * Sites come from the token's "sites" claim when Keycloak provides it; otherwise from the
 * site assignments stored locally (maintained by an administrator).
 */
@Service
public class KeycloakUserService {

    private final AppUserRepository users;
    private final String clientId;
    private final boolean useResourceRoleMappings;

    public KeycloakUserService(AppUserRepository users, @Value("${keycloak.resource}") String clientId,
            @Value("${keycloak.use-resource-role-mappings:true}") boolean useResourceRoleMappings) {
        this.users = users;
        this.clientId = clientId;
        this.useResourceRoleMappings = useResourceRoleMappings;
    }

    public Set<Role> roles(Jwt jwt) {
        return KeycloakClaims.roles(jwt, clientId, useResourceRoleMappings);
    }

    /** Own transaction: provisioning must commit even if the caller's work later rolls back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CurrentUser resolve(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        if (username == null || username.isBlank()) {
            throw new AccessDeniedException("Token has no preferred_username");
        }
        Set<Role> roles = roles(jwt);
        String displayName = jwt.getClaimAsString("name");
        String email = jwt.getClaimAsString("email");
        Set<String> tokenSites = KeycloakClaims.sites(jwt);

        AppUser user = users.findByUsername(username).orElse(null);
        if (user == null) {
            try {
                user = users.saveAndFlush(new AppUser(username, AppUser.EXTERNAL_PASSWORD, displayName, roles,
                        tokenSites == null ? Set.of() : tokenSites));
            } catch (DataIntegrityViolationException concurrentFirstLogin) {
                user = users.findByUsername(username).orElseThrow(() -> concurrentFirstLogin);
            }
        }
        if (!user.isActive()) {
            throw new AccessDeniedException("User " + username + " is deactivated in the portal");
        }
        user.syncFromIdentityProvider(displayName, email, roles);
        if (tokenSites != null) {
            user.replaceSites(tokenSites);
        }
        return new CurrentUser(user.getId(), username, roles, Set.copyOf(user.getSites()));
    }
}
