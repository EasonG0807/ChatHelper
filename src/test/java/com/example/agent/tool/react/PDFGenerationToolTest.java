package com.example.agent.tool.react;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void sameRequestedNameCreatesDistinctArtifactsWithoutLeakingServerPath() {
        AgentWorkspaceService workspaceService = new AgentWorkspaceService(tempDir.toString());
        Path workspace = workspaceService.workspace(1L, 1L);
        PDFGenerationTool tool = new PDFGenerationTool(workspaceService, "");
        ToolExecutionContext context = new ToolExecutionContext(1L, 1L, 1L, workspace);

        ToolExecutionResult first = tool.execute(context,
                Map.of("content", "第一份", "fileName", "report.pdf"));
        ToolExecutionResult second = tool.execute(context,
                Map.of("content", "第二份", "fileName", "report.pdf"));

        assertTrue(first.success(), first.errorMessage());
        assertTrue(second.success(), second.errorMessage());
        assertNotEquals(first.artifactPath(), second.artifactPath());
        assertTrue(Files.isRegularFile(Path.of(first.artifactPath())));
        assertTrue(Files.isRegularFile(Path.of(second.artifactPath())));
        assertFalse(first.content().contains(tempDir.toString()));
        assertFalse(first.toObservation().contains(tempDir.toString()));
    }
}
