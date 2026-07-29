package com.example.agent.tool.react;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PDFGenerationTool implements ReactTool {

    private static final String BUNDLED_FONT_PATH = "fonts/simhei.ttf";
    private static final Object[] GENERATION_LOCKS = createGenerationLocks();

    private final AgentWorkspaceService workspaceService;
    private final String fontPath;
    private final Resource bundledFont = new ClassPathResource(BUNDLED_FONT_PATH);

    public PDFGenerationTool(AgentWorkspaceService workspaceService,
                             @Value("${agent.react.pdf.font-path:}") String fontPath) {
        this.workspaceService = workspaceService;
        this.fontPath = fontPath;
    }

    @Override
    public String name() {
        return "pdf_generation";
    }

    @Override
    public String description() {
        return "Generate a simple PDF artifact from plain text or Markdown-like content in the current agent workspace.";
    }

    @Override
    public String parameters() {
        return """
                {
                  "title": "string, optional",
                  "content": "string, required",
                  "fileName": "string, required, should end with .pdf",
                  "allowDuplicate": "boolean, optional, default false; true only when the user explicitly requests multiple PDFs with the same material"
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String title = ToolArguments.string(arguments, "title");
        String content = ToolArguments.string(arguments, "content");
        String fileName = ToolArguments.string(arguments, "fileName");
        if (content == null || content.isBlank()) {
            return ToolExecutionResult.failure("content is required.");
        }
        if (fileName == null || fileName.isBlank()) {
            fileName = "agent-report.pdf";
        }
        if (!fileName.toLowerCase().endsWith(".pdf")) {
            fileName = fileName + ".pdf";
        }

        String safeFileName = workspaceService.artifactFileName(fileName);
        String idempotencyKey = context.messageId() == null
                ? null
                : "pdf_generation:" + context.messageId() + ":" + safeFileName.toLowerCase(Locale.ROOT);
        Path target = workspaceService.createIdempotentArtifactPath(
                context.workspaceRoot(), safeFileName, idempotencyKey);

        Object generationLock = GENERATION_LOCKS[Math.floorMod(target.hashCode(), GENERATION_LOCKS.length)];
        synchronized (generationLock) {
            try {
                if (Files.isRegularFile(target) && Files.size(target) > 0) {
                    return ToolExecutionResult.success(
                            "PDF already generated for this request; reused: " + target.getFileName(),
                            target.toString());
                }
                generateAtomically(target, title, content);
                return ToolExecutionResult.success("PDF generated: " + target.getFileName(), target.toString());
            } catch (Exception ex) {
                return ToolExecutionResult.failure("PDF generation failed: " + ex.getMessage());
            }
        }
    }

    private void generateAtomically(Path target, String title, String content) throws Exception {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".pdf-generation-", ".tmp");
        try (PDDocument document = new PDDocument()) {
            PDFont font = loadFont(document);
            writePages(document, font, title, content);
            document.save(temporary.toFile());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            Files.deleteIfExists(temporary);
            throw ex;
        }
    }

    private static Object[] createGenerationLocks() {
        Object[] locks = new Object[64];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private PDFont loadFont(PDDocument document) {
        if (fontPath != null && !fontPath.isBlank()) {
            Path externalFont = Path.of(fontPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(externalFont)) {
                throw new IllegalStateException("Configured PDF font does not exist: " + externalFont);
            }
            try (InputStream input = Files.newInputStream(externalFont)) {
                return PDType0Font.load(document, input, true);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to load configured PDF font: " + externalFont, ex);
            }
        }

        try (InputStream input = bundledFont.getInputStream()) {
            return PDType0Font.load(document, input, true);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to load bundled PDF font from classpath:" + BUNDLED_FONT_PATH, ex);
        }
    }

    private void writePages(PDDocument document, PDFont font, String title, String content) throws Exception {
        float margin = 54;
        float fontSize = 11;
        float leading = 16;
        PDRectangle pageSize = PDRectangle.LETTER;
        float width = pageSize.getWidth() - margin * 2;
        List<String> lines = wrap(sanitizeForFont(font, buildContent(title, content)), font, fontSize, width);

        PDPage page = new PDPage(pageSize);
        document.addPage(page);
        PDPageContentStream stream = new PDPageContentStream(document, page);
        stream.beginText();
        stream.setFont(font, fontSize);
        stream.newLineAtOffset(margin, pageSize.getHeight() - margin);

        float y = pageSize.getHeight() - margin;
        for (String line : lines) {
            if (y <= margin) {
                stream.endText();
                stream.close();
                page = new PDPage(pageSize);
                document.addPage(page);
                stream = new PDPageContentStream(document, page);
                stream.beginText();
                stream.setFont(font, fontSize);
                stream.newLineAtOffset(margin, pageSize.getHeight() - margin);
                y = pageSize.getHeight() - margin;
            }
            stream.showText(line);
            stream.newLineAtOffset(0, -leading);
            y -= leading;
        }
        stream.endText();
        stream.close();
    }

    private String buildContent(String title, String content) {
        if (title == null || title.isBlank()) {
            return content;
        }
        return title + "\n\n" + content;
    }

    private List<String> wrap(String text, PDFont font, float fontSize, float width) throws Exception {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (paragraph.isBlank()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int offset = 0; offset < paragraph.length();) {
                int codePoint = paragraph.codePointAt(offset);
                offset += Character.charCount(codePoint);
                String character = Character.isWhitespace(codePoint)
                        ? " "
                        : new String(Character.toChars(codePoint));
                if (line.isEmpty() && character.isBlank()) {
                    continue;
                }

                String candidate = line + character;
                if (!line.isEmpty() && textWidth(candidate, font, fontSize) > width) {
                    lines.add(line.toString().stripTrailing());
                    line.setLength(0);
                    if (character.isBlank()) {
                        continue;
                    }
                }
                line.append(character);
            }
            lines.add(line.toString().stripTrailing());
        }
        return lines;
    }

    private float textWidth(String text, PDFont font, float fontSize) throws Exception {
        return font.getStringWidth(text) / 1000 * fontSize;
    }

    /**
     * PDFBox throws when a font cannot encode a glyph. Preserve normal CJK
     * text and replace only unsupported characters (typically emoji) so one
     * uncommon glyph cannot abort the whole PDF generation task.
     */
    private String sanitizeForFont(PDFont font, String content) {
        String safeContent = content == null ? "" : content;
        StringBuilder result = new StringBuilder(safeContent.length());
        for (int offset = 0; offset < safeContent.length();) {
            int codePoint = safeContent.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\r') {
                continue;
            }
            if (codePoint == '\n') {
                result.append('\n');
                continue;
            }
            if (codePoint == '\t' || Character.isISOControl(codePoint)) {
                result.append(' ');
                continue;
            }

            String character = new String(Character.toChars(codePoint));
            try {
                font.getStringWidth(character);
                result.append(character);
            } catch (Exception unsupportedGlyph) {
                result.append('?');
            }
        }
        return result.toString();
    }
}
