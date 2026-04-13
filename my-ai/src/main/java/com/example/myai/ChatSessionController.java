package com.example.myai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/chat/sessions")
public class ChatSessionController {
    private final ChatSessionService chatSessionService;

    public ChatSessionController(ChatSessionService chatSessionService) {
        this.chatSessionService = chatSessionService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listSessions(
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        String username = requireUsername(authUsername);
        Map<String, Object> result = new HashMap<>(chatSessionService.listSessions(username, page, size));
        result.put("code", 200);
        result.put("message", "查询成功");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername,
            @PathVariable String sessionId
    ) {
        String username = requireUsername(authUsername);
        Map<String, Object> body = okBody("查询成功");
        body.put("session", chatSessionService.getSession(username, normalizeSessionId(sessionId)));
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> upsertSession(
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername,
            @PathVariable String sessionId,
            @RequestBody JsonNode sessionBody
    ) {
        String username = requireUsername(authUsername);
        chatSessionService.upsertSession(username, normalizeSessionId(sessionId), sessionBody);
        return ResponseEntity.ok(okBody("保存成功"));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteSession(
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername,
            @PathVariable String sessionId
    ) {
        String username = requireUsername(authUsername);
        chatSessionService.deleteSession(username, normalizeSessionId(sessionId));
        return ResponseEntity.ok(okBody("删除成功"));
    }

    private String requireUsername(String authUsername) {
        if (authUsername == null || authUsername.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        return authUsername.trim();
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sessionId 不能为空");
        }
        String normalized = sessionId.trim();
        if (normalized.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sessionId 不能为空");
        }
        if (normalized.length() > 96) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sessionId 过长");
        }
        return normalized;
    }

    private Map<String, Object> okBody(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", 200);
        body.put("message", message);
        return body;
    }
}
