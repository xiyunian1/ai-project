package com.example.myai;

import com.example.myai.rag.KnowledgeIndexService;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings/knowledge-documents")
public class KnowledgeDocumentController {
    private static final int MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_KNOWLEDGE_CHARS = 120_000;

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final DocumentTextExtractor documentTextExtractor;
    private final KnowledgeIndexService knowledgeIndexService;

    public KnowledgeDocumentController(
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            DocumentTextExtractor documentTextExtractor,
            KnowledgeIndexService knowledgeIndexService
    ) {
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.documentTextExtractor = documentTextExtractor;
        this.knowledgeIndexService = knowledgeIndexService;
    }

    @GetMapping
    public List<KnowledgeDocumentSummary> list(
            @RequestAttribute(AuthInterceptor.ATTR_USERNAME) String username
    ) {
        return knowledgeDocumentRepository.findByUsernameOrderByUpdatedAtDesc(username).stream()
                .map(KnowledgeDocumentSummary::from)
                .toList();
    }

    @PostMapping
    public KnowledgeDocumentSummary upload(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute(AuthInterceptor.ATTR_USERNAME) String username
    ) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please upload a file");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File size cannot exceed 20MB");
        }

        String fileName = documentTextExtractor.sanitizeFileName(file.getOriginalFilename());
        String text = documentTextExtractor.extractText(fileName, readBytes(file), file.getContentType(), MAX_KNOWLEDGE_CHARS);

        KnowledgeDocument document = new KnowledgeDocument();
        document.setUsername(username);
        document.setFileName(fileName);
        document.setContentType(file.getContentType());
        document.setTextContent(text);
        document.setTextLength(text.length());

        KnowledgeDocument saved = knowledgeDocumentRepository.save(document);
        knowledgeIndexService.indexAsync(saved);
        return KnowledgeDocumentSummary.from(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> delete(
            @PathVariable Long id,
            @RequestAttribute(AuthInterceptor.ATTR_USERNAME) String username
    ) {
        KnowledgeDocument document = knowledgeDocumentRepository.findByUsernameAndId(username, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Knowledge document not found"));
        knowledgeDocumentRepository.delete(document);
        knowledgeIndexService.removeAsync(id);
        return Map.of("deleted", true, "id", id);
    }

    /** 检索测试：返回当前账号资料中与问题最相关的分块，用于调试和离线评测。 */
    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam("q") String query,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "k", required = false) Integer topK,
            @RequestAttribute(AuthInterceptor.ATTR_USERNAME) String username
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", knowledgeIndexService.status());
        String effectiveMode = mode == null || mode.isBlank() ? knowledgeIndexService.defaultMode() : mode;
        body.put("mode", effectiveMode);
        body.put("chunks", knowledgeIndexService.search(username, query, effectiveMode,
                topK == null ? knowledgeIndexService.defaultTopK() : topK));
        return body;
    }

    /** 等待索引任务完成并与数据库对账，返回本次重建/清理的数量。 */
    @PostMapping("/reindex")
    public Map<String, Object> reindex() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(knowledgeIndexService.awaitIndexing());
        body.put("status", knowledgeIndexService.status());
        return body;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read uploaded file");
        }
    }

    public record KnowledgeDocumentSummary(
            Long id,
            String fileName,
            String contentType,
            int textLength,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        static KnowledgeDocumentSummary from(KnowledgeDocument document) {
            return new KnowledgeDocumentSummary(
                    document.getId(),
                    document.getFileName(),
                    document.getContentType(),
                    document.getTextLength(),
                    document.getCreatedAt(),
                    document.getUpdatedAt()
            );
        }
    }
}
