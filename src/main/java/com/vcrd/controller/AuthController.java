package com.vcrd.controller;

import com.vcrd.dto.CurrentUser;
import com.vcrd.component.CurrentUserProvider;
import com.vcrd.service.JwtService;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.vcrd.entity.AppUser;
import com.vcrd.repository.AppUserRepository;
import com.vcrd.constants.Role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CurrentUserProvider currentUser;
    private final boolean keycloakMode;

    public AuthController(AppUserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
            CurrentUserProvider currentUser,
            @org.springframework.beans.factory.annotation.Value("${vcrd.auth.mode:local}") String authMode) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.currentUser = currentUser;
        this.keycloakMode = "keycloak".equalsIgnoreCase(authMode);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn) {
    }

    public record MeResponse(String username, Set<Role> roles, Set<String> sites) {
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        if (keycloakMode) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sign in through Keycloak");
        }
        AppUser user = users.findByUsername(request.username())
                .filter(AppUser::isActive)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        return new LoginResponse(jwtService.issue(user), "Bearer", jwtService.expirySeconds());
    }

    @GetMapping("/me")
    public MeResponse me() {
        CurrentUser user = currentUser.get();
        return new MeResponse(user.username(), user.roles(), user.sites());
    }
}
