package com.vcrd.entity;

import com.vcrd.constants.Role;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    private String displayName;

    private String email;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_site", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "site_code")
    private Set<String> sites = new HashSet<>();

    protected AppUser() {
    }

    public AppUser(String username, String passwordHash, String displayName, Set<Role> roles, Set<String> sites) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.roles = new HashSet<>(roles);
        this.sites = new HashSet<>(sites);
    }

    /** Marker for users who sign in through Keycloak; never matches a password. */
    public static final String EXTERNAL_PASSWORD = "{external}";

    /** Keeps the local copy of a Keycloak user in line with their token. */
    public boolean syncFromIdentityProvider(String displayName, String email, Set<Role> roles) {
        boolean changed = !java.util.Objects.equals(this.displayName, displayName)
                || !java.util.Objects.equals(this.email, email) || !this.roles.equals(roles);
        this.displayName = displayName;
        this.email = email;
        this.roles = new HashSet<>(roles);
        return changed;
    }

    public void replaceSites(Set<String> sites) {
        this.sites = new HashSet<>(sites);
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public boolean isActive() {
        return active;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public Set<String> getSites() {
        return sites;
    }
}
