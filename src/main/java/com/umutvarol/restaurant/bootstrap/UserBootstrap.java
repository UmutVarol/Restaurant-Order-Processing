package com.umutvarol.restaurant.bootstrap;

import com.umutvarol.restaurant.security.AppUser;
import com.umutvarol.restaurant.security.AppUserRepository;
import com.umutvarol.restaurant.security.Role;
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

    UserBootstrap(AppUserRepository users, PasswordEncoder passwordEncoder, DemoProperties demo,
                  @Value("${restaurant.bootstrap.admin-password:}") String adminPassword) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.demo = demo;
        this.adminPassword = adminPassword;
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
            log.info("Demo accounts created: admin/admin123, waiter/waiter123, chef/chef123");
        } else if (StringUtils.hasText(adminPassword)) {
            create("admin", adminPassword, "Administrator", Role.ADMIN);
            log.info("Initial administrator account 'admin' created");
        } else {
            log.warn("No user accounts exist. Set RESTAURANT_BOOTSTRAP_ADMIN_PASSWORD to create an administrator.");
        }
    }

    private void create(String username, String rawPassword, String displayName, Role role) {
        users.save(new AppUser(username, passwordEncoder.encode(rawPassword), displayName, role));
    }
}
