package com.example.agent.controller;

import com.example.agent.service.AgentArtifactNotFoundException;
import com.example.agent.service.AgentArtifactService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentArtifactController {

    private final AgentArtifactService artifactService;

    @GetMapping("/artifacts")
    @ResponseBody
    public ResponseEntity<?> list(@RequestParam Long sessionId, HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        try {
            return ResponseEntity.ok(artifactService.list(userId, sessionId));
        } catch (IllegalArgumentException notOwned) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/artifacts/{artifactId}/content")
    @ResponseBody
    public ResponseEntity<?> content(@PathVariable Long artifactId,
                                     @RequestParam(defaultValue = "false") boolean download,
                                     HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        try {
            AgentArtifactService.ArtifactDownload artifact = artifactService.openOwned(userId, artifactId);
            boolean inline = !download && artifact.previewable();
            ContentDisposition disposition = ContentDisposition
                    .builder(inline ? "inline" : "attachment")
                    .filename(artifact.fileName(), StandardCharsets.UTF_8)
                    .build();
            MediaType contentType;
            try {
                contentType = MediaType.parseMediaType(artifact.contentType());
            } catch (IllegalArgumentException invalidType) {
                contentType = MediaType.APPLICATION_OCTET_STREAM;
            }

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore().cachePrivate())
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .contentType(contentType)
                    .contentLength(artifact.sizeBytes())
                    .body(new FileSystemResource(artifact.path()));
        } catch (AgentArtifactNotFoundException notFound) {
            return ResponseEntity.notFound().build();
        }
    }
}
