package com.codepilot.core.application.tool;

import com.codepilot.core.domain.tool.ToolCall;
import com.codepilot.core.domain.tool.Tool;
import com.codepilot.core.domain.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ToolExecutor {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final ExecutorService DEFAULT_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("codepilot-tool-", 0).factory()
    );

    private final ToolRegistry toolRegistry;

    private final Duration timeout;

    private final ExecutorService executorService;

    private final ConcurrentHashMap<String, FutureTask<CachedToolResult>> cachedResults = new ConcurrentHashMap<>();

    public ToolExecutor(ToolRegistry toolRegistry) {
        this(toolRegistry, DEFAULT_TIMEOUT, DEFAULT_EXECUTOR);
    }

    public ToolExecutor(ToolRegistry toolRegistry, Duration timeout) {
        this(toolRegistry, timeout, DEFAULT_EXECUTOR);
    }

    public ToolExecutor(ToolRegistry toolRegistry, Duration timeout, ExecutorService executorService) {
        this.toolRegistry = toolRegistry;
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
        this.executorService = executorService == null ? DEFAULT_EXECUTOR : executorService;
    }

    public List<ToolResult> executeAll(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        ToolResult[] orderedResults = new ToolResult[toolCalls.size()];
        List<IndexedToolCall> readOnlyPartition = new ArrayList<>();

        for (int index = 0; index < toolCalls.size(); index++) {
            ToolCall toolCall = toolCalls.get(index);
            Tool tool = toolRegistry.getRequired(toolCall.toolName());
            IndexedToolCall indexedToolCall = new IndexedToolCall(index, tool, toolCall);
            if (tool.readOnly() && !tool.exclusive()) {
                readOnlyPartition.add(indexedToolCall);
                continue;
            }

            flushReadOnlyPartition(readOnlyPartition, orderedResults);
            orderedResults[index] = awaitResult(indexedToolCall, submitExecution(indexedToolCall));
        }

        flushReadOnlyPartition(readOnlyPartition, orderedResults);

        List<ToolResult> results = new ArrayList<>(orderedResults.length);
        for (ToolResult orderedResult : orderedResults) {
            results.add(orderedResult);
        }
        return List.copyOf(results);
    }

    private void flushReadOnlyPartition(List<IndexedToolCall> partition, ToolResult[] orderedResults) {
        if (partition.isEmpty()) {
            return;
        }

        Map<Integer, PendingExecution> pendingByIndex = new LinkedHashMap<>();
        for (IndexedToolCall indexedToolCall : partition) {
            pendingByIndex.put(indexedToolCall.index(), submitExecution(indexedToolCall));
        }
        for (IndexedToolCall indexedToolCall : partition) {
            orderedResults[indexedToolCall.index()] = awaitResult(indexedToolCall, pendingByIndex.get(indexedToolCall.index()));
        }
        partition.clear();
    }

    private PendingExecution submitExecution(IndexedToolCall indexedToolCall) {
        Tool tool = indexedToolCall.tool();
        ToolCall toolCall = indexedToolCall.toolCall();

        if (!tool.readOnly()) {
            FutureTask<CachedToolResult> task = new FutureTask<>(() -> executeTool(tool, toolCall));
            executorService.execute(task);
            return new PendingExecution(tool.name(), toolCall.signature(), task, false, false);
        }

        FutureTask<CachedToolResult> createdTask = new FutureTask<>(() -> executeTool(tool, toolCall));
        FutureTask<CachedToolResult> existingTask = cachedResults.putIfAbsent(toolCall.signature(), createdTask);
        boolean cacheHit = existingTask != null;
        FutureTask<CachedToolResult> task = cacheHit ? existingTask : createdTask;
        if (!cacheHit) {
            executorService.execute(task);
        }
        return new PendingExecution(tool.name(), toolCall.signature(), task, cacheHit, true);
    }

    private ToolResult awaitResult(IndexedToolCall indexedToolCall, PendingExecution pendingExecution) {
        ToolCall toolCall = indexedToolCall.toolCall();
        try {
            CachedToolResult cachedToolResult = pendingExecution.task()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (pendingExecution.cacheable() && !cachedToolResult.success()) {
                cachedResults.remove(pendingExecution.signature(), pendingExecution.task());
            }
            return cachedToolResult.toToolResult(toolCall.callId(), pendingExecution.cacheHit(), pendingExecution.toolName());
        } catch (TimeoutException timeoutException) {
            pendingExecution.task().cancel(true);
            if (pendingExecution.cacheable()) {
                cachedResults.remove(pendingExecution.signature(), pendingExecution.task());
            }
            return ToolResult.failure(
                    toolCall.callId(),
                    "Tool timed out after %d ms".formatted(timeout.toMillis()),
                    Map.of(
                            "toolName", pendingExecution.toolName(),
                            "timeoutMillis", timeout.toMillis(),
                            "cached", pendingExecution.cacheHit()
                    )
            );
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            if (pendingExecution.cacheable()) {
                cachedResults.remove(pendingExecution.signature(), pendingExecution.task());
            }
            return ToolResult.failure(
                    toolCall.callId(),
                    "Tool execution interrupted",
                    Map.of(
                            "toolName", pendingExecution.toolName(),
                            "cached", pendingExecution.cacheHit()
                    )
            );
        } catch (CancellationException | ExecutionException executionException) {
            if (pendingExecution.cacheable()) {
                cachedResults.remove(pendingExecution.signature(), pendingExecution.task());
            }
            Throwable cause = executionException instanceof ExecutionException && executionException.getCause() != null
                    ? executionException.getCause()
                    : executionException;
            return ToolResult.failure(
                    toolCall.callId(),
                    "Tool execution failed: " + cause.getMessage(),
                    Map.of(
                            "toolName", pendingExecution.toolName(),
                            "cached", pendingExecution.cacheHit()
                    )
            );
        }
    }

    private CachedToolResult executeTool(Tool tool, ToolCall toolCall) {
        ToolResult toolResult = tool.execute(toolCall);
        if (toolResult == null) {
            throw new IllegalStateException("Tool returned null result");
        }
        return new CachedToolResult(toolResult.success(), toolResult.output(), toolResult.metadata());
    }

    private record IndexedToolCall(
            int index,
            Tool tool,
            ToolCall toolCall
    ) {
    }

    private record PendingExecution(
            String toolName,
            String signature,
            FutureTask<CachedToolResult> task,
            boolean cacheHit,
            boolean cacheable
    ) {
    }

    private record CachedToolResult(
            boolean success,
            String output,
            Map<String, Object> metadata
    ) {

        private ToolResult toToolResult(String callId, boolean cacheHit, String toolName) {
            Map<String, Object> toolMetadata = metadata == null || metadata.isEmpty()
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(metadata);
            toolMetadata.put("toolName", toolName);
            toolMetadata.put("cached", cacheHit);
            return success
                    ? ToolResult.success(callId, output, toolMetadata)
                    : ToolResult.failure(callId, output, toolMetadata);
        }
    }
}
