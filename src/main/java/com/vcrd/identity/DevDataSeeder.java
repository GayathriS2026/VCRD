package com.vcrd.identity;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds demo users for local development. Never active in the "prod" profile;
 * real users will come from enterprise SSO.
 */
@Component
@Profile("!prod")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    public static final String DEMO_PASSWORD = "Password@123";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public DevDataSeeder(AppUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (users.count() > 0) {
            return;
        }
        String hash = passwordEncoder.encode(DEMO_PASSWORD);
        users.save(new AppUser("site.user", hash, "Site User (SITE-01)", Set.of(Role.SITE_USER), Set.of("SITE-01")));
        users.save(new AppUser("site2.user", hash, "Site User (SITE-02)", Set.of(Role.SITE_USER), Set.of("SITE-02")));
        users.save(new AppUser("pmo.user", hash, "PMO User", Set.of(Role.PMO), Set.of()));
        users.save(new AppUser("approver", hash, "Approver", Set.of(Role.APPROVER), Set.of()));
        users.save(new AppUser("admin", hash, "Portal Admin", Set.of(Role.PORTAL_ADMIN), Set.of()));
        users.save(new AppUser("auditor", hash, "Auditor", Set.of(Role.AUDITOR), Set.of()));
        log.info("Seeded demo users (password: {})", DEMO_PASSWORD);
    }
}
