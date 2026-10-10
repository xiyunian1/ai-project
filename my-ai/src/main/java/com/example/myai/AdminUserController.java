package com.example.myai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserController(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public List<Map<String, Object>> listUsers() {
        return userAccountRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDto)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createUser(@Valid @RequestBody CreateUserRequest request) {
        String username = request.username().trim();
        if (userAccountRepository.existsByUsername(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "账号已存在");
        }
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setDisplayName(safeDisplayName(request.displayName(), username));
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setRole(normalizeRole(request.role()));
        account.setEnabled(true);
        return toDto(userAccountRepository.save(account));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest request,
            @RequestAttribute(AuthInterceptor.ATTR_USERNAME) String authUsername
    ) {
        UserAccount account = getAccount(id);
        if (request.displayName() != null) {
            account.setDisplayName(safeDisplayName(request.displayName(), account.getUsername()));
        }
        if (request.role() != null) {
            account.setRole(normalizeRole(request.role()));
        }
        if (request.enabled() != null) {
            if (account.getUsername().equals(authUsername) && !request.enabled()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "不能禁用当前登录的管理员账号");
            }
            account.setEnabled(request.enabled());
        }
        return toDto(userAccountRepository.save(account));
    }

    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        UserAccount account = getAccount(id);
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        userAccountRepository.save(account);
        Map<String, Object> body = new HashMap<>();
        body.put("code", 200);
        body.put("message", "密码已重置");
        return body;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteUser(
            @PathVariable Long id,
            @RequestAttribute(AuthInterceptor.ATTR_USERNAME) String authUsername
    ) {
        UserAccount account = getAccount(id);
        if (account.getUsername().equals(authUsername)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不能删除当前登录的管理员账号");
        }
        userAccountRepository.delete(account);
        return Map.of("deleted", true);
    }

    private UserAccount getAccount(Long id) {
        return userAccountRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在"));
    }

    private Map<String, Object> toDto(UserAccount account) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", account.getId());
        dto.put("username", account.getUsername());
        dto.put("displayName", account.getDisplayName());
        dto.put("role", account.getRole());
        dto.put("enabled", account.getEnabled());
        dto.put("createdAt", account.getCreatedAt());
        dto.put("updatedAt", account.getUpdatedAt());
        return dto;
    }

    private String normalizeRole(String role) {
        String value = role == null ? "USER" : role.trim().toUpperCase();
        if (!"ADMIN".equals(value) && !"USER".equals(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "角色只能是 ADMIN 或 USER");
        }
        return value;
    }

    private String safeDisplayName(String displayName, String fallback) {
        if (displayName == null || displayName.isBlank()) {
            return fallback;
        }
        return displayName.trim();
    }

    public record CreateUserRequest(
            @NotBlank(message = "不能为空") @Size(min = 3, max = 64, message = "长度需要在 3 到 64 之间") String username,
            @NotBlank(message = "不能为空") @Size(min = 6, max = 128, message = "长度需要在 6 到 128 之间") String password,
            @Size(max = 64, message = "长度不能超过 64") String displayName,
            String role
    ) {
    }

    public record UpdateUserRequest(
            @Size(max = 64, message = "长度不能超过 64") String displayName,
            String role,
            Boolean enabled
    ) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "不能为空") @Size(min = 6, max = 128, message = "长度需要在 6 到 128 之间") String password
    ) {
    }
}
