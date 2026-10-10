package com.example.myai;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentToolControllerTest {

    @Test
    void rawFileCommandUsesWorkspaceSandboxInsteadOfBypass() throws Exception {
        DocumentToolController controller = new DocumentToolController("codex", "test-model", 60);

        Method method = DocumentToolController.class.getDeclaredMethod(
                "buildRawFileCommand",
                Path.class,
                Path.class,
                String.class
        );
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) method.invoke(
                controller,
                Path.of("work-dir"),
                Path.of("last-message.txt"),
                "process this file"
        );

        assertThat(command).doesNotContain("--dangerously-bypass-approvals-and-sandbox");
        assertThat(command).containsSubsequence("--sandbox", "workspace-write");
    }

    @Test
    void processDocumentUsesLocalTextProcessingByDefault() throws Exception {
        Path logPath = Files.createTempFile("fake-codex-calls", ".log");
        Files.deleteIfExists(logPath);
        Path fakeCodex = createFakeCodex(logPath);
        DocumentToolController controller = new DocumentToolController(fakeCodex.toString(), "test-model", 60);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "source.txt",
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        ResponseEntity<byte[]> response = controller.processDocument(file, "整理内容并导出为 txt", "tester");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("整理内容并导出为 txt", "hello");
        assertThat(Files.exists(logPath)).isFalse();
    }

    private Path createFakeCodex(Path logPath) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path script = Files.createTempFile("fake-codex", windows ? ".cmd" : ".sh");
        String content = windows ? createWindowsFakeCodex(logPath) : createUnixFakeCodex(logPath);
        Files.writeString(script, content, StandardCharsets.UTF_8);
        if (!windows) {
            script.toFile().setExecutable(true);
        }
        return script;
    }

    private String createWindowsFakeCodex(Path logPath) {
        return "@echo off\r\n"
                + "setlocal\r\n"
                + "set \"LOG=" + logPath + "\"\r\n"
                + "set \"MODE=unknown\"\r\n"
                + ":loop\r\n"
                + "if \"%~1\"==\"\" goto done\r\n"
                + "if \"%~1\"==\"workspace-write\" set \"MODE=workspace-write\"\r\n"
                + "if \"%~1\"==\"read-only\" set \"MODE=read-only\"\r\n"
                + "shift\r\n"
                + "goto loop\r\n"
                + ":done\r\n"
                + "echo %MODE%>>\"%LOG%\"\r\n"
                + "if \"%MODE%\"==\"workspace-write\" exit /b 7\r\n"
                + "exit /b 0\r\n";
    }

    private String createUnixFakeCodex(Path logPath) {
        return "#!/usr/bin/env sh\n"
                + "LOG=" + shellQuote(logPath.toString()) + "\n"
                + "MODE=unknown\n"
                + "while [ \"$#\" -gt 0 ]; do\n"
                + "  if [ \"$1\" = \"workspace-write\" ]; then MODE=workspace-write; fi\n"
                + "  if [ \"$1\" = \"read-only\" ]; then MODE=read-only; fi\n"
                + "  shift || break\n"
                + "done\n"
                + "printf '%s\\n' \"$MODE\" >> \"$LOG\"\n"
                + "if [ \"$MODE\" = \"workspace-write\" ]; then exit 7; fi\n"
                + "exit 0\n";
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
