package com.codepilot.core.application.memory;

import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.domain.memory.GlobalKnowledgeEntry;
import com.codepilot.core.domain.plan.ReviewTask;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class GlobalKnowledgeService {

    private static final String DEFAULT_CATALOG_RESOURCE = "global-knowledge/security-and-perf.json";

    private static final Set<String> STOP_WORDS = Set.of(
            "project",
            "before",
            "after",
            "should",
            "must",
            "with",
            "from",
            "into",
            "java",
            "code"
    );

    private final List<GlobalKnowledgeEntry> catalog;

    private final TokenCounter tokenCounter;

    private final int maxEntries;

    public GlobalKnowledgeService() {
        this(loadDefaultCatalog(), new TokenCounter(), 3);
    }

    public GlobalKnowledgeService(List<GlobalKnowledgeEntry> catalog) {
        this(catalog, new TokenCounter(), 3);
    }

    public GlobalKnowledgeService(
            List<GlobalKnowledgeEntry> catalog,
            TokenCounter tokenCounter,
            int maxEntries
    ) {
        this.catalog = catalog == null ? List.of() : List.copyOf(catalog);
        this.tokenCounter = tokenCounter;
        this.maxEntries = Math.max(maxEntries, 1);
    }

    public List<GlobalKnowledgeEntry> recall(
            ReviewTask.TaskType taskType,
            Set<String> queryTokens,
            int tokenBudget
    ) {
        if (taskType == null || queryTokens == null || queryTokens.isEmpty() || tokenBudget <= 0) {
            return List.of();
        }
        return trimToBudget(
                catalog.stream()
                        .filter(entry -> entry.taskType() == taskType)
                        .map(entry -> new RankedEntry(entry, score(entry, queryTokens)))
                        .filter(entry -> entry.score() > 0.0d)
                        .sorted(RankedEntry.DESCENDING.thenComparing(entry -> entry.entry().entryId()))
                        .toList(),
                tokenBudget
        );
    }

    public List<GlobalKnowledgeEntry> recall(
            Set<String> queryTokens,
            int tokenBudget
    ) {
        if (queryTokens == null || queryTokens.isEmpty() || tokenBudget <= 0) {
            return List.of();
        }
        return trimToBudget(
                catalog.stream()
                        .map(entry -> new RankedEntry(entry, score(entry, queryTokens)))
                        .filter(entry -> entry.score() > 0.0d)
                        .sorted(RankedEntry.DESCENDING
                                .thenComparing((RankedEntry entry) -> entry.entry().taskType().name())
                                .thenComparing(entry -> entry.entry().entryId()))
                        .toList(),
                tokenBudget
        );
    }

    private List<GlobalKnowledgeEntry> trimToBudget(
            List<RankedEntry> rankedEntries,
            int tokenBudget
    ) {
        int remaining = Math.max(tokenBudget, 0);
        List<GlobalKnowledgeEntry> selected = new ArrayList<>();
        for (RankedEntry rankedEntry : rankedEntries) {
            if (selected.size() >= maxEntries) {
                break;
            }
            int entryTokens = Math.max(tokenCounter.countText(summary(rankedEntry.entry())), 1);
            if (entryTokens > remaining) {
                continue;
            }
            selected.add(rankedEntry.entry());
            remaining = Math.max(remaining - entryTokens, 0);
        }
        return List.copyOf(selected);
    }

    private double score(GlobalKnowledgeEntry entry, Set<String> queryTokens) {
        double triggerScore = overlapScore(queryTokens, Set.copyOf(normalizeTokens(entry.triggerTokens()))) * 4.0d;
        double titleScore = overlapScore(queryTokens, tokenize(entry.title())) * 2.0d;
        double guidanceScore = overlapScore(queryTokens, tokenize(entry.guidance())) * 1.25d;
        return triggerScore + titleScore + guidanceScore;
    }

    private double overlapScore(Set<String> queryTokens, Set<String> candidateTokens) {
        double score = 0.0d;
        for (String token : queryTokens) {
            if (candidateTokens.contains(token)) {
                score += 1.0d;
            }
        }
        return score;
    }

    private String summary(GlobalKnowledgeEntry entry) {
        return "%s %s %s".formatted(entry.taskType(), entry.title(), entry.guidance());
    }

    private List<String> normalizeTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String token : tokens) {
            normalized.addAll(tokenize(token));
        }
        return List.copyOf(normalized);
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normalized = text.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")
                .toLowerCase(Locale.ROOT);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("[^a-z0-9]+")) {
            if (token.length() < 3 || STOP_WORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return Set.copyOf(tokens);
    }

    private static List<GlobalKnowledgeEntry> loadDefaultCatalog() {
        try (InputStream stream = GlobalKnowledgeService.class.getClassLoader()
                .getResourceAsStream(DEFAULT_CATALOG_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing global knowledge catalog " + DEFAULT_CATALOG_RESOURCE);
            }
            return JsonMapper.builder()
                    .findAndAddModules()
                    .build()
                    .readValue(stream, new TypeReference<List<GlobalKnowledgeEntry>>() {
                    });
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to load global knowledge catalog " + DEFAULT_CATALOG_RESOURCE, error);
        }
    }

    private record RankedEntry(
            GlobalKnowledgeEntry entry,
            double score
    ) {
        private static final Comparator<RankedEntry> DESCENDING =
                Comparator.comparingDouble(RankedEntry::score).reversed();
    }
}
