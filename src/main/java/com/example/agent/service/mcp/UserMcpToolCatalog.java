package com.example.agent.service.mcp;

import com.example.agent.entity.McpConnection;
import com.example.agent.entity.McpDiscoveredTool;
import com.example.agent.repository.McpConnectionRepository;
import com.example.agent.repository.McpDiscoveredToolRepository;
import com.example.agent.tool.react.McpDiscoveredReactTool;
import com.example.agent.tool.react.ReactTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserMcpToolCatalog {

    private final McpConnectionRepository connectionRepository;
    private final McpDiscoveredToolRepository toolRepository;
    private final McpConnectionRuntimeManager runtimeManager;

    @Transactional(readOnly = true)
    public List<ReactTool> listEnabledTools(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<Long> connectionIds = connectionRepository.findByOwnerUserIdAndEnabledTrueOrderByIdAsc(userId).stream()
                .map(McpConnection::getId)
                .toList();
        if (connectionIds.isEmpty()) {
            return List.of();
        }
        return toolRepository.findByConnectionIdInAndEnabledTrueOrderByExposedNameAsc(connectionIds).stream()
                .map(tool -> (ReactTool) new McpDiscoveredReactTool(tool, runtimeManager))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<McpDiscoveredTool> listTools(Long connectionId) {
        return toolRepository.findByConnectionIdOrderByExposedNameAsc(connectionId);
    }
}
