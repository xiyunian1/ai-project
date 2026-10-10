package com.example.myai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminAccountInitializer implements ApplicationRunner {
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.default-username:admin}")
    private String defaultUsername;

    @Value("${admin.default-password:123456}")
    private String defaultPassword;

    @Value("${admin.default-display-name:系统管理员}")
    private String defaultDisplayName;

    public AdminAccountInitializer(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = safe(defaultUsername, "admin");
        UserAccount account = userAccountRepository.findByUsername(username).orElseGet(UserAccount::new);
        account.setUsername(username);
        account.setDisplayName(safe(defaultDisplayName, username));
        account.setPasswordHash(passwordEncoder.encode(safe(defaultPassword, "123456")));
        account.setRole("ADMIN");
        account.setEnabled(true);
        userAccountRepository.save(account);
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
