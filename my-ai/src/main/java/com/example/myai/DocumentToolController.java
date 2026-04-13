package com.example.myai;

import jakarta.validation.constraints.NotBlank;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/tools/document")
@Validated
public class DocumentToolController {
    private static final Logger log = LoggerFactory.getLogger(DocumentToolController.class);
    private static final int MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_REQUIREMENT_CHARS = 2000;
    private static final int MAX_INPUT_CHARS = 120_000;
    private static final int MAX_OUTPUT_CHARS = 300_000;
    private static final int PREVIEW_HEADER_CHARS = 2500;

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "csv", "json", "xml", "html", "htm", "yaml", "yml", "log",
            "sql", "java", "js", "ts", "vue", "properties", "ini"
    );

    private final String codexBinary;
    private final String codexModel;
    private final long codexTimeoutSeconds;

    public DocumentToolController(
            @Value("${codex.binary:codex}") String codexBinary,
            @Value("${codex.model:gpt-5.2-codex}") String codexModel,
            @Value("${codex.timeout-seconds:600}") long codexTimeoutSeconds
    ) {
        this.codexBinary = codexBinary;
        this.codexModel = codexModel;
        this.codexTimeoutSeconds = codexTimeoutSeconds;
    }

    @PostMapping(value = "/process", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> processDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("requirement") @NotBlank(message = "processing requirement is required") String requirement,
            @RequestAttribute(value = AuthInterceptor.ATTR_USERNAME, required = false) String authUsername
    ) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Please upload a file");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File size cannot exceed 20MB");
        }

        String cleanRequirement = requirement.trim();
        if (cleanRequirement.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Processing requirement cannot be empty");
        }
        if (cleanRequirement.length() > MAX_REQUIREMENT_CHARS) {
            cleanRequirement = cleanRequirement.substring(0, MAX_REQUIREMENT_CHARS);
        }

        String sourceFileName = sanitizeFileName(file.getOriginalFilename());
        String sourceExt = getExtension(sourceFileName);
        byte[] sourceBytes = readBytes(file);
        String targetExt = resolveTargetExtension(sourceExt, cleanRequirement);
        RawProcessAttempt rawAttempt = tryProcessWithOriginalFile(
                sourceFileName,
                sourceBytes,
                targetExt,
                cleanRequirement,
                authUsername
        );

        OutputArtifact artifact;
        String previewText;
        String processMode;
        if (rawAttempt.artifactWithPreview() != null) {
            artifact = rawAttempt.artifactWithPreview().artifact();
            previewText = rawAttempt.artifactWithPreview().previewText();
            processMode = "raw-file";
        } else if (rawAttempt.timedOut()) {
            throw new ApiException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "Codex raw-file processing timed out after " + codexTimeoutSeconds + " seconds"
            );
        } else {
            String sourceText = extractTextForCodex(sourceFileName, sourceExt, sourceBytes, file.getContentType());
            String resultText = runCodex(sourceFileName, sourceText, cleanRequirement, authUsername);
            if (resultText.isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Codex returned empty result");
            }
            if (resultText.length() > MAX_OUTPUT_CHARS) {
                resultText = resultText.substring(0, MAX_OUTPUT_CHARS) + "\n\n...(result truncated)";
            }
            artifact = buildOutputArtifact(sourceFileName, targetExt, resultText);
            previewText = resultText;
            processMode = "text-fallback";
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(artifact.mediaType());
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(artifact.fileName(), StandardCharsets.UTF_8)
                .build());

        String preview = truncatePreview(previewText, PREVIEW_HEADER_CHARS);
        headers.set("X-Processed-Preview-Base64",
                Base64.getEncoder().encodeToString(preview.getBytes(StandardCharsets.UTF_8)));
        headers.set("X-Process-Mode", processMode);

        return ResponseEntity.ok()
                .headers(headers)
                .body(artifact.bytes());
    }

    private String runCodex(String sourceFileName, String sourceText, String requirement, String authUsername) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("codex-doc-");
            Path outputPath = workDir.resolve("processed.txt");
            Path logPath = workDir.resolve("codex-exec.log");

            String prompt = buildPrompt(sourceFileName, sourceText, requirement, authUsername);
            List<String> command = buildCommand(workDir, outputPath, prompt);

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(logPath.toFile());

            Process process;
            try {
                process = processBuilder.start();
            } catch (IOException startEx) {
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Failed to start Codex CLI. Please ensure codex is installed and logged in."
                );
            }

            boolean finished = process.waitFor(codexTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ApiException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "Codex processing timed out after " + codexTimeoutSeconds + " seconds"
                );
            }

            int exitCode = process.exitValue();
            String logTail = readLogTail(logPath, 4000);

            if (exitCode != 0) {
                log.warn("Codex exec failed, exitCode={}, tail={}", exitCode, abbreviate(logTail, 400));
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Codex processing failed. " + abbreviate(logTail, 300));
            }

            if (!Files.exists(outputPath)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Codex did not produce output");
            }

            String result = Files.readString(outputPath, StandardCharsets.UTF_8);
            if (!result.isEmpty() && result.charAt(0) == '\uFEFF') {
                result = result.substring(1);
            }
            return result.trim();
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Codex processing interrupted");
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process uploaded file");
        } finally {
            deleteDirectoryQuietly(workDir);
        }
    }

    private RawProcessAttempt tryProcessWithOriginalFile(
            String sourceFileName,
            byte[] sourceBytes,
            String targetExt,
            String requirement,
            String authUsername
    ) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("codex-raw-doc-");
            Path sourcePath = workDir.resolve(sourceFileName);
            String outputFileName = getBaseName(sourceFileName) + "-processed." + targetExt;
            Path outputPath = workDir.resolve(outputFileName);
            Path lastMessagePath = workDir.resolve("codex-last-message.txt");
            Path logPath = workDir.resolve("codex-raw.log");

            Files.write(sourcePath, sourceBytes);

            List<String> command = buildRawFileCommand(
                    workDir,
                    lastMessagePath,
                    buildRawFilePrompt(sourceFileName, outputFileName, requirement, authUsername)
            );

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(logPath.toFile());

            Process process = processBuilder.start();
            boolean finished = process.waitFor(codexTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Codex raw-file mode timed out");
                return new RawProcessAttempt(null, true);
            }
            if (process.exitValue() != 0) {
                String tail = readLogTail(logPath, 2000);
                log.warn("Codex raw-file mode failed, fallback to text mode. tail={}", abbreviate(tail, 400));
                return new RawProcessAttempt(null, false);
            }

            if (!Files.exists(outputPath) || Files.size(outputPath) <= 0) {
                log.warn("Codex raw-file mode produced no output file, fallback to text mode");
                return new RawProcessAttempt(null, false);
            }

            byte[] outputBytes = Files.readAllBytes(outputPath);
            String previewText = extractPreviewTextForOutput(targetExt, outputBytes);
            if (previewText.isBlank() && Files.exists(lastMessagePath)) {
                previewText = Files.readString(lastMessagePath, StandardCharsets.UTF_8);
            }
            if (previewText.isBlank()) {
                previewText = "(binary output generated)";
            }

            OutputArtifact artifact = new OutputArtifact(
                    outputBytes,
                    outputFileName,
                    mediaTypeForOutputExt(targetExt)
            );
            return new RawProcessAttempt(new ArtifactWithPreview(artifact, previewText), false);
        } catch (Exception ex) {
            log.warn("Raw-file codex mode unavailable, fallback to text mode", ex);
            return new RawProcessAttempt(null, false);
        } finally {
            deleteDirectoryQuietly(workDir);
        }
    }

    private List<String> buildCommand(Path workDir, Path outputPath, String prompt) {
        List<String> command = new ArrayList<>();
        command.add(codexBinary);
        command.add("exec");
        command.add("--skip-git-repo-check");
        command.add("--ephemeral");
        command.add("--sandbox");
        command.add("read-only");
        command.add("--cd");
        command.add(workDir.toString());
        command.add("--output-last-message");
        command.add(outputPath.toString());
        if (codexModel != null && !codexModel.isBlank()) {
            command.add("--model");
            command.add(codexModel.trim());
        }
        command.add(prompt);
        return command;
    }

    private List<String> buildRawFileCommand(Path workDir, Path lastMessagePath, String prompt) {
        List<String> command = new ArrayList<>();
        command.add(codexBinary);
        command.add("exec");
        command.add("--skip-git-repo-check");
        command.add("--ephemeral");
        command.add("--dangerously-bypass-approvals-and-sandbox");
        command.add("--cd");
        command.add(workDir.toString());
        command.add("--output-last-message");
        command.add(lastMessagePath.toString());
        if (codexModel != null && !codexModel.isBlank()) {
            command.add("--model");
            command.add(codexModel.trim());
        }
        command.add(prompt);
        return command;
    }

    private String buildPrompt(String sourceFileName, String sourceText, String requirement, String authUsername) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a document processing assistant.\n");
        prompt.append("Original file name: ").append(sourceFileName).append("\n");
        prompt.append("Apply the following requirement exactly:\n");
        prompt.append(requirement).append("\n\n");
        prompt.append("Document content (between tags):\n");
        prompt.append("<document>\n");
        prompt.append(sourceText).append("\n");
        prompt.append("</document>\n\n");
        prompt.append("Output rules:\n");
        prompt.append("1) Return ONLY the final processed document content in plain text.\n");
        prompt.append("2) Do NOT include explanations, headers, markdown code fences, or notes.\n");
        prompt.append("3) Keep original language and key terms unless requirement asks otherwise.\n");
        if (authUsername != null && !authUsername.isBlank()) {
            prompt.append("Operator: ").append(authUsername).append("\n");
        }
        return prompt.toString();
    }

    private String buildRawFilePrompt(String sourceFileName, String outputFileName, String requirement, String authUsername) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a document editor.\n");
        prompt.append("Input file in current directory: ").append(sourceFileName).append("\n");
        prompt.append("Apply requirement:\n").append(requirement).append("\n\n");
        prompt.append("Hard requirements:\n");
        prompt.append("1) Keep original file structure/layout/media as much as possible.\n");
        prompt.append("2) Preserve embedded images/media unless requirement says remove them.\n");
        prompt.append("3) Write the processed result to file: ").append(outputFileName).append("\n");
        prompt.append("4) If direct editing fails, copy source file to output file so output always exists.\n");
        prompt.append("5) Prefer minimal modifications; avoid rewriting from scratch.\n");
        if (authUsername != null && !authUsername.isBlank()) {
            prompt.append("Operator: ").append(authUsername).append("\n");
        }
        return prompt.toString();
    }

    private String extractTextForCodex(String sourceFileName, String sourceExt, byte[] sourceBytes, String contentType) {
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
                        text = buildBinaryFallbackText(sourceFileName, sourceBytes);
                    }
                    break;
            }
        } catch (Exception ex) {
            log.warn("Failed to extract file text, name={}, ext={}", sourceFileName, sourceExt, ex);
            text = buildBinaryFallbackText(sourceFileName, sourceBytes);
        }

        text = normalizeText(text);
        if (text.trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Uploaded document content is empty");
        }
        if (text.length() > MAX_INPUT_CHARS) {
            text = text.substring(0, MAX_INPUT_CHARS);
        }
        return text;
    }

    private OutputArtifact buildOutputArtifact(String sourceFileName, String targetExt, String resultText) {
        String baseName = getBaseName(sourceFileName);
        String outputFileName = baseName + "-processed." + targetExt;
        try {
            switch (targetExt) {
                case "docx":
                    return new OutputArtifact(
                            createDocxBytes(resultText),
                            outputFileName,
                            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    );
                case "pdf":
                    return new OutputArtifact(
                            createPdfBytes(resultText),
                            outputFileName,
                            MediaType.APPLICATION_PDF
                    );
                case "xlsx":
                    return new OutputArtifact(
                            createXlsxBytes(resultText),
                            outputFileName,
                            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    );
                default:
                    return new OutputArtifact(
                            resultText.getBytes(StandardCharsets.UTF_8),
                            outputFileName,
                            mediaTypeForTextExt(targetExt)
                    );
            }
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to generate output file");
        }
    }

    private String resolveTargetExtension(String sourceExt, String requirement) {
        String req = requirement == null ? "" : requirement.toLowerCase(Locale.ROOT);
        if (containsAny(req, ".docx", " docx", "word", "文档", "docx格式")) {
            return "docx";
        }
        if (containsAny(req, ".pdf", " pdf", "导出pdf", "pdf格式")) {
            return "pdf";
        }
        if (containsAny(req, ".xlsx", "xlsx", "excel", "表格")) {
            return "xlsx";
        }
        if (containsAny(req, ".md", "markdown")) {
            return "md";
        }
        if (containsAny(req, ".txt", "纯文本", "text")) {
            return "txt";
        }

        if ("doc".equals(sourceExt)) return "docx";
        if ("xls".equals(sourceExt)) return "xlsx";
        if ("ppt".equals(sourceExt) || "pptx".equals(sourceExt)) return "txt";
        if (sourceExt == null || sourceExt.isBlank()) return "txt";
        return sourceExt;
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

    private byte[] createDocxBytes(String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            String[] lines = normalizeText(text).split("\n", -1);
            for (String line : lines) {
                document.createParagraph().createRun().setText(line);
            }
            document.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private byte[] createPdfBytes(String text) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            List<String> lines = wrapLines(normalizeText(text), 95);

            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDPageContentStream stream = new PDPageContentStream(document, page);
            stream.setFont(PDType1Font.HELVETICA, 11);
            stream.beginText();
            stream.setLeading(14f);
            stream.newLineAtOffset(40, 800);
            float y = 800;

            for (String line : lines) {
                if (y < 60) {
                    stream.endText();
                    stream.close();
                    page = new PDPage(PDRectangle.A4);
                    document.addPage(page);
                    stream = new PDPageContentStream(document, page);
                    stream.setFont(PDType1Font.HELVETICA, 11);
                    stream.beginText();
                    stream.setLeading(14f);
                    stream.newLineAtOffset(40, 800);
                    y = 800;
                }
                stream.showText(line);
                stream.newLine();
                y -= 14f;
            }

            stream.endText();
            stream.close();
            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }

    private byte[] createXlsxBytes(String text) throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Processed");
            String[] lines = normalizeText(text).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Row row = sheet.createRow(i);
                String[] cols = lines[i].split("\t", -1);
                for (int j = 0; j < cols.length; j++) {
                    row.createCell(j).setCellValue(cols[j]);
                }
            }
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private String extractPreviewTextForOutput(String extension, byte[] outputBytes) {
        try {
            switch (extension) {
                case "docx":
                    return truncatePreview(normalizeText(extractDocxText(outputBytes)), PREVIEW_HEADER_CHARS);
                case "doc":
                    return truncatePreview(normalizeText(extractDocText(outputBytes)), PREVIEW_HEADER_CHARS);
                case "pdf":
                    return truncatePreview(normalizeText(extractPdfText(outputBytes)), PREVIEW_HEADER_CHARS);
                case "xlsx":
                case "xls":
                    return truncatePreview(normalizeText(extractSpreadsheetText(outputBytes)), PREVIEW_HEADER_CHARS);
                default:
                    if (isLikelyText(null, outputBytes)) {
                        return truncatePreview(normalizeText(decodeText(outputBytes)), PREVIEW_HEADER_CHARS);
                    }
                    return "";
            }
        } catch (Exception ex) {
            return "";
        }
    }

    private MediaType mediaTypeForOutputExt(String extension) {
        switch (extension) {
            case "docx":
                return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "doc":
                return MediaType.parseMediaType("application/msword");
            case "pdf":
                return MediaType.APPLICATION_PDF;
            case "xlsx":
                return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "xls":
                return MediaType.parseMediaType("application/vnd.ms-excel");
            default:
                return mediaTypeForTextExt(extension);
        }
    }

    private MediaType mediaTypeForTextExt(String extension) {
        switch (extension) {
            case "json":
                return MediaType.APPLICATION_JSON;
            case "xml":
                return MediaType.APPLICATION_XML;
            case "html":
            case "htm":
                return MediaType.TEXT_HTML;
            case "csv":
                return MediaType.parseMediaType("text/csv");
            case "md":
                return MediaType.parseMediaType("text/markdown");
            default:
                return new MediaType("text", "plain", StandardCharsets.UTF_8);
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

    private String decodeText(byte[] sourceBytes) {
        String text = new String(sourceBytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    private String normalizeText(String text) {
        return Optional.ofNullable(text).orElse("")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private boolean isLikelyText(String contentType, byte[] bytes) {
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

    private List<String> wrapLines(String text, int maxChars) {
        List<String> wrapped = new ArrayList<>();
        String[] rawLines = normalizeText(text).split("\n", -1);
        for (String line : rawLines) {
            if (line.length() <= maxChars) {
                wrapped.add(line);
                continue;
            }
            int start = 0;
            while (start < line.length()) {
                int end = Math.min(start + maxChars, line.length());
                wrapped.add(line.substring(start, end));
                start = end;
            }
        }
        return wrapped;
    }

    private String readLogTail(Path logPath, int maxChars) {
        if (logPath == null || !Files.exists(logPath)) return "";
        try {
            String content = Files.readString(logPath, StandardCharsets.UTF_8);
            if (content.length() <= maxChars) return content;
            return content.substring(content.length() - maxChars);
        } catch (IOException ex) {
            return "";
        }
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignore) {
                        }
                    });
        } catch (IOException ignore) {
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read uploaded file");
        }
    }

    private String sanitizeFileName(String originalName) {
        String raw = Optional.ofNullable(originalName).orElse("document.txt");
        String safe = raw.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
        if (safe.isEmpty()) {
            return "document.txt";
        }
        if (!safe.contains(".")) {
            return safe + ".txt";
        }
        return safe;
    }

    private String getBaseName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String safe = baseName.trim();
        return safe.isEmpty() ? "document" : safe;
    }

    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String truncatePreview(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n\n...(preview truncated)";
    }

    private boolean containsAny(String target, String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && target.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String abbreviate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...";
    }

    private record OutputArtifact(byte[] bytes, String fileName, MediaType mediaType) {
    }

    private record ArtifactWithPreview(OutputArtifact artifact, String previewText) {
    }

    private record RawProcessAttempt(ArtifactWithPreview artifactWithPreview, boolean timedOut) {
    }
}
