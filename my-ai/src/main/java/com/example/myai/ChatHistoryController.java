package com.example.myai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatHistoryController {
    private final ChatHistoryService chatHistoryService;

    public ChatHistoryController(ChatHistoryService chatHistoryService) {
        this.chatHistoryService = chatHistoryService;
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername
    ) {
        ensureAuthenticated(authUsername);

        ObjectNode sessions = chatHistoryService.getSessionsByUsername(authUsername);
        Map<String, Object> body = okBody("查询成功");
        body.put("sessions", sessions);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/history")
    public ResponseEntity<Map<String, Object>> saveHistory(
            @RequestBody JsonNode body,
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername
    ) {
        ensureAuthenticated(authUsername);

        JsonNode sessions = body != null && body.has("sessions") ? body.get("sessions") : body;
        chatHistoryService.saveSessionsByUsername(authUsername, sessions);
        return ResponseEntity.ok(okBody("保存成功"));
    }

    private void ensureAuthenticated(String authUsername) {
        if (authUsername == null || authUsername.isBlank()) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }

    private Map<String, Object> okBody(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", message);
        return response;
    }
}
