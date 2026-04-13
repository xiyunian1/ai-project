package com.example.myai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ChatSessionService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_MESSAGES_JSON_LENGTH = 1_500_000;

    private final UserChatSessionRepository repository;
    private final ObjectMapper objectMapper;

    public ChatSessionService(UserChatSessionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> listSessions(String username, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<UserChatSession> result = repository.findByUsernameOrderByUpdatedAtEpochMsDesc(
                username,
                PageRequest.of(safePage, safeSize)
        );

        List<Map<String, Object>> items = result.getContent().stream().map(session -> {
            Map<String, Object> item = new java.util.HashMap<>();
            item.put("sessionId", session.getSessionId());
            item.put("name", session.getSessionName());
            item.put("messageCount", session.getMessageCount() == null ? 0 : session.getMessageCount());
            item.put("createdAt", session.getCreatedAtEpochMs() == null ? 0L : session.getCreatedAtEpochMs());
            item.put("updatedAt", session.getUpdatedAtEpochMs() == null ? 0L : session.getUpdatedAtEpochMs());
            return item;
        }).toList();

        return Map.of(
                "page", safePage,
                "size", safeSize,
                "total", result.getTotalElements(),
                "totalPages", result.getTotalPages(),
                "items", items
        );
    }

    public Map<String, Object> getSession(String username, String sessionId) {
        UserChatSession session = repository.findByUsernameAndSessionId(username, sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "会话不存在"));

        ArrayNode messages = parseMessages(session.getMessagesJson());
        return Map.of(
                "sessionId", session.getSessionId(),
                "name", session.getSessionName(),
                "messages", messages,
                "createdAt", session.getCreatedAtEpochMs() == null ? 0L : session.getCreatedAtEpochMs(),
                "updatedAt", session.getUpdatedAtEpochMs() == null ? 0L : session.getUpdatedAtEpochMs()
        );
    }

    public void upsertSession(String username, String sessionId, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "会话内容格式错误");
        }

        long now = System.currentTimeMillis();
        Optional<UserChatSession> existingOpt = repository.findByUsernameAndSessionId(username, sessionId);
        UserChatSession session = existingOpt.orElseGet(UserChatSession::new);

        String name = body.path("name").asText("新对话").trim();
        if (name.isEmpty()) {
            name = "新对话";
        }
        if (name.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "会话标题过长");
        }

        JsonNode messagesNode = body.get("messages");
        ArrayNode messages = objectMapper.createArrayNode();
        if (messagesNode != null && messagesNode.isArray()) {
            messages = (ArrayNode) messagesNode;
        }

        String messagesJson;
        try {
            messagesJson = objectMapper.writeValueAsString(messages);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "消息格式错误");
        }
        if (messagesJson.length() > MAX_MESSAGES_JSON_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "会话消息过大，请拆分会话");
        }

        long createdAt = existingOpt.map(UserChatSession::getCreatedAtEpochMs).orElse(now);
        if (body.has("createdAt") && body.get("createdAt").canConvertToLong()) {
            createdAt = body.get("createdAt").asLong();
        }
        long updatedAt = now;
        if (body.has("updatedAt") && body.get("updatedAt").canConvertToLong()) {
            updatedAt = body.get("updatedAt").asLong();
        }
        if (updatedAt < createdAt) {
            updatedAt = createdAt;
        }

        session.setUsername(username);
        session.setSessionId(sessionId);
        session.setSessionName(name);
        session.setMessagesJson(messagesJson);
        session.setMessageCount(messages.size());
        session.setCreatedAtEpochMs(createdAt);
        session.setUpdatedAtEpochMs(updatedAt);
        repository.save(session);
    }

    @Transactional
    public void deleteSession(String username, String sessionId) {
        repository.deleteByUsernameAndSessionId(username, sessionId);
    }

    private ArrayNode parseMessages(String messagesJson) {
        if (messagesJson == null || messagesJson.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(messagesJson);
            if (parsed != null && parsed.isArray()) {
                return (ArrayNode) parsed;
            }
        } catch (Exception ignored) {
        }
        return objectMapper.createArrayNode();
    }
}
