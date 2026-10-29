package com.vcrd.config;

import com.vcrd.entity.AppUser;
import com.vcrd.repository.AppUserRepository;
import com.vcrd.constants.Role;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds demo users when using the built-in login. Off in the "prod" profile unless VCRD_DEMO_USERS=true;
 * real users come from Keycloak once VCRD_AUTH_MODE=keycloak.
 */
@Component
@ConditionalOnProperty(name = "vcrd.auth.mode", havingValue = "local", matchIfMissing = true)
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    public static final String DEMO_PASSWORD = "Password@123";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    private final boolean demoUsersEnabled;

    public DevDataSeeder(AppUserRepository users, PasswordEncoder passwordEncoder,
            @org.springframework.beans.factory.annotation.Value("${vcrd.demo-users.enabled:true}") boolean demoUsersEnabled) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.demoUsersEnabled = demoUsersEnabled;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!demoUsersEnabled) {
            return;
        }
        String hash = passwordEncoder.encode(DEMO_PASSWORD);
        int added = 0;
        added += seed(new AppUser("site.user", hash, "Site User (SITE-01)", Set.of(Role.SITE_USER), Set.of("SITE-01")));
        added += seed(new AppUser("site2.user", hash, "Site User (SITE-02)", Set.of(Role.SITE_USER), Set.of("SITE-02")));
        added += seed(new AppUser("pmo.user", hash, "PMO User", Set.of(Role.PMO), Set.of()));
        added += seed(new AppUser("approver", hash, "Approver", Set.of(Role.APPROVER), Set.of()));
        added += seed(new AppUser("finance.user", hash, "Finance User", Set.of(Role.FINANCE), Set.of()));
        added += seed(new AppUser("admin", hash, "Portal Admin", Set.of(Role.PORTAL_ADMIN), Set.of()));
        added += seed(new AppUser("auditor", hash, "Auditor", Set.of(Role.AUDITOR), Set.of()));
        if (added > 0) {
            log.info("Seeded {} demo users (password: {})", added, DEMO_PASSWORD);
        }
    }

    /** Adds the user only if missing, so new demo users also appear in existing dev databases. */
    private int seed(AppUser user) {
        if (users.findByUsername(user.getUsername()).isPresent()) {
            return 0;
        }
        users.save(user);
        return 1;
    }
}
