package com.example.myai.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 关键词检索：中文按相邻二字切分（bigram），英文和数字按整词，用 BM25 打分。
 * 不依赖分词器，对"2-8℃""GSP""30 分钟"这类精确词比向量检索更敏感，用来和向量检索互补。
 */
public final class KeywordSearch {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    /** 疑问句里常见、但对定位资料没有帮助的二字组合。 */
    private static final Set<String> STOP_BIGRAMS = Set.of(
            "多少", "什么", "哪些", "哪个", "怎么", "如何", "是否", "需要", "可以", "要求", "规定", "公司",
            "的是", "是多", "少天", "少小", "有哪", "是什", "么时", "时候", "请问", "一下", "我们", "按照");

    private KeywordSearch() {
    }

    public record Scored(int index, double score) {
    }

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder cjk = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                flushWord(word, tokens);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c) || c == '.' && word.length() > 0) {
                flushCjk(cjk, tokens);
                word.append(c);
            } else {
                flushCjk(cjk, tokens);
                flushWord(word, tokens);
                if (c == '℃' || c == '%') {
                    tokens.add(String.valueOf(c));
                }
            }
        }
        flushCjk(cjk, tokens);
        flushWord(word, tokens);
        return tokens;
    }

    /** 对 documents 按 query 打 BM25 分，返回得分大于 0 的前 topK 个（按分数降序）。 */
    public static List<Scored> search(String query, List<String> documents, int topK) {
        Set<String> queryTerms = new HashSet<>(tokenize(query));
        queryTerms.removeAll(STOP_BIGRAMS);
        if (queryTerms.isEmpty() || documents.isEmpty()) {
            return List.of();
        }

        List<Map<String, Integer>> termFrequencies = new ArrayList<>(documents.size());
        Map<String, Integer> documentFrequency = new HashMap<>();
        long totalLength = 0;
        for (String document : documents) {
            List<String> tokens = tokenize(document);
            totalLength += tokens.size();
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                if (queryTerms.contains(token)) {
                    tf.merge(token, 1, Integer::sum);
                }
            }
            tf.put("\u0000len", tokens.size());
            for (String term : tf.keySet()) {
                if (!term.equals("\u0000len")) {
                    documentFrequency.merge(term, 1, Integer::sum);
                }
            }
            termFrequencies.add(tf);
        }

        double avgLength = Math.max(1.0, (double) totalLength / documents.size());
        int n = documents.size();
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> tf = termFrequencies.get(i);
            double length = tf.get("\u0000len");
            double score = 0;
            for (String term : queryTerms) {
                Integer f = tf.get(term);
                if (f == null) {
                    continue;
                }
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * length / avgLength));
            }
            if (score > 0) {
                scored.add(new Scored(i, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.size() > topK ? scored.subList(0, topK) : scored;
    }

    private static void flushCjk(StringBuilder cjk, List<String> tokens) {
        if (cjk.length() == 1) {
            tokens.add(cjk.toString());
        }
        for (int i = 0; i + 1 < cjk.length(); i++) {
            tokens.add(cjk.substring(i, i + 2));
        }
        cjk.setLength(0);
    }

    private static void flushWord(StringBuilder word, List<String> tokens) {
        if (word.length() > 0) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
    }
}
