package com.example.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SentryMcpConfigurationTest {

    @Test
    void baseConfigurationKeepsMcpAndSentryReportingInactive() throws Exception {
        PropertySourcesPropertyResolver resolver = resolver("application.yml");

        assertEquals(false, resolver.getProperty("spring.ai.mcp.client.enabled", Boolean.class));
        assertEquals("", resolver.getProperty("sentry.dsn"));
    }

    @Test
    void sentryProfileDefinesPinnedWindowsStdioConnection() throws Exception {
        PropertySourcesPropertyResolver resolver = resolver("application-sentry-mcp.yml");

        assertEquals("sentry-mcp", resolver.getProperty("spring.config.activate.on-profile"));
        assertEquals(true, resolver.getProperty("spring.ai.mcp.client.enabled", Boolean.class));
        assertEquals("cmd", resolver.getProperty(
                "spring.ai.mcp.client.stdio.connections.sentry.command"));
        assertEquals("@sentry/mcp-server@0.37.0", resolver.getProperty(
                "spring.ai.mcp.client.stdio.connections.sentry.args[3]"));
        assertEquals("", resolver.getProperty(
                "spring.ai.mcp.client.stdio.connections.sentry.env.SENTRY_ACCESS_TOKEN"));
    }

    private PropertySourcesPropertyResolver resolver(String resourceName) throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load(resourceName, new ClassPathResource(resourceName))
                .forEach(sources::addLast);
        return new PropertySourcesPropertyResolver(sources);
    }
}
