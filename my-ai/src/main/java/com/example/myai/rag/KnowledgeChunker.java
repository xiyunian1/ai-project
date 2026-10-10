package com.example.myai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按标题和段落切分资料。每个分块带上所在的标题路径（如「第三章 近效期管理 > 3.2 预警」），
 * 单独检索出来时也能看出它属于哪一部分。超长段落按句号等断句继续切分，同一小节内相邻分块保留少量重叠。
 */
public class KnowledgeChunker {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern CHINESE_CHAPTER = Pattern.compile("^第[一二三四五六七八九十百零0-9]+[章节]\\s*.*$");
    private static final Pattern CHINESE_ARTICLE = Pattern.compile("^第[一二三四五六七八九十百零0-9]+条\\s*.{0,30}$");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？；!?;])");

    public record Chunk(int index, String heading, String body) {
        /** 写入向量库和交给模型的文本：标题路径 + 正文。 */
        public String text() {
            return heading.isBlank() ? body : heading + "\n" + body;
        }
    }

    private final int targetChars;
    private final int overlapChars;

    public KnowledgeChunker() {
        this(500, 80);
    }

    public KnowledgeChunker(int targetChars, int overlapChars) {
        this.targetChars = Math.max(100, targetChars);
        this.overlapChars = Math.max(0, Math.min(overlapChars, this.targetChars / 3));
    }

    public List<Chunk> split(String title, String text) {
        List<Section> sections = toSections(text == null ? "" : text);
        List<Chunk> chunks = new ArrayList<>();
        for (Section section : sections) {
            String heading = joinHeading(title, section.headingPath);
            String previousTail = "";
            StringBuilder current = new StringBuilder();
            for (String paragraph : section.paragraphs) {
                for (String piece : splitLongParagraph(paragraph)) {
                    if (current.length() > 0 && current.length() + piece.length() + 1 > targetChars) {
                        previousTail = flush(chunks, heading, current, previousTail);
                    }
                    if (current.length() > 0) {
                        current.append('\n');
                    }
                    current.append(piece);
                }
            }
            if (current.length() > 0) {
                flush(chunks, heading, current, previousTail);
            }
        }
        return chunks;
    }

    private String flush(List<Chunk> chunks, String heading, StringBuilder current, String previousTail) {
        String body = current.toString().trim();
        String withOverlap = previousTail.isBlank() ? body : previousTail + "\n" + body;
        chunks.add(new Chunk(chunks.size(), heading, withOverlap));
        current.setLength(0);
        return tail(body);
    }

    /** 取上一块末尾不超过 overlapChars 的完整句子，作为下一块开头的重叠部分。 */
    private String tail(String body) {
        if (overlapChars == 0) {
            return "";
        }
        String[] sentences = SENTENCE_END.split(body);
        StringBuilder sb = new StringBuilder();
        for (int i = sentences.length - 1; i >= 0; i--) {
            String sentence = sentences[i].trim();
            if (sentence.isEmpty()) {
                continue;
            }
            if (sb.length() + sentence.length() > overlapChars) {
                break;
            }
            sb.insert(0, sentence);
        }
        return sb.toString();
    }

    private List<String> splitLongParagraph(String paragraph) {
        if (paragraph.length() <= targetChars) {
            return List.of(paragraph);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SENTENCE_END.split(paragraph)) {
            if (current.length() > 0 && current.length() + sentence.length() > targetChars) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            // 单句本身超长时硬切
            while (sentence.length() > targetChars) {
                pieces.add(sentence.substring(0, targetChars));
                sentence = sentence.substring(targetChars);
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    private static String joinHeading(String title, List<String> path) {
        List<String> parts = new ArrayList<>();
        if (title != null && !title.isBlank()) {
            parts.add("《" + title.replaceAll("\\.(md|txt|docx?|pdf)$", "") + "》");
        }
        parts.addAll(path);
        return String.join(" > ", parts);
    }

    private record Section(List<String> headingPath, List<String> paragraphs) {
    }

    private static List<Section> toSections(String text) {
        List<Section> sections = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        List<Integer> levelStack = new ArrayList<>();
        List<String> paragraphs = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();

        Runnable endParagraph = () -> {
            if (paragraph.length() > 0) {
                paragraphs.add(paragraph.toString().trim());
                paragraph.setLength(0);
            }
        };

        for (String rawLine : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.strip();
            int level = headingLevel(line);
            if (level > 0) {
                endParagraph.run();
                if (!paragraphs.isEmpty()) {
                    sections.add(new Section(List.copyOf(headingStack), List.copyOf(paragraphs)));
                    paragraphs.clear();
                }
                while (!levelStack.isEmpty() && levelStack.get(levelStack.size() - 1) >= level) {
                    levelStack.remove(levelStack.size() - 1);
                    headingStack.remove(headingStack.size() - 1);
                }
                levelStack.add(level);
                headingStack.add(cleanHeading(line));
                continue;
            }
            if (line.isEmpty()) {
                endParagraph.run();
                continue;
            }
            if (paragraph.length() > 0) {
                paragraph.append('\n');
            }
            paragraph.append(line);
        }
        endParagraph.run();
        if (!paragraphs.isEmpty()) {
            sections.add(new Section(List.copyOf(headingStack), List.copyOf(paragraphs)));
        }
        return sections;
    }

    private static int headingLevel(String line) {
        var md = MARKDOWN_HEADING.matcher(line);
        if (md.matches()) {
            return md.group(1).length();
        }
        if (CHINESE_CHAPTER.matcher(line).matches() && line.length() <= 30) {
            return 2;
        }
        if (CHINESE_ARTICLE.matcher(line).matches()) {
            return 3;
        }
        return 0;
    }

    private static String cleanHeading(String line) {
        return line.replaceFirst("^#{1,6}\\s+", "").trim();
    }
}
