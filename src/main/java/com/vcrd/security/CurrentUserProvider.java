package com.vcrd.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.vcrd.identity.Role;

@Component
public class CurrentUserProvider {

    public CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw new AccessDeniedException("Not authenticated");
        }
        Jwt jwt = token.getToken();
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
