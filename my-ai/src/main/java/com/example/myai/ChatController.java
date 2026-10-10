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
    private final SqlAnalysisService sqlAnalysisService;

    public ChatController(
            ChatService chatService,
            SqlAnalysisService sqlAnalysisService
    ) {
        this.chatService = chatService;
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

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStreamWithConfig(
            @RequestBody ChatStreamRequest request,
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername
    ) {
        if (request == null) {
            return Flux.error(new ApiException(HttpStatus.BAD_REQUEST, "request body is required"));
        }
        return chatService.chatStream(request.query(), authUsername, request.modelConfig());
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

    /** 返回完整 Agent 轨迹（每步动作、SQL、token、耗时），供 eval/run_sql_eval.py 离线评测。 */
    @PostMapping("/chat/analyze/trace")
    public SqlAnalysisService.TracedAnalysisResult analyzeWithTrace(
            @RequestBody AnalysisStreamRequest request,
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername
    ) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "query cannot be empty");
        }
        return sqlAnalysisService.analyzeWithTrace(request.query(), request.history(), request.modelConfig(),
                request.agentMode(), authUsername);
    }

    /** 资料问答的评测接口：返回回答、命中的分块、上下文长度、token 用量和耗时。knowledgeMode 可选 stuff 或检索模式。 */
    @PostMapping("/chat/knowledge/trace")
    public Map<String, Object> knowledgeAnswerWithTrace(
            @RequestBody KnowledgeTraceRequest request,
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername
    ) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "query cannot be empty");
        }
        return chatService.answerWithTrace(request.query(), authUsername, request.modelConfig(), request.knowledgeMode());
    }

    @PostMapping(value = "/chat/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> analyzeBySqlServerStream(
            @RequestBody AnalysisStreamRequest request,
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername
    ) {
        if (request == null) {
            return Flux.error(new ApiException(HttpStatus.BAD_REQUEST, "request body is required"));
        }
        return sqlAnalysisService.analyzeStream(request.query(), request.history(), request.dbConfig(), request.modelConfig(), authUsername);
    }

    @PostMapping("/models")
    public Map<String, Object> listModels(@RequestBody(required = false) ModelListRequest request) {
        String baseUrl = request == null ? null : request.baseUrl();
        String apiKey = request == null ? null : request.apiKey();
        List<String> models = chatService.listModels(baseUrl, apiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("code", 200);
        body.put("message", "Models loaded");
        body.put("models", models);
        return body;
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
            SqlAnalysisService.DbConnectionConfig dbConfig,
            SqlAnalysisService.ModelConnectionConfig modelConfig,
            /* 可选：json 或 tools，仅评测接口使用；为空时用 analysis.agent.mode 配置 */
            String agentMode
    ) {
    }

    public record KnowledgeTraceRequest(
            String query,
            SqlAnalysisService.ModelConnectionConfig modelConfig,
            String knowledgeMode
    ) {
    }

    public record ChatStreamRequest(
            @NotBlank(message = "query cannot be empty")
            @Size(max = 2000, message = "query is too long (max 2000)")
            String query,
            SqlAnalysisService.ModelConnectionConfig modelConfig
    ) {
    }

    public record ModelListRequest(
            String baseUrl,
            String apiKey
    ) {
    }
}
