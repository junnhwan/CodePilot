package com.codepilot.core.application.tool;

import com.codepilot.core.domain.tool.ToolCall;
import com.codepilot.core.domain.tool.Tool;
import com.codepilot.core.domain.tool.ToolResult;
import com.codepilot.core.infrastructure.tool.AstParseTool;
import com.codepilot.core.infrastructure.tool.ReadFileTool;
import com.codepilot.core.infrastructure.tool.SearchPatternTool;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutorTest {

    @TempDir
    Path repoRoot;

    @Test
    void executesRegisteredReadOnlyToolsAgainstRepository() throws IOException {
        Path sourceFile = repoRoot.resolve("src/main/java/com/example/DemoRepository.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.example;

                class DemoRepository {
                    String findById(String id) {
                        return jdbcTemplate.queryForObject("select * from demo where id = ?", String.class, id);
                    }
                }
                """);

        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new ReadFileTool(repoRoot),
                new SearchPatternTool(repoRoot),
                new AstParseTool(repoRoot, JsonMapper.builder().findAndAddModules().build())
        ));
        ToolExecutor toolExecutor = new ToolExecutor(toolRegistry);

        List<ToolResult> results = toolExecutor.executeAll(List.of(
                new ToolCall("call-read", "read_file", Map.of("file_path", "src/main/java/com/example/DemoRepository.java")),
                new ToolCall("call-search", "search_pattern", Map.of("pattern", "jdbcTemplate\\.queryForObject")),
                new ToolCall("call-ast", "ast_parse", Map.of("file_path", "src/main/java/com/example/DemoRepository.java"))
        ));

        assertThat(results).hasSize(3);
        assertThat(results).allMatch(ToolResult::success);
        assertThat(results.get(0).output()).contains("class DemoRepository");
        assertThat(results.get(1).output()).contains("DemoRepository.java:5");
        assertThat(results.get(2).output()).contains("findById");
    }

    @Test
    void executesReadOnlyPartitionInParallel() {
        ExecutionTracker tracker = new ExecutionTracker();
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new TrackingTool("read_alpha", true, false, tracker, 200),
                new TrackingTool("read_beta", true, false, tracker, 200)
        ));
        ToolExecutor toolExecutor = new ToolExecutor(toolRegistry, Duration.ofSeconds(2));

        List<ToolResult> results = toolExecutor.executeAll(List.of(
                new ToolCall("call-alpha", "read_alpha", Map.of("target", "alpha")),
                new ToolCall("call-beta", "read_beta", Map.of("target", "beta"))
        ));

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(ToolResult::success);
        assertThat(tracker.maxConcurrent()).isGreaterThan(1);
        assertThat(results).extracting(ToolResult::callId)
                .containsExactly("call-alpha", "call-beta");
    }

    @Test
    void keepsExclusivePartitionSerializedAgainstReadOnlyCalls() {
        ExecutionTracker tracker = new ExecutionTracker();
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new TrackingTool("read_alpha", true, false, tracker, 200),
                new TrackingTool("exclusive_gamma", false, true, tracker, 50),
                new TrackingTool("read_beta", true, false, tracker, 200)
        ));
        ToolExecutor toolExecutor = new ToolExecutor(toolRegistry, Duration.ofSeconds(2));

        List<ToolResult> results = toolExecutor.executeAll(List.of(
                new ToolCall("call-alpha", "read_alpha", Map.of("target", "alpha")),
                new ToolCall("call-gamma", "exclusive_gamma", Map.of("target", "gamma")),
                new ToolCall("call-beta", "read_beta", Map.of("target", "beta"))
        ));

        assertThat(results).hasSize(3);
        assertThat(results).allMatch(ToolResult::success);
        assertThat(tracker.exclusiveObservedOverlap()).isFalse();
    }

    @Test
    void reusesCachedToolResultForSameSignatureAcrossCalls() {
        CountingTool countingTool = new CountingTool();
        ToolRegistry toolRegistry = new ToolRegistry(List.of(countingTool));
        ToolExecutor toolExecutor = new ToolExecutor(toolRegistry, Duration.ofSeconds(2));

        List<ToolResult> firstBatch = toolExecutor.executeAll(List.of(
                new ToolCall("call-1", "counting_tool", Map.of("target", "alpha")),
                new ToolCall("call-2", "counting_tool", Map.of("target", "alpha"))
        ));
        List<ToolResult> secondBatch = toolExecutor.executeAll(List.of(
                new ToolCall("call-3", "counting_tool", Map.of("target", "alpha"))
        ));

        assertThat(countingTool.invocationCount()).isEqualTo(1);
        assertThat(firstBatch).extracting(ToolResult::callId).containsExactly("call-1", "call-2");
        assertThat(secondBatch).singleElement()
                .extracting(ToolResult::callId)
                .isEqualTo("call-3");
        assertThat(firstBatch.get(0).output()).isEqualTo("computed-alpha");
        assertThat(firstBatch.get(1).output()).isEqualTo("computed-alpha");
        assertThat(secondBatch.get(0).output()).isEqualTo("computed-alpha");
    }

    @Test
    void returnsFailureResultWhenToolExecutionTimesOut() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(new SlowTool()));
        ToolExecutor toolExecutor = new ToolExecutor(toolRegistry, Duration.ofMillis(50));

        List<ToolResult> results = toolExecutor.executeAll(List.of(
                new ToolCall("call-timeout", "slow_tool", Map.of("target", "alpha"))
        ));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.output()).contains("timed out");
            assertThat(result.metadata()).containsEntry("toolName", "slow_tool");
        });
    }

    private static final class ExecutionTracker {

        private final AtomicInteger active = new AtomicInteger();

        private final AtomicInteger maxConcurrent = new AtomicInteger();

        private final AtomicBoolean exclusiveObservedOverlap = new AtomicBoolean(false);

        void enter(boolean exclusive) {
            int concurrent = active.incrementAndGet();
            maxConcurrent.accumulateAndGet(concurrent, Math::max);
            if (exclusive && concurrent > 1) {
                exclusiveObservedOverlap.set(true);
            }
        }

        void leave() {
            active.decrementAndGet();
        }

        int maxConcurrent() {
            return maxConcurrent.get();
        }

        boolean exclusiveObservedOverlap() {
            return exclusiveObservedOverlap.get();
        }
    }

    private static final class TrackingTool implements Tool {

        private final String name;

        private final boolean readOnly;

        private final boolean exclusive;

        private final ExecutionTracker tracker;

        private final long sleepMillis;

        private TrackingTool(String name, boolean readOnly, boolean exclusive, ExecutionTracker tracker, long sleepMillis) {
            this.name = name;
            this.readOnly = readOnly;
            this.exclusive = exclusive;
            this.tracker = tracker;
            this.sleepMillis = sleepMillis;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "Tracking test tool";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of();
        }

        @Override
        public boolean readOnly() {
            return readOnly;
        }

        @Override
        public boolean exclusive() {
            return exclusive;
        }

        @Override
        public ToolResult execute(ToolCall call) {
            tracker.enter(exclusive);
            try {
                Thread.sleep(sleepMillis);
                return ToolResult.success(call.callId(), name + "-" + call.arguments().get("target"), Map.of());
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return ToolResult.failure(call.callId(), name + " interrupted", Map.of());
            } finally {
                tracker.leave();
            }
        }
    }

    private static final class CountingTool implements Tool {

        private final AtomicInteger invocationCount = new AtomicInteger();

        @Override
        public String name() {
            return "counting_tool";
        }

        @Override
        public String description() {
            return "Counting test tool";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of();
        }

        @Override
        public boolean readOnly() {
            return true;
        }

        @Override
        public boolean exclusive() {
            return false;
        }

        @Override
        public ToolResult execute(ToolCall call) {
            invocationCount.incrementAndGet();
            return ToolResult.success(call.callId(), "computed-" + call.arguments().get("target"), Map.of());
        }

        int invocationCount() {
            return invocationCount.get();
        }
    }

    private static final class SlowTool implements Tool {

        @Override
        public String name() {
            return "slow_tool";
        }

        @Override
        public String description() {
            return "Slow test tool";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of();
        }

        @Override
        public boolean readOnly() {
            return true;
        }

        @Override
        public boolean exclusive() {
            return false;
        }

        @Override
        public ToolResult execute(ToolCall call) {
            try {
                Thread.sleep(500);
                return ToolResult.success(call.callId(), "slow-result", Map.of());
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return ToolResult.failure(call.callId(), "slow tool interrupted", Map.of());
            }
        }
    }
}
