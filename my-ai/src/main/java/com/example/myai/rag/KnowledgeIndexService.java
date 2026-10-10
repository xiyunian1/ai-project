package com.example.myai.rag;

import com.example.myai.KnowledgeDocument;
import com.example.myai.KnowledgeDocumentRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 资料问答的检索服务：分块 → bge-m3 向量化写入 pgvector → 向量召回 + BM25 关键词召回 → RRF 融合 → rerank。
 *
 * <p>向量库是可选组件：未配置 embedding Key、向量库口令，或者连接失败时 {@link #isReady()} 为 false，
 * 调用方回退到原来的"整篇文档拼进上下文"方式，系统其余功能不受影响。
 */
@Service
public class KnowledgeIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);
    private static final String TABLE = "knowledge_chunks";
    private static final int RRF_K = 60;

    public static final Set<String> MODES = Set.of("vector", "keyword", "hybrid", "vector_rerank", "hybrid_rerank");

    public record RetrievedChunk(
            String id,
            String docId,
            String fileName,
            String heading,
            int chunkIndex,
            String content,
            double score,
            Integer vectorRank,
            Integer keywordRank
    ) {
    }

    private record Infra(HikariDataSource dataSource, JdbcTemplate jdbc, PgVectorStore store, RerankClient reranker) {
    }

    @Value("${rag.enabled:true}")
    private boolean enabled;
    @Value("${rag.mode:hybrid_rerank}")
    private String defaultMode;
    @Value("${rag.top-k:5}")
    private int defaultTopK;
    @Value("${rag.candidate-k:20}")
    private int candidateK;
    @Value("${rag.datasource.url:jdbc:postgresql://localhost:5432/wms_rag}")
    private String dbUrl;
    @Value("${rag.datasource.username:wms_rag}")
    private String dbUsername;
    @Value("${rag.datasource.password:}")
    private String dbPassword;
    @Value("${rag.embedding.api-key:}")
    private String embeddingApiKey;
    @Value("${rag.embedding.base-url:https://api.siliconflow.cn}")
    private String embeddingBaseUrl;
    @Value("${rag.embedding.model:BAAI/bge-m3}")
    private String embeddingModel;
    @Value("${rag.embedding.dimensions:1024}")
    private int dimensions;
    @Value("${rag.rerank.model:BAAI/bge-reranker-v2-m3}")
    private String rerankModel;

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunker chunker = new KnowledgeChunker();
    private final ExecutorService indexExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "knowledge-indexer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Infra infra;
    private volatile String unavailableReason = "未初始化";

    public KnowledgeIndexService(KnowledgeDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    // ------------------------------------------------------------------ 生命周期

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        indexExecutor.submit(() -> {
            if (initialize()) {
                syncAll();
            }
        });
    }

    synchronized boolean initialize() {
        if (infra != null) {
            return true;
        }
        if (!enabled) {
            unavailableReason = "rag.enabled=false";
            return false;
        }
        if (isBlank(embeddingApiKey) || isBlank(dbPassword)) {
            unavailableReason = "未配置 EMBEDDING_API_KEY 或 RAG_DB_PASSWORD";
            log.info("Knowledge RAG disabled: {}", unavailableReason);
            return false;
        }
        HikariDataSource dataSource = null;
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(dbUrl);
            config.setUsername(dbUsername);
            config.setPassword(dbPassword);
            config.setMaximumPoolSize(4);
            config.setPoolName("knowledge-pgvector");
            config.setInitializationFailTimeout(5000);
            dataSource = new HikariDataSource(config);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(10));
            requestFactory.setReadTimeout(Duration.ofSeconds(60));
            OpenAiApi embeddingApi = OpenAiApi.builder()
                    .baseUrl(embeddingBaseUrl.replaceAll("/+$", ""))
                    .apiKey(embeddingApiKey)
                    .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                    .build();
            OpenAiEmbeddingModel embedding = new OpenAiEmbeddingModel(
                    embeddingApi,
                    MetadataMode.NONE,
                    OpenAiEmbeddingOptions.builder().model(embeddingModel).build(),
                    RetryTemplate.builder().maxAttempts(3).exponentialBackoff(1000, 2, 8000).build());

            PgVectorStore store = PgVectorStore.builder(jdbc, embedding)
                    .dimensions(dimensions)
                    .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                    .indexType(PgVectorStore.PgIndexType.HNSW)
                    .schemaName("public")
                    .vectorTableName(TABLE)
                    .initializeSchema(true)
                    .maxDocumentBatchSize(32)
                    .build();
            store.afterPropertiesSet();
            jdbc.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_username_idx ON public." + TABLE + " ((metadata->>'username'))");

            RerankClient reranker = new RerankClient(embeddingBaseUrl, embeddingApiKey, rerankModel, Duration.ofSeconds(30));
            infra = new Infra(dataSource, jdbc, store, reranker);
            unavailableReason = "";
            log.info("Knowledge RAG ready: {} / {} / {}", embeddingModel, rerankModel, dbUrl);
            return true;
        } catch (Exception ex) {
            if (dataSource != null) {
                dataSource.close();
            }
            unavailableReason = "向量库初始化失败：" + ex.getMessage();
            log.warn("Knowledge RAG unavailable, falling back to full-document context: {}", ex.getMessage());
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        indexExecutor.shutdownNow();
        Infra current = infra;
        if (current != null) {
            current.dataSource().close();
        }
    }

    public boolean isReady() {
        return infra != null;
    }

    public String status() {
        return isReady() ? "ready" : unavailableReason;
    }

    public String defaultMode() {
        return MODES.contains(defaultMode) ? defaultMode : "hybrid_rerank";
    }

    public int defaultTopK() {
        return Math.max(1, Math.min(defaultTopK, 20));
    }

    // ------------------------------------------------------------------ 写入

    /** 上传后异步建索引，避免上传接口等待 embedding 请求。 */
    public void indexAsync(KnowledgeDocument document) {
        if (isReady()) {
            indexExecutor.submit(() -> indexDocument(document));
        }
    }

    public void removeAsync(Long documentId) {
        if (isReady()) {
            indexExecutor.submit(() -> removeDocument(String.valueOf(documentId)));
        }
    }

    public int indexDocument(KnowledgeDocument document) {
        Infra current = infra;
        if (current == null) {
            return 0;
        }
        String docId = String.valueOf(document.getId());
        String content = document.getTextContent() == null ? "" : document.getTextContent();
        String hash = sha256(document.getFileName() + "\n" + content);
        List<KnowledgeChunker.Chunk> chunks = chunker.split(document.getFileName(), content);
        List<Document> documents = new ArrayList<>(chunks.size());
        for (KnowledgeChunker.Chunk chunk : chunks) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("username", document.getUsername());
            metadata.put("docId", docId);
            metadata.put("fileName", document.getFileName());
            metadata.put("heading", chunk.heading());
            metadata.put("chunkIndex", chunk.index());
            metadata.put("contentHash", hash);
            String id = UUID.nameUUIDFromBytes((docId + ":" + hash + ":" + chunk.index()).getBytes(StandardCharsets.UTF_8)).toString();
            documents.add(Document.builder().id(id).text(chunk.text()).metadata(metadata).build());
        }
        removeDocument(docId);
        if (!documents.isEmpty()) {
            current.store().add(documents);
        }
        log.info("Indexed knowledge document {} ({}) into {} chunks", docId, document.getFileName(), documents.size());
        return documents.size();
    }

    public void removeDocument(String docId) {
        Infra current = infra;
        if (current != null) {
            current.jdbc().update("DELETE FROM public." + TABLE + " WHERE metadata->>'docId' = ?", docId);
        }
    }

    /** 启动时对账：内容有变化的文档重建索引，已删除文档的分块清理掉。 */
    public Map<String, Object> syncAll() {
        Infra current = infra;
        Map<String, Object> summary = new LinkedHashMap<>();
        if (current == null) {
            summary.put("status", unavailableReason);
            return summary;
        }
        Map<String, String> indexed = new HashMap<>();
        current.jdbc().query("SELECT metadata->>'docId' AS doc_id, MAX(metadata->>'contentHash') AS hash FROM public." + TABLE
                + " GROUP BY metadata->>'docId'", rs -> {
            indexed.put(rs.getString("doc_id"), rs.getString("hash"));
        });
        int reindexed = 0;
        int chunks = 0;
        Set<String> alive = new java.util.HashSet<>();
        for (KnowledgeDocument document : documentRepository.findAll()) {
            String docId = String.valueOf(document.getId());
            alive.add(docId);
            String hash = sha256(document.getFileName() + "\n" + (document.getTextContent() == null ? "" : document.getTextContent()));
            if (!hash.equals(indexed.get(docId))) {
                try {
                    chunks += indexDocument(document);
                    reindexed++;
                } catch (Exception ex) {
                    log.warn("Failed to index knowledge document {}: {}", docId, ex.getMessage());
                }
            }
        }
        int removed = 0;
        for (String docId : indexed.keySet()) {
            if (!alive.contains(docId)) {
                removeDocument(docId);
                removed++;
            }
        }
        summary.put("reindexedDocuments", reindexed);
        summary.put("newChunks", chunks);
        summary.put("removedDocuments", removed);
        summary.put("totalDocuments", alive.size());
        summary.put("totalChunks", current.jdbc().queryForObject("SELECT COUNT(*) FROM public." + TABLE, Long.class));
        log.info("Knowledge index sync: {}", summary);
        return summary;
    }

    /** 等待已提交的索引任务执行完（评测脚本上传资料后调用）。 */
    public Map<String, Object> awaitIndexing() throws Exception {
        return indexExecutor.submit(this::syncAll).get();
    }

    // ------------------------------------------------------------------ 检索

    public List<RetrievedChunk> search(String username, String query, String mode, int topK) {
        Infra current = infra;
        if (current == null || isBlank(username) || isBlank(query)) {
            return List.of();
        }
        String effectiveMode = MODES.contains(mode) ? mode : defaultMode();
        int k = Math.max(1, Math.min(topK, 20));
        int candidates = Math.max(k, Math.min(candidateK, 50));

        List<Candidate> vector = effectiveMode.equals("keyword") ? List.of() : vectorSearch(current, username, query, candidates);
        List<Candidate> keyword = effectiveMode.startsWith("vector") ? List.of() : keywordSearch(current, username, query, candidates);

        List<Candidate> merged;
        if (effectiveMode.startsWith("hybrid")) {
            merged = reciprocalRankFusion(vector, keyword, candidates);
        } else {
            merged = effectiveMode.equals("keyword") ? keyword : vector;
        }
        if (effectiveMode.endsWith("_rerank") && !merged.isEmpty()) {
            List<RerankClient.Ranked> ranked = current.reranker().rerank(query, merged.stream().map(c -> c.text).toList(), k);
            List<Candidate> reranked = new ArrayList<>();
            for (RerankClient.Ranked r : ranked) {
                Candidate c = merged.get(r.index());
                reranked.add(c.withScore(r.score()));
            }
            merged = reranked;
        }
        return merged.stream().limit(k).map(Candidate::toChunk).toList();
    }

    private List<Candidate> vectorSearch(Infra current, String username, String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(new FilterExpressionBuilder().eq("username", username).build())
                .build();
        List<Document> documents = current.store().similaritySearch(request);
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Document d = documents.get(i);
            Candidate c = Candidate.of(d.getId(), d.getText(), d.getMetadata(), d.getScore() == null ? 0 : d.getScore());
            c.vectorRank = i + 1;
            out.add(c);
        }
        return out;
    }

    private List<Candidate> keywordSearch(Infra current, String username, String query, int topK) {
        List<Candidate> all = current.jdbc().query(
                "SELECT id::text AS id, content, metadata::text AS metadata FROM public." + TABLE + " WHERE metadata->>'username' = ?",
                (rs, rowNum) -> Candidate.of(rs.getString("id"), rs.getString("content"), parseMetadata(rs.getString("metadata")), 0),
                username);
        List<KeywordSearch.Scored> scored = KeywordSearch.search(query, all.stream().map(c -> c.text).toList(), topK);
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            Candidate c = all.get(scored.get(i).index()).withScore(scored.get(i).score());
            c.keywordRank = i + 1;
            out.add(c);
        }
        return out;
    }

    /** RRF：score = Σ 1 / (60 + rank)，对两路召回的名次做融合，不需要把两种分数归一到同一量纲。 */
    static List<Candidate> reciprocalRankFusion(List<Candidate> vector, List<Candidate> keyword, int limit) {
        Map<String, Candidate> byId = new LinkedHashMap<>();
        for (Candidate c : vector) {
            Candidate merged = byId.computeIfAbsent(c.id, id -> c.copy());
            merged.vectorRank = c.vectorRank;
            merged.score += 1.0 / (RRF_K + c.vectorRank);
        }
        for (Candidate c : keyword) {
            Candidate merged = byId.computeIfAbsent(c.id, id -> c.copy());
            merged.keywordRank = c.keywordRank;
            merged.score += 1.0 / (RRF_K + c.keywordRank);
        }
        return byId.values().stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(limit)
                .collect(Collectors.toList());
    }

    static final class Candidate {
        final String id;
        final String text;
        final Map<String, Object> metadata;
        double score;
        Integer vectorRank;
        Integer keywordRank;

        private Candidate(String id, String text, Map<String, Object> metadata, double score) {
            this.id = id;
            this.text = text == null ? "" : text;
            this.metadata = metadata == null ? Map.of() : metadata;
            this.score = score;
        }

        static Candidate of(String id, String text, Map<String, Object> metadata, double score) {
            return new Candidate(id, text, metadata, score);
        }

        Candidate copy() {
            Candidate c = new Candidate(id, text, metadata, 0);
            c.vectorRank = vectorRank;
            c.keywordRank = keywordRank;
            return c;
        }

        Candidate withScore(double newScore) {
            Candidate c = copy();
            c.score = newScore;
            return c;
        }

        RetrievedChunk toChunk() {
            Object index = metadata.get("chunkIndex");
            return new RetrievedChunk(
                    id,
                    String.valueOf(metadata.getOrDefault("docId", "")),
                    String.valueOf(metadata.getOrDefault("fileName", "")),
                    String.valueOf(metadata.getOrDefault("heading", "")),
                    index instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(index == null ? "0" : index)),
                    text,
                    score,
                    vectorRank,
                    keywordRank);
        }
    }

    // ------------------------------------------------------------------ 工具方法

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMetadata(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
