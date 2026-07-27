package com.example.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${upload.dir}")
    private String uploadDir; // 这里现在读取到的是 "."

    @Value("${upload.avatar:uploads/avatar/}")
    private String avatarDir;

    @Value("${upload.doc-media:uploads/doc-media/}")
    private String docMediaDir;

    @Value("${upload.question-images:uploads/question-images/}")
    private String questionImagesDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path projectRoot = Path.of(uploadDir).toAbsolutePath().normalize();

        // Only explicitly public media directories are exposed. Agent artifacts
        // are private and are served through the authenticated controller.
        registerPublicDirectory(registry, projectRoot, avatarDir);
        registerPublicDirectory(registry, projectRoot, docMediaDir);
        registerPublicDirectory(registry, projectRoot, questionImagesDir);
    }

    private void registerPublicDirectory(ResourceHandlerRegistry registry, Path projectRoot, String configuredDir) {
        String webDirectory = configuredDir.replace('\\', '/').replaceAll("^/+|/+$", "");
        Path location = projectRoot.resolve(webDirectory).normalize();
        if (webDirectory.isBlank() || !location.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Public upload directory must stay inside upload.dir.");
        }
        String resourceLocation = location.toUri().toString();
        if (!resourceLocation.endsWith("/")) {
            resourceLocation += "/";
        }
        registry.addResourceHandler("/" + webDirectory + "/**")
                .addResourceLocations(resourceLocation);
    }
}
