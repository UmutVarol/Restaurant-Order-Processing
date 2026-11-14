package com.umutvarol.restaurant.bootstrap;

import com.umutvarol.restaurant.security.AppUser;
import com.umutvarol.restaurant.security.AppUserRepository;
import com.umutvarol.restaurant.security.Role;
import com.umutvarol.restaurant.security.StaffPinService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Makes sure someone can sign in to a fresh installation. In demo mode the three demo staff accounts are
 * created; otherwise a single {@code admin} is created from {@code restaurant.bootstrap.admin-password}.
 */
@Component
@Order(1)
class UserBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final DemoProperties demo;
    private final String adminPassword;
    private final StaffPinService pins;
    private final java.time.Clock clock;

    UserBootstrap(AppUserRepository users, PasswordEncoder passwordEncoder, DemoProperties demo, StaffPinService pins,
                  java.time.Clock clock, @Value("${restaurant.bootstrap.admin-password:}") String adminPassword) {
        this.clock = clock;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.demo = demo;
        this.adminPassword = adminPassword;
        this.pins = pins;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        if (demo.enabled()) {
            create("admin", "admin123", "Elif Yılmaz", Role.ADMIN);
            create("waiter", "waiter123", "Can Demir", Role.WAITER);
            create("chef", "chef123", "Mehmet Usta", Role.KITCHEN);
            pins.demoPins(Map.of("admin", "2468", "waiter", "1357", "chef", "9024"));
            log.info("Demo accounts created: admin/admin123, waiter/waiter123, chef/chef123 (PINs 2468, 1357, 9024)");
        } else if (StringUtils.hasText(adminPassword)) {
            // the installer chose this password, so the manager replaces it at the first sign-in
            users.save(AppUser.withTemporaryPassword("admin", passwordEncoder.encode(adminPassword), "Administrator",
                    Role.ADMIN, clock.instant()));
            log.info("Initial manager account 'admin' created; its password must be changed at the first sign-in");
        } else {
            log.warn("No user accounts exist. Set RESTAURANT_BOOTSTRAP_ADMIN_PASSWORD to create a manager account.");
        }
    }

    private void create(String username, String rawPassword, String displayName, Role role) {
        users.save(new AppUser(username, passwordEncoder.encode(rawPassword), displayName, role));
    }
}
