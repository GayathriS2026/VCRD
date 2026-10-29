package com.vcrd.component;

import com.vcrd.dto.CurrentUser;
import com.vcrd.service.JwtService;
import com.vcrd.service.KeycloakUserService;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import com.vcrd.constants.Role;

@Component
public class CurrentUserProvider {

    private static final String REQUEST_CACHE_KEY = CurrentUserProvider.class.getName();

    private final ObjectProvider<KeycloakUserService> keycloakUsers;
    private final boolean keycloakMode;

    public CurrentUserProvider(ObjectProvider<KeycloakUserService> keycloakUsers,
            @Value("${vcrd.auth.mode:local}") String authMode) {
        this.keycloakUsers = keycloakUsers;
        this.keycloakMode = "keycloak".equalsIgnoreCase(authMode);
    }

    public CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw new AccessDeniedException("Not authenticated");
        }
        Jwt jwt = token.getToken();
        if (!keycloakMode) {
            return fromLocalToken(jwt);
        }
        // Keycloak token: resolve (and provision) the local user once per request.
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null && request.getAttribute(REQUEST_CACHE_KEY, RequestAttributes.SCOPE_REQUEST) instanceof CurrentUser cached) {
            return cached;
        }
        CurrentUser user = keycloakUsers.getObject().resolve(jwt);
        if (request != null) {
            request.setAttribute(REQUEST_CACHE_KEY, user, RequestAttributes.SCOPE_REQUEST);
        }
        return user;
    }

    private static CurrentUser fromLocalToken(Jwt jwt) {
        Set<Role> roles = toSet(jwt.getClaimAsStringList(JwtService.CLAIM_ROLES)).stream()
                .map(Role::valueOf)
                .collect(Collectors.toSet());
        return new CurrentUser(
                UUID.fromString(jwt.getClaimAsString(JwtService.CLAIM_USER_ID)),
                jwt.getSubject(),
                roles,
                toSet(jwt.getClaimAsStringList(JwtService.CLAIM_SITES)));
    }

    private static Set<String> toSet(List<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }
}
