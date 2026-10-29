package com.vcrd.security;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import com.vcrd.identity.Role;

public record CurrentUser(UUID id, String username, Set<Role> roles, Set<String> sites) {

    public boolean hasAnyRole(Collection<Role> candidates) {
        return candidates.stream().anyMatch(roles::contains);
    }

    public boolean hasGlobalAccess() {
        return roles.stream().anyMatch(Role::isGlobal);
    }

    public boolean canAccessSite(String siteCode) {
        return hasGlobalAccess() || sites.contains(siteCode);
    }
}
