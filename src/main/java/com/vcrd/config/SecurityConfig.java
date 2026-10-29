package com.vcrd.config;

import com.vcrd.service.JwtService;
import com.vcrd.util.KeycloakClaims;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Two sign-in modes (vcrd.auth.mode):
 * - keycloak: bearer-only resource server. Tokens are issued by Keycloak (realm keycloak.realm) and verified
 *   against its published signing keys; roles come from the client roles of keycloak.resource.
 * - local: development login (/api/v1/auth/login) with tokens signed by vcrd.jwt.secret.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final boolean keycloakMode;
    private final SecretKey secretKey;
    private final String keycloakIssuer;
    private final String keycloakClientId;
    private final boolean useResourceRoleMappings;

    public SecurityConfig(@Value("${vcrd.auth.mode:local}") String authMode,
            @Value("${vcrd.jwt.secret}") String secret,
            @Value("${keycloak.auth-server-url}") String authServerUrl,
            @Value("${keycloak.realm}") String realm,
            @Value("${keycloak.resource}") String clientId,
            @Value("${keycloak.use-resource-role-mappings:true}") boolean useResourceRoleMappings) {
        this.keycloakMode = "keycloak".equalsIgnoreCase(authMode);
        if (!keycloakMode && secret.length() < 32) {
            throw new IllegalStateException("vcrd.jwt.secret must be at least 32 characters");
        }
        this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.keycloakIssuer = authServerUrl.replaceAll("/+$", "") + "/realms/" + realm;
        this.keycloakClientId = clientId;
        this.useResourceRoleMappings = useResourceRoleMappings;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.frameOptions(f -> f.sameOrigin())) // H2 console uses frames
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                antMatcher("/api/v1/auth/**"),
                                antMatcher("/swagger-ui.html"),
                                antMatcher("/swagger-ui/**"),
                                antMatcher("/v3/api-docs/**"),
                                antMatcher("/h2-console/**"),
                                antMatcher("/error"))
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(
                        keycloakMode ? keycloakAuthenticationConverter() : localAuthenticationConverter())));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder() {
        if (!keycloakMode) {
            return NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
        }
        // Signing keys are fetched lazily (and cached) from Keycloak, so startup does not depend on it.
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(keycloakIssuer + "/protocol/openid-connect/certs")
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(keycloakIssuer), this::issuedForThisClient));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Accept tokens meant for this API: audience contains the client, or the client itself requested it. */
    private OAuth2TokenValidatorResult issuedForThisClient(Jwt jwt) {
        List<String> audience = jwt.getAudience();
        boolean ok = (audience != null && audience.contains(keycloakClientId))
                || keycloakClientId.equals(jwt.getClaimAsString("azp"));
        return ok ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "Token was not issued for client " + keycloakClientId, null));
    }

    private Converter<Jwt, AbstractAuthenticationToken> keycloakAuthenticationConverter() {
        return jwt -> new JwtAuthenticationToken(jwt,
                KeycloakClaims.roles(jwt, keycloakClientId, useResourceRoleMappings).stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                        .collect(Collectors.toList()),
                jwt.getClaimAsString("preferred_username"));
    }

    private JwtAuthenticationConverter localAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtService.CLAIM_ROLES);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
