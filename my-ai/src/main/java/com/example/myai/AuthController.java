package com.example.myai;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        String username = request.getUsername().trim();
        String password = request.getPassword();

        Optional<UserAccount> userOpt = userAccountRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            log.warn("Login failed: account not found, username={}", username);
            return error(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }

        UserAccount userAccount = userOpt.get();
        if (!Boolean.TRUE.equals(userAccount.getEnabled())) {
            log.warn("Login failed: account disabled, username={}", username);
            return error(HttpStatus.UNAUTHORIZED, "账号已被禁用");
        }
        if (!passwordEncoder.matches(password, userAccount.getPasswordHash())) {
            log.warn("Login failed: password mismatch, username={}", username);
            return error(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }

        String token = jwtService.generateToken(userAccount.getUsername(), userAccount.getDisplayName(), userAccount.getRole());
        long expiresAt = Instant.now().toEpochMilli() + jwtService.getExpirationMs();

        Map<String, Object> body = okBody("登录成功");
        body.put("token", token);
        body.put("expiresAt", expiresAt);
        body.put("username", userAccount.getDisplayName());
        body.put("account", userAccount.getUsername());
        body.put("role", userAccount.getRole());

        log.info("Login success: username={}", username);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername
    ) {
        if (authUsername == null || authUsername.isBlank()) {
            return error(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }

        Optional<UserAccount> userOpt = userAccountRepository.findByUsername(authUsername);
        if (userOpt.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "用户不存在");
        }

        UserAccount userAccount = userOpt.get();
        if (!passwordEncoder.matches(request.getCurrentPassword(), userAccount.getPasswordHash())) {
            return error(HttpStatus.UNAUTHORIZED, "当前密码错误");
        }

        userAccount.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userAccountRepository.save(userAccount);
        log.info("Password changed: username={}", authUsername);
        return ResponseEntity.ok(okBody("密码修改成功"));
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) RegisterRequest request) {
        return error(HttpStatus.FORBIDDEN, "请联系管理员创建账号");
    }

    private ResponseEntity<Map<String, Object>> registerLegacy(@Valid @RequestBody RegisterRequest request) {
        String username = request.getUsername().trim();
        String password = request.getPassword();
        String displayName = request.getDisplayName() == null ? "" : request.getDisplayName().trim();

        if (userAccountRepository.existsByUsername(username)) {
            return error(HttpStatus.CONFLICT, "账号已存在");
        }

        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setDisplayName(displayName.isBlank() ? username : displayName);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setRole("USER");
        account.setEnabled(true);
        userAccountRepository.save(account);

        Map<String, Object> body = new HashMap<>();
        body.put("code", HttpStatus.CREATED.value());
        body.put("message", "注册成功");
        body.put("username", account.getDisplayName());

        log.info("Register success: username={}", username);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", status.value());
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }

    private Map<String, Object> okBody(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", message);
        return response;
    }
}
