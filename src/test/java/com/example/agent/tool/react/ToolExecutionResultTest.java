package com.example.agent.tool.react;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionResultTest {

    @Test
    void observationNeverExposesServerArtifactPath() {
        String serverPath = "D:\\private\\agent-workspace\\1\\10\\report.pdf";

        String observation = ToolExecutionResult.success("PDF generated: report.pdf", serverPath).toObservation();

        assertFalse(observation.contains("D:\\private"));
        assertTrue(observation.contains("report.pdf"));
    }
}
