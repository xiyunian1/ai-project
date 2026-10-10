package com.example.myai.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRetrievalUnitTest {

    @Test
    void chunksCarryHeadingPathAndRespectSize() {
        String text = """
                # 效期管理制度
                ## 第一章 总则
                本制度适用于全部仓库。
                ## 第二章 近效期管理
                ### 2.1 定义
                距有效期不足 6 个月的药品为近效期药品。
                ### 2.2 预警
                """ + "每月 5 日前出具近效期报表。".repeat(60);

        List<KnowledgeChunker.Chunk> chunks = new KnowledgeChunker(300, 60).split("效期管理制度.md", text);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> assertThat(c.body().length()).isLessThanOrEqualTo(300 + 60 + 1));
        KnowledgeChunker.Chunk definition = chunks.stream().filter(c -> c.body().contains("不足 6 个月")).findFirst().orElseThrow();
        assertThat(definition.heading()).isEqualTo("《效期管理制度》 > 效期管理制度 > 第二章 近效期管理 > 2.1 定义");
        assertThat(definition.text()).startsWith("《效期管理制度》");
        // 长段落被切成多块，后一块以前一块末尾的句子开头（重叠）
        List<KnowledgeChunker.Chunk> warning = chunks.stream().filter(c -> c.heading().endsWith("2.2 预警")).toList();
        assertThat(warning.size()).isGreaterThan(1);
        assertThat(warning.get(1).body()).startsWith("每月 5 日前出具近效期报表。");
    }

    @Test
    void chineseHeadingsWithoutMarkdownAreRecognised() {
        List<KnowledgeChunker.Chunk> chunks = new KnowledgeChunker().split("盘点管理办法", """
                第一章 总则
                盘点由仓储部主导。
                第二章 差异处理
                差异数量达到 3 个需复核。
                """);
        assertThat(chunks).extracting(KnowledgeChunker.Chunk::heading)
                .containsExactly("《盘点管理办法》 > 第一章 总则", "《盘点管理办法》 > 第二章 差异处理");
    }

    @Test
    void keywordSearchUsesBigramsAndIgnoresQuestionWords() {
        assertThat(KeywordSearch.tokenize("冷库2-8℃")).containsExactly("冷库", "2", "8", "℃");
        List<String> docs = List.of(
                "阴凉库温度不超过 20℃，相对湿度 35%-75%。",
                "冷库温度控制在 2-8℃，每日记录两次。",
                "盘点每月最后一个工作日进行。");
        List<KeywordSearch.Scored> result = KeywordSearch.search("冷库的温度要求是多少？", docs, 3);
        assertThat(result.get(0).index()).isEqualTo(1);
        assertThat(KeywordSearch.search("是多少", docs, 3)).isEmpty();
    }

    @Test
    void reciprocalRankFusionRewardsAgreementBetweenRetrievers() {
        var a = KnowledgeIndexService.Candidate.of("a", "a", Map.of(), 0);
        var b = KnowledgeIndexService.Candidate.of("b", "b", Map.of(), 0);
        var c = KnowledgeIndexService.Candidate.of("c", "c", Map.of(), 0);
        a.vectorRank = 1;
        b.vectorRank = 2;
        var b2 = b.copy();
        b2.keywordRank = 1;
        var c2 = c.copy();
        c2.keywordRank = 2;

        var fused = KnowledgeIndexService.reciprocalRankFusion(List.of(a, b), List.of(b2, c2), 10);

        assertThat(fused).extracting(x -> x.id).containsExactly("b", "a", "c");
        assertThat(fused.get(0).vectorRank).isEqualTo(2);
        assertThat(fused.get(0).keywordRank).isEqualTo(1);
    }
}
