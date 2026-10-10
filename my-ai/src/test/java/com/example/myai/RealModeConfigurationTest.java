package com.example.myai;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RealModeConfigurationTest {

    @Test
    void docsAndAgentInstructionsUseRealModeStartup() throws Exception {
        String root = System.getProperty("user.dir");
        String readme = Files.readString(Path.of(root).getParent().resolve("README.md"));
        String agents = Files.readString(Path.of(root).getParent().getParent().resolve("AGENTS.md"));

        assertFalse(readme.contains("LLM_MOCK_ENABLED"));
        assertFalse(agents.contains("LLM_MOCK_ENABLED"));
    }

    @Test
    void runtimeChatServiceDoesNotReturnLocalMockResponses() throws Exception {
        String root = System.getProperty("user.dir");
        String chatService = Files.readString(Path.of(root)
                .resolve("src/main/java/com/example/myai/ChatService.java"));
        String chatController = Files.readString(Path.of(root)
                .resolve("src/main/java/com/example/myai/ChatController.java"));

        assertFalse(chatService.contains("本地模拟回复"));
        assertFalse(chatService.contains("模拟流式响应"));
        assertFalse(chatService.contains("getMockStreamResponse"));
        assertFalse(chatService.contains("@PostConstruct"));
        assertFalse(chatService.contains("llm.api-key must be configured"));
        org.junit.jupiter.api.Assertions.assertTrue(chatService.contains("chatStream(String query, String userId, SqlAnalysisService.ModelConnectionConfig"));
        org.junit.jupiter.api.Assertions.assertTrue(chatController.contains("@PostMapping(value = \"/chat/stream\""));
        org.junit.jupiter.api.Assertions.assertTrue(chatController.contains("request.modelConfig()"));
    }

    @Test
    void sqlAnalysisServiceDoesNotUseLocalMockSqlGeneration() throws Exception {
        String root = System.getProperty("user.dir");
        String sqlAnalysisService = Files.readString(Path.of(root)
                .resolve("src/main/java/com/example/myai/SqlAnalysisService.java"));

        assertFalse(sqlAnalysisService.contains("buildMockAnalysisSql"));
        assertFalse(sqlAnalysisService.contains("shouldUseLocalFallback"));
        assertFalse(sqlAnalysisService.contains("shouldUseModelConfigDespiteMock"));
        assertFalse(sqlAnalysisService.contains("buildFallbackText"));
        assertFalse(sqlAnalysisService.contains("fallbackAnswer"));
    }

    @Test
    void runtimeDoesNotExposeLegacyRuleBasedSqlChatFallback() throws Exception {
        String root = System.getProperty("user.dir");
        String chatController = Files.readString(Path.of(root)
                .resolve("src/main/java/com/example/myai/ChatController.java"));
        Path sqlChatService = Path.of(root)
                .resolve("src/main/java/com/example/myai/SqlChatService.java");
        Path sqlNl2SqlAgentService = Path.of(root)
                .resolve("src/main/java/com/example/myai/SqlNl2SqlAgentService.java");

        assertFalse(chatController.contains("/chat/sql"));
        assertFalse(chatController.contains("SqlChatService"));
        assertFalse(Files.exists(sqlChatService));
        assertFalse(Files.exists(sqlNl2SqlAgentService));
    }
}
