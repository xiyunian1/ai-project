package com.example.myai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthApiIntegrationTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ChatService chatService;

    @BeforeEach
    void setupChatServiceMock() {
        given(chatService.chat(anyString(), anyString())).willReturn(Mono.just("ok"));
        given(chatService.chatStream(anyString(), anyString())).willReturn(
                Flux.just(
                        "data:{\"event\":\"answer\",\"text\":\"ok\"}",
                        "data:[DONE]"
                )
        );
        given(chatService.chatWithImage(any(), anyString(), anyString())).willReturn(Mono.just("ok"));
    }

    @Test
    void registerLoginChangePasswordAndStreamAuthShouldWork() throws Exception {
        String username = "tester_" + System.currentTimeMillis();
        String password = "123456";

        createUserAsAdmin(username, password);

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.role").value("USER"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode loginJson = objectMapper.readTree(loginBody);
        String token = loginJson.path("token").asText();
        assertThat(token).isNotBlank();

        mockMvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", password,
                                "newPassword", "654321"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", password,
                                "newPassword", "654321"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/ai/chat/stream")
                        .param("query", "hello")
                        .param("token", token))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/ai/chat/stream")
                        .param("query", "hello")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data:")));
    }

    @Test
    void userManagementRequiresAdminAndSupportsAccountLifecycle() throws Exception {
        String userToken = registerAndLogin("normal_" + System.currentTimeMillis(), "123456");

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        String adminToken = login("admin", "123456");
        String managedUsername = "managed_" + System.currentTimeMillis();

        String createBody = mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", managedUsername,
                                "password", "123456",
                                "displayName", "Managed User",
                                "role", "USER"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(managedUsername))
                .andExpect(jsonPath("$.displayName").value("Managed User"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long managedId = objectMapper.readTree(createBody).path("id").asLong();

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == '" + managedUsername + "')]").exists());

        mockMvc.perform(patch("/api/admin/users/{id}", managedId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", "Updated User",
                                "role", "ADMIN",
                                "enabled", false
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Updated User"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", managedUsername,
                                "password", "123456"
                        ))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/admin/users/{id}", managedId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(post("/api/admin/users/{id}/reset-password", managedId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "abcdef"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", managedUsername,
                                "password", "abcdef"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mockMvc.perform(delete("/api/admin/users/{id}", managedId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void adminCannotDisableOrDeleteSelf() throws Exception {
        String adminToken = login("admin", "123456");
        JsonNode users = objectMapper.readTree(
                mockMvc.perform(get("/api/admin/users")
                                .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
        );
        long adminId = 0L;
        for (JsonNode user : users) {
            if ("admin".equals(user.path("username").asText())) {
                adminId = user.path("id").asLong();
                break;
            }
        }
        assertThat(adminId).isPositive();

        mockMvc.perform(patch("/api/admin/users/{id}", adminId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/admin/users/{id}", adminId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void legacySqlChatEndpointShouldNotExist() throws Exception {
        String username = "sql_user_" + System.currentTimeMillis();
        String password = "123456";

        createUserAsAdmin(username, password);

        String token = objectMapper.readTree(
                        mockMvc.perform(post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(Map.of(
                                                "username", username,
                                                "password", password
                                        ))))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString()
                )
                .path("token").asText();

        mockMvc.perform(get("/api/ai/chat/sql")
                        .param("query", "stock total"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/ai/chat/sql")
                        .param("query", "stock total")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void authorizationSchemeShouldBeCaseInsensitive() throws Exception {
        String username = "bearer_user_" + System.currentTimeMillis();
        String password = "123456";

        createUserAsAdmin(username, password);

        String token = objectMapper.readTree(
                        mockMvc.perform(post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(Map.of(
                                                "username", username,
                                                "password", password
                                        ))))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString()
                )
                .path("token").asText();

        mockMvc.perform(get("/api/ai/chat/sql")
                        .param("query", "stock total")
                        .header("Authorization", "bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCreateDuplicateShouldReturnConflict() throws Exception {
        String username = "dup_" + System.currentTimeMillis();
        String body = objectMapper.writeValueAsString(Map.of(
                "username", username,
                "password", "123456",
                "role", "USER"
        ));
        String adminToken = login("admin", "123456");

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void publicRegistrationShouldBeDisabledAfterAdminBootstrap() throws Exception {
        String username = "closed_register_" + System.currentTimeMillis();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "123456"
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void publicRegistrationInvalidPayloadStillReturnsForbidden() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "ab",
                                "password", "12"
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void chatHistoryShouldBePersistedPerAccount() throws Exception {
        String user1 = "history_u1_" + System.currentTimeMillis();
        String user2 = "history_u2_" + System.currentTimeMillis();
        String password = "123456";

        createUserAsAdmin(user1, password);
        createUserAsAdmin(user2, password);

        String token1 = objectMapper.readTree(
                        mockMvc.perform(post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(Map.of(
                                                "username", user1,
                                                "password", password
                                        ))))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString()
                )
                .path("token").asText();

        String token2 = objectMapper.readTree(
                        mockMvc.perform(post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(Map.of(
                                                "username", user2,
                                                "password", password
                                        ))))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString()
                )
                .path("token").asText();

        Map<String, Object> sessionsPayload = Map.of(
                "chat_1", Map.of(
                        "name", "测试会话",
                        "messages", java.util.List.of(
                                Map.of("role", "user", "content", "hello"),
                                Map.of("role", "assistant", "content", "world")
                        ),
                        "createdAt", 1,
                        "updatedAt", 2
                )
        );

        mockMvc.perform(post("/api/chat/history")
                        .header("Authorization", "Bearer " + token1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sessions", sessionsPayload))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/chat/history")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.chat_1.name").value("测试会话"))
                .andExpect(jsonPath("$.sessions.chat_1.messages[0].content").value("hello"));

        mockMvc.perform(get("/api/chat/history")
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.chat_1").doesNotExist());
    }

    @Test
    void pagedSessionApisShouldWorkAndIsolateByAccount() throws Exception {
        String username = "page_user_" + System.currentTimeMillis();
        String password = "123456";

        createUserAsAdmin(username, password);

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = objectMapper.readTree(loginBody).path("token").asText();

        Map<String, Object> sessionBody = Map.of(
                "name", "分页会话",
                "messages", java.util.List.of(
                        Map.of("role", "user", "content", "hello"),
                        Map.of("role", "assistant", "content", "world")
                ),
                "createdAt", 1000,
                "updatedAt", 2000
        );

        mockMvc.perform(put("/api/chat/sessions/s_page_1")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sessionBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/chat/sessions")
                        .param("page", "0")
                        .param("size", "10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.items[0].sessionId").value("s_page_1"))
                .andExpect(jsonPath("$.items[0].messageCount").value(2));

        mockMvc.perform(get("/api/chat/sessions/s_page_1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.name").value("分页会话"))
                .andExpect(jsonPath("$.session.messages[0].content").value("hello"));
    }

    @Test
    void knowledgeDocumentApisShouldWorkPerAccount() throws Exception {
        String username = "knowledge_user_" + System.currentTimeMillis();
        String password = "123456";

        createUserAsAdmin(username, password);

        String token = objectMapper.readTree(
                        mockMvc.perform(post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(Map.of(
                                                "username", username,
                                                "password", password
                                        ))))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString()
                )
                .path("token").asText();

        mockMvc.perform(get("/api/settings/knowledge-documents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        String uploadBody = mockMvc.perform(multipart("/api/settings/knowledge-documents")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file",
                                "knowledge.txt",
                                "text/plain",
                                "knowledge content".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        ))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("knowledge.txt"))
                .andExpect(jsonPath("$.textLength").value(17))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long documentId = objectMapper.readTree(uploadBody).path("id").asLong();

        mockMvc.perform(get("/api/settings/knowledge-documents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(documentId))
                .andExpect(jsonPath("$[0].fileName").value("knowledge.txt"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/settings/knowledge-documents/{id}", documentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }

    private String registerAndLogin(String username, String password) throws Exception {
        createUserAsAdmin(username, password);
        return login(username, password);
    }

    private void createUserAsAdmin(String username, String password) throws Exception {
        String adminToken = login("admin", "123456");
        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password,
                                "displayName", username,
                                "role", "USER"
                        ))))
                .andExpect(status().isCreated());
    }

    private String login(String username, String password) throws Exception {
        return objectMapper.readTree(
                        mockMvc.perform(post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(Map.of(
                                                "username", username,
                                                "password", password
                                        ))))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString()
                )
                .path("token").asText();
    }
}
