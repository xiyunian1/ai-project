package com.example.myai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ChatHistoryService {
    private static final int MAX_HISTORY_JSON_LENGTH = 2_000_000;

    private final UserChatHistoryRepository repository;
    private final ObjectMapper objectMapper;

    public ChatHistoryService(UserChatHistoryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public ObjectNode getSessionsByUsername(String username) {
        return repository.findByUsername(username)
                .map(UserChatHistory::getSessionsJson)
                .map(this::parseSessionsOrEmpty)
                .orElseGet(objectMapper::createObjectNode);
    }

    public void saveSessionsByUsername(String username, JsonNode sessions) {
        if (sessions == null || !sessions.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sessions 必须是对象");
        }

        String sessionsJson = sessions.toString();
        if (sessionsJson.length() > MAX_HISTORY_JSON_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "聊天记录过大，请先删除部分会话");
        }

        UserChatHistory history = repository.findByUsername(username).orElseGet(UserChatHistory::new);
        history.setUsername(username);
        history.setSessionsJson(sessionsJson);
        repository.save(history);
    }

    private ObjectNode parseSessionsOrEmpty(String sessionsJson) {
        if (sessionsJson == null || sessionsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(sessionsJson);
            return node != null && node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
