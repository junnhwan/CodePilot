package com.codepilot.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

public final class CodePilotMcpServerFactory {

    private final McpJsonMapper jsonMapper;

    public CodePilotMcpServerFactory(McpJsonMapper jsonMapper) {
        if (jsonMapper == null) {
            throw new IllegalArgumentException("jsonMapper must not be null");
        }
        this.jsonMapper = jsonMapper;
    }

    public McpSyncServer create(List<McpServerFeatures.SyncToolSpecification> toolSpecifications) {
        return create(new StdioServerTransportProvider(jsonMapper), toolSpecifications);
    }

    public McpSyncServer create(
            StdioServerTransportProvider transportProvider,
            List<McpServerFeatures.SyncToolSpecification> toolSpecifications
    ) {
        if (transportProvider == null) {
            throw new IllegalArgumentException("transportProvider must not be null");
        }
        return McpServer.sync(transportProvider)
                .serverInfo("codepilot-mcp-server", "0.1.0-SNAPSHOT")
                .instructions("CodePilot MCP server exposes read-only review and project-memory tools.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(Boolean.TRUE).build())
                .tools(toolSpecifications == null ? List.of() : toolSpecifications)
                .build();
    }
}
