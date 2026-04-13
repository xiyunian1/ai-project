package com.example.myai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AuthDataInitializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(AuthDataInitializer.class);

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${auth.bootstrap-admin.enabled:false}")
    private boolean enabled;

    @Value("${auth.bootstrap-admin.username:admin}")
    private String username;

    @Value("${auth.bootstrap-admin.display-name:管理员}")
    private String displayName;

    @Value("${auth.bootstrap-admin.password:}")
    private String password;

    public AuthDataInitializer(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            return;
        }

        if (password == null || password.isBlank()) {
            log.warn("Skip bootstrap admin: BOOTSTRAP_ADMIN_PASSWORD is empty.");
            return;
        }

        if (userAccountRepository.existsByUsername(username)) {
            return;
        }

        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setDisplayName(displayName == null || displayName.isBlank() ? username : displayName);
        account.setPasswordHash(passwordEncoder.encode(password));
        userAccountRepository.save(account);
        log.info("Bootstrap admin created: {}", username);
    }
}
