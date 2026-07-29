package com.example.agent.tool.react;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PDFGenerationToolTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesSearchableMultiPageChinesePdfWithBundledFont() throws Exception {
        AgentWorkspaceService workspaceService = new AgentWorkspaceService(tempDir.toString());
        Path workspace = workspaceService.workspace(1L, 1L);
        PDFGenerationTool tool = new PDFGenerationTool(workspaceService, "");
        String content = "中文字体测试：这是一段没有空格的长中文内容，用于确认字体嵌入、自动换行和分页都能正常工作。"
                .repeat(160);

        ToolExecutionResult result = tool.execute(
                new ToolExecutionContext(1L, 1L, 1L, workspace),
                Map.of("title", "中文 PDF 验证", "content", content, "fileName", "chinese-test.pdf"));

        assertTrue(result.success(), result.errorMessage());
        Path pdfPath = Path.of(result.artifactPath());
        assertTrue(Files.isRegularFile(pdfPath));
        assertTrue(Files.size(pdfPath) > 0);

        try (PDDocument document = PDDocument.load(pdfPath.toFile())) {
            String extracted = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
            assertTrue(document.getNumberOfPages() > 1, "Long CJK text should wrap across pages");
            assertTrue(extracted.contains("中文字体测试"));
            assertFalse(extracted.contains("????????"));
        }
    }

    @Test
    void sameMessageAndRequestedNameReusesOneArtifactWithoutOverwritingIt() throws Exception {
        AgentWorkspaceService workspaceService = new AgentWorkspaceService(tempDir.toString());
        Path workspace = workspaceService.workspace(1L, 1L);
        PDFGenerationTool tool = new PDFGenerationTool(workspaceService, "");
        ToolExecutionContext context = new ToolExecutionContext(1L, 1L, 1L, workspace);

        ToolExecutionResult first = tool.execute(context,
                Map.of("content", "first version", "fileName", "report.pdf"));
        ToolExecutionResult second = tool.execute(context,
                Map.of("content", "second version", "fileName", "report.pdf"));

        assertTrue(first.success(), first.errorMessage());
        assertTrue(second.success(), second.errorMessage());
        assertEquals(first.artifactPath(), second.artifactPath());
        assertTrue(second.content().contains("reused"));
        assertTrue(Files.isRegularFile(Path.of(first.artifactPath())));
        assertFalse(first.content().contains(tempDir.toString()));
        assertFalse(first.toObservation().contains(tempDir.toString()));

        try (PDDocument document = PDDocument.load(Path.of(first.artifactPath()).toFile())) {
            String extracted = new PDFTextStripper().getText(document);
            assertTrue(extracted.contains("first version"));
            assertFalse(extracted.contains("second version"));
        }
        try (var paths = Files.walk(workspace.resolve("artifacts"))) {
            assertEquals(1, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void sameFileNameInDifferentMessagesCreatesDistinctArtifacts() {
        AgentWorkspaceService workspaceService = new AgentWorkspaceService(tempDir.toString());
        Path workspace = workspaceService.workspace(1L, 1L);
        PDFGenerationTool tool = new PDFGenerationTool(workspaceService, "");

        ToolExecutionResult first = tool.execute(
                new ToolExecutionContext(1L, 1L, 1L, workspace),
                Map.of("content", "first request", "fileName", "report.pdf"));
        ToolExecutionResult second = tool.execute(
                new ToolExecutionContext(1L, 1L, 2L, workspace),
                Map.of("content", "second request", "fileName", "report.pdf"));

        assertTrue(first.success(), first.errorMessage());
        assertTrue(second.success(), second.errorMessage());
        assertNotEquals(first.artifactPath(), second.artifactPath());
    }

    @Test
    void preservesRequestedChineseArtifactFileName() {
        AgentWorkspaceService workspaceService = new AgentWorkspaceService(tempDir.toString());
        Path workspace = workspaceService.workspace(1L, 1L);
        PDFGenerationTool tool = new PDFGenerationTool(workspaceService, "");
        String requestedName = "项目难点面试回答.pdf";

        ToolExecutionResult result = tool.execute(
                new ToolExecutionContext(1L, 1L, 1L, workspace),
                Map.of("title", "项目难点", "content", "中文文件名测试", "fileName", requestedName));

        assertTrue(result.success(), result.errorMessage());
        Path artifact = Path.of(result.artifactPath());
        assertEquals(requestedName, artifact.getFileName().toString());
        assertTrue(Files.isRegularFile(artifact));
        assertTrue(result.content().contains(requestedName));
        assertTrue(result.toObservation().contains(requestedName));
    }

    @Test
    void keepsUnicodeWhileReplacingUnsafeFileNameCharacters() {
        AgentWorkspaceService workspaceService = new AgentWorkspaceService(tempDir.toString());
        Path workspace = workspaceService.workspace(1L, 1L);

        Path artifact = workspaceService.createArtifactPath(workspace, "../项目:答辩?.pdf");

        assertEquals("项目_答辩_.pdf", artifact.getFileName().toString());
        assertTrue(artifact.normalize().startsWith(workspace.normalize()));
    }
}
