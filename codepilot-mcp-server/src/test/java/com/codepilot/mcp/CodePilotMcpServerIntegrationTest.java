package com.codepilot.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodePilotMcpServerIntegrationTest {

    @TempDir
    Path repoRoot;

    @Test
    void exposesMinimalToolSetAndHandlesSearchAndReviewCalls() throws Exception {
        Path sourceFile = repoRoot.resolve("src/main/java/com/example/UserRepository.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.example;

                class UserRepository {
                    String findByName(String name) {
                        return jdbcTemplate.queryForObject("select * from users where name = '" + name + "'", String.class);
                    }
                }
                """);
        String rawDiff = """
                diff --git a/src/main/java/com/example/UserRepository.java b/src/main/java/com/example/UserRepository.java
                index 1111111..2222222 100644
                --- a/src/main/java/com/example/UserRepository.java
                +++ b/src/main/java/com/example/UserRepository.java
                @@ -2,3 +2,5 @@
                 class UserRepository {
                +    String findByName(String name) {
                +        return jdbcTemplate.queryForObject("select * from users where name = '" + name + "'", String.class);
                +    }
                 }
                """;

        try (RunningGitHubServer gitHubServer = RunningGitHubServer.start()) {
            try (McpSyncClient client = startClient()) {
                client.initialize();

                McpSchema.ListToolsResult tools = client.listTools();
                assertThat(tools.tools()).extracting(McpSchema.Tool::name)
                        .containsExactlyInAnyOrder("review_diff", "review_pr", "search_memory");

                McpSchema.CallToolResult memoryResult = client.callTool(McpSchema.CallToolRequest.builder()
                        .name("search_memory")
                        .arguments(Map.of(
                                "project_id", "project-alpha",
                                "query", "validation gateway"
                        ))
                        .build());
                assertThat(memoryResult.isError()).isFalse();
                assertThat(memoryResult.content().toString()).contains("Validation missing before repository call");

                McpSchema.CallToolResult reviewDiffResult = client.callTool(McpSchema.CallToolRequest.builder()
                        .name("review_diff")
                        .arguments(Map.of(
                                "repo_root", repoRoot.toString(),
                                "raw_diff", rawDiff,
                                "project_id", "project-alpha"
                        ))
                        .build());
                assertThat(reviewDiffResult.isError()).isFalse();
                assertThat(reviewDiffResult.content().toString()).contains("finding_count=1");

                McpSchema.CallToolResult reviewPrResult = client.callTool(McpSchema.CallToolRequest.builder()
                        .name("review_pr")
                        .arguments(Map.of(
                                "owner", "acme",
                                "repository", "repo",
                                "pr_number", 42,
                                "project_id", "project-alpha",
                                "api_base_url", gitHubServer.baseUrl()
                        ))
                        .build());
                assertThat(reviewPrResult.isError())
                        .withFailMessage(reviewPrResult.content().toString())
                        .isFalse();
                assertThat(reviewPrResult.content().toString()).contains("finding_count=1");

                McpSchema.CallToolResult invalidResult = client.callTool(McpSchema.CallToolRequest.builder()
                        .name("review_diff")
                        .arguments(Map.of(
                                "repo_root", repoRoot.toString(),
                                "raw_diff", " "
                        ))
                        .build());
                assertThat(invalidResult.isError()).isTrue();
                assertThat(invalidResult.content().toString()).contains("review_diff validation failed");
            }
        }
    }

    private McpSyncClient startClient() {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ServerParameters parameters = ServerParameters.builder(javaExecutable)
                .args(
                        "-Djdk.net.URLClassPath.disableClassPathURLCheck=true",
                        "-cp",
                        System.getProperty("java.class.path"),
                        "com.codepilot.mcp.testsupport.CodePilotMcpServerTestMain"
                )
                .build();
        StdioClientTransport transport = new StdioClientTransport(parameters, jsonMapper);
        transport.setStdErrorHandler(line -> {
        });
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(15))
                .initializationTimeout(Duration.ofSeconds(15))
                .build();
    }

    private static final class RunningGitHubServer implements AutoCloseable {

        private final com.sun.net.httpserver.HttpServer server;

        private RunningGitHubServer(com.sun.net.httpserver.HttpServer server) {
            this.server = server;
        }

        private static RunningGitHubServer start() throws IOException {
            com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/repos/acme/repo/pulls/42", exchange -> {
                String accept = exchange.getRequestHeaders().getFirst("Accept");
                byte[] body = (accept != null && accept.contains("application/vnd.github.v3.diff")
                        ? """
                        diff --git a/src/main/java/com/example/UserRepository.java b/src/main/java/com/example/UserRepository.java
                        @@ -1,1 +1,1 @@
                        -old
                        +new
                        """
                        : """
                        {
                          "head": {
                            "sha": "head-sha"
                          }
                        }
                        """).getBytes();
                exchange.getResponseHeaders().add("Content-Type",
                        accept != null && accept.contains("application/vnd.github.v3.diff")
                                ? "text/plain"
                                : "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/repos/acme/repo/pulls/42/files", exchange -> {
                byte[] body = """
                        [
                          {
                            "filename": "src/main/java/com/example/UserRepository.java",
                            "status": "modified"
                          }
                        ]
                        """.getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/repos/acme/repo/contents/src/main/java/com/example/UserRepository.java", exchange -> {
                byte[] body = """
                        {
                          "content": "cGFja2FnZSBjb20uZXhhbXBsZTsKY2xhc3MgVXNlclJlcG9zaXRvcnkgewogICAgU3RyaW5nIGZpbmRCeU5hbWUoU3RyaW5nIG5hbWUpIHsKICAgICAgICByZXR1cm4gamRiY1RlbXBsYXRlLnF1ZXJ5Rm9yT2JqZWN0KCJzZWxlY3QgKiBmcm9tIHVzZXJzIHdoZXJlIG5hbWUgPSAnIiArIG5hbWUgKyAiJyIsIFN0cmluZy5jbGFzcyk7CiAgICB9Cn0K",
                          "encoding": "base64"
                        }
                        """.getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return new RunningGitHubServer(server);
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
