package com.example.myai;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class DocumentTextExtractor {
    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);
    public static final int DEFAULT_MAX_INPUT_CHARS = 120_000;

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "csv", "json", "xml", "html", "htm", "yaml", "yml", "log",
            "sql", "java", "js", "ts", "vue", "properties", "ini"
    );

    public String extractText(String fileName, byte[] sourceBytes, String contentType) {
        return extractText(fileName, sourceBytes, contentType, DEFAULT_MAX_INPUT_CHARS);
    }

    public String extractText(String fileName, byte[] sourceBytes, String contentType, int maxChars) {
        String safeFileName = sanitizeFileName(fileName);
        String sourceExt = getExtension(safeFileName);
        String text;
        try {
            switch (sourceExt) {
                case "docx":
                    text = extractDocxText(sourceBytes);
                    break;
                case "doc":
                    text = extractDocText(sourceBytes);
                    break;
                case "pdf":
                    text = extractPdfText(sourceBytes);
                    break;
                case "xlsx":
                case "xls":
                    text = extractSpreadsheetText(sourceBytes);
                    break;
                default:
                    if (TEXT_EXTENSIONS.contains(sourceExt) || isLikelyText(contentType, sourceBytes)) {
                        text = decodeText(sourceBytes);
                    } else {
                        text = buildBinaryFallbackText(safeFileName, sourceBytes);
                    }
                    break;
            }
        } catch (Exception ex) {
            log.warn("Failed to extract file text, name={}, ext={}", safeFileName, sourceExt, ex);
            text = buildBinaryFallbackText(safeFileName, sourceBytes);
        }

        text = normalizeText(text);
        if (text.trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Uploaded document content is empty");
        }
        if (maxChars > 0 && text.length() > maxChars) {
            text = text.substring(0, maxChars);
        }
        return text;
    }

    public String sanitizeFileName(String originalName) {
        String raw = Optional.ofNullable(originalName).orElse("document.txt");
        String safe = raw.replaceAll("[\\/:*?\"<>|]+", "_").trim();
        if (safe.isEmpty()) {
            return "document.txt";
        }
        if (!safe.contains(".")) {
            return safe + ".txt";
        }
        return safe;
    }

    public String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    public String normalizeText(String text) {
        return Optional.ofNullable(text).orElse("")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    public boolean isLikelyText(String contentType, byte[] bytes) {
        String lowerType = Optional.ofNullable(contentType).orElse("").toLowerCase(Locale.ROOT);
        if (lowerType.startsWith("text/")) return true;
        if (lowerType.contains("/json") || lowerType.endsWith("+json")) return true;
        if (lowerType.contains("/xml") || lowerType.endsWith("+xml")) return true;

        int inspectLength = Math.min(bytes.length, 1024);
        int suspicious = 0;
        for (int i = 0; i < inspectLength; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) return false;
            if (b < 9 || (b > 13 && b < 32)) suspicious++;
        }
        return inspectLength == 0 || suspicious < inspectLength / 10;
    }

    public String decodeText(byte[] sourceBytes) {
        String text = new String(sourceBytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    private String extractDocxText(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String extractDocText(byte[] bytes) throws IOException {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String extractPdfText(byte[] bytes) throws IOException {
        try (PDDocument document = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String extractSpreadsheetText(byte[] bytes) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder sb = new StringBuilder();
            for (Sheet sheet : workbook) {
                sb.append("[Sheet] ").append(sheet.getSheetName()).append("\n");
                for (Row row : sheet) {
                    boolean first = true;
                    for (Cell cell : row) {
                        if (!first) sb.append('\t');
                        sb.append(formatter.formatCellValue(cell));
                        first = false;
                    }
                    sb.append('\n');
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    private String buildBinaryFallbackText(String sourceFileName, byte[] sourceBytes) {
        int rawLimit = Math.min(sourceBytes.length, 45_000);
        byte[] truncated = new byte[rawLimit];
        System.arraycopy(sourceBytes, 0, truncated, 0, rawLimit);
        String encoded = Base64.getEncoder().encodeToString(truncated);
        return "Binary file: " + sourceFileName + "\n"
                + "Base64 payload (truncated):\n"
                + encoded;
    }
}
