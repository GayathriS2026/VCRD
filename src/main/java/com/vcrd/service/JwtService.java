package com.vcrd.service;

import com.vcrd.config.SecurityConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.vcrd.entity.AppUser;

/**
 * Issues portal JWTs for the interim username/password login.
 * When enterprise SSO is confirmed, tokens will come from the identity provider instead
 * and only the decoder configuration in SecurityConfig needs to change.
 */
@Service
public class JwtService {

    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_SITES = "sites";

    private final JwtEncoder encoder;
    private final Duration expiry;

    public JwtService(JwtEncoder encoder, @Value("${vcrd.jwt.expiry-minutes}") long expiryMinutes) {
        this.encoder = encoder;
        this.expiry = Duration.ofMinutes(expiryMinutes);
    }

    public String issue(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("vcrd")
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(now.plus(expiry))
                .claim(CLAIM_USER_ID, user.getId().toString())
                .claim(CLAIM_ROLES, user.getRoles().stream().map(Enum::name).toList())
                .claim(CLAIM_SITES, List.copyOf(user.getSites()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long expirySeconds() {
        return expiry.toSeconds();
    }
}
