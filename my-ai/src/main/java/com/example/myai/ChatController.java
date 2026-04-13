package com.example.myai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@Validated
public class ChatController {
    private final ChatService chatService;
    private final SqlChatService sqlChatService;
    private final SqlAnalysisService sqlAnalysisService;

    public ChatController(
            ChatService chatService,
            SqlChatService sqlChatService,
            SqlAnalysisService sqlAnalysisService
    ) {
        this.chatService = chatService;
        this.sqlChatService = sqlChatService;
        this.sqlAnalysisService = sqlAnalysisService;
    }

    @GetMapping("/chat")
    public Mono<String> chat(
            @RequestParam
            @NotBlank(message = "query cannot be empty")
            @Size(max = 2000, message = "query is too long (max 2000)") String query,
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername
    ) {
        return chatService.chat(query, authUsername);
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestParam
            @NotBlank(message = "query cannot be empty")
            @Size(max = 2000, message = "query is too long (max 2000)") String query,
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername
    ) {
        return chatService.chatStream(query, authUsername);
    }

    @GetMapping("/chat/sql")
    public Map<String, Object> chatBySqlServer(
            @RequestParam
            @NotBlank(message = "query cannot be empty")
            @Size(max = 2000, message = "query is too long (max 2000)") String query
    ) {
        SqlChatService.SqlChatResult result = sqlChatService.query(query);

        Map<String, Object> body = new HashMap<>();
        body.put("code", 200);
        body.put("message", "SQL Server query success");
        body.put("mode", "sqlserver");
        body.put("answer", result.answer());
        body.put("sql", result.sqlTemplate());
        body.put("rowCount", result.rowCount());
        body.put("rows", result.rows());
        return body;
    }

    @GetMapping("/chat/analyze")
    public Map<String, Object> analyzeBySqlServer(
            @RequestParam
            @NotBlank(message = "query cannot be empty")
            @Size(max = 2000, message = "query is too long (max 2000)") String query
    ) {
        SqlAnalysisService.AnalysisResult result = sqlAnalysisService.analyze(query);

        Map<String, Object> body = new HashMap<>();
        body.put("code", 200);
        body.put("message", "Analysis success");
        body.put("mode", "analysis");
        body.put("answer", result.answer());
        body.put("sql", result.sqlTemplate());
        body.put("rowCount", result.rowCount());
        body.put("rows", result.rows());
        return body;
    }

    @PostMapping(value = "/chat/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> analyzeBySqlServerStream(
            @RequestBody AnalysisStreamRequest request
    ) {
        if (request == null) {
            return Flux.error(new ApiException(HttpStatus.BAD_REQUEST, "request body is required"));
        }
        return sqlAnalysisService.analyzeStream(request.query(), request.history(), request.dbConfig());
    }

    @PostMapping("/chat/image")
    public Mono<String> chatWithImage(
            @RequestParam("image") MultipartFile image,
            @RequestParam(required = false) @Size(max = 2000, message = "query is too long (max 2000)") String query,
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername
    ) {
        if (image == null || image.isEmpty()) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "image file is required"));
        }

        String finalQuery = query != null && !query.trim().isEmpty() ? query.trim() : "";
        return chatService.chatWithImage(image, finalQuery, authUsername);
    }

    public record AnalysisStreamRequest(
            @NotBlank(message = "query cannot be empty")
            @Size(max = 2000, message = "query is too long (max 2000)")
            String query,
            List<SqlAnalysisService.HistoryTurn> history,
            SqlAnalysisService.DbConnectionConfig dbConfig
    ) {
    }
}
