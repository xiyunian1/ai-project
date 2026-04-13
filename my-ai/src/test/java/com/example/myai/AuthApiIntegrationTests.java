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

    @MockBean
    private SqlChatService sqlChatService;

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
        given(sqlChatService.query(anyString())).willReturn(
                new SqlChatService.SqlChatResult("db-ok", "SELECT TOP 10 ...", 0, java.util.List.of())
        );
    }

    @Test
    void registerLoginChangePasswordAndStreamAuthShouldWork() throws Exception {
        String username = "tester_" + System.currentTimeMillis();
        String password = "123456";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201));

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
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
    void sqlChatShouldRequireAuthAndReturnResult() throws Exception {
        String username = "sql_user_" + System.currentTimeMillis();
        String password = "123456";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isCreated());

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
                        .param("query", "总库存"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/ai/chat/sql")
                        .param("query", "总库存")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.mode").value("sqlserver"))
                .andExpect(jsonPath("$.answer").value("db-ok"));
    }

    @Test
    void registerDuplicateShouldReturnConflict() throws Exception {
        String username = "dup_" + System.currentTimeMillis();
        String body = objectMapper.writeValueAsString(Map.of(
                "username", username,
                "password", "123456"
        ));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void registerInvalidPayloadShouldReturnBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "ab",
                                "password", "12"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void chatHistoryShouldBePersistedPerAccount() throws Exception {
        String user1 = "history_u1_" + System.currentTimeMillis();
        String user2 = "history_u2_" + System.currentTimeMillis();
        String password = "123456";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", user1,
                                "password", password
                        ))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", user2,
                                "password", password
                        ))))
                .andExpect(status().isCreated());

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

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isCreated());

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
}
