package com.example.agent.service.mcp;

import com.example.agent.entity.McpDiscoveredTool;
import com.example.agent.repository.McpConnectionRepository;
import com.example.agent.repository.McpDiscoveredToolRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpConnectionServiceTest {

    @Test
    void refusesToToggleAToolOwnedByAnotherUser() {
        McpConnectionRepository connectionRepository = mock(McpConnectionRepository.class);
        McpDiscoveredToolRepository toolRepository = mock(McpDiscoveredToolRepository.class);
        McpDiscoveredTool tool = new McpDiscoveredTool();
        tool.setId(11L);
        tool.setConnectionId(22L);
        when(toolRepository.findById(11L)).thenReturn(Optional.of(tool));
        when(connectionRepository.findByIdAndOwnerUserId(22L, 3L)).thenReturn(Optional.empty());
        McpConnectionService service = new McpConnectionService(
                connectionRepository,
                toolRepository,
                mock(McpCredentialVault.class),
                mock(McpConnectionRuntimeManager.class),
                new RemoteMcpUrlPolicy(true, true));
        ReflectionTestUtils.setField(service, "maxConnections", 10);

        assertThrows(IllegalArgumentException.class, () -> service.setToolEnabled(3L, 11L, false));
        verify(toolRepository, never()).save(tool);
    }
}
