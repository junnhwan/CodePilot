package com.codepilot.core.application.memory;

import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.domain.context.DiffSummary;
import com.codepilot.core.domain.context.ImpactSet;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ReviewPattern;
import com.codepilot.core.domain.memory.TeamConvention;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MemoryService {

    private static final Set<String> STOP_WORDS = Set.of(
            "src",
            "main",
            "java",
            "test",
            "com",
            "org",
            "class",
            "public",
            "private",
            "protected",
            "static",
            "void",
            "return",
            "package",
            "import",
            "string",
            "int",
            "long",
            "null"
    );

    private final TokenCounter tokenCounter;

    private final int maxPatterns;

    private final int maxConventions;

    public MemoryService(TokenCounter tokenCounter) {
        this(tokenCounter, 3, 4);
    }

    public MemoryService(TokenCounter tokenCounter, int maxPatterns, int maxConventions) {
        this.tokenCounter = tokenCounter;
        this.maxPatterns = Math.max(maxPatterns, 1);
        this.maxConventions = Math.max(maxConventions, 1);
    }

    public ProjectMemory recall(
            ProjectMemory projectMemory,
            DiffSummary diffSummary,
            ImpactSet impactSet,
            String rawDiff,
            int tokenBudget
    ) {
        if (projectMemory == null) {
            throw new IllegalArgumentException("projectMemory must not be null");
        }
        if ((projectMemory.reviewPatterns().isEmpty() && projectMemory.teamConventions().isEmpty()) || tokenBudget <= 0) {
            return ProjectMemory.empty(projectMemory.projectId());
        }

        Set<String> queryTokens = buildQueryTokens(diffSummary, impactSet, rawDiff);
        if (queryTokens.isEmpty()) {
            return ProjectMemory.empty(projectMemory.projectId());
        }

        int patternBudget = Math.max((int) Math.floor(tokenBudget * 0.6d), 1);
        List<ReviewPattern> reviewPatterns = selectPatterns(projectMemory.reviewPatterns(), queryTokens, patternBudget);
        int remainingBudget = Math.max(tokenBudget - tokenCount(reviewPatterns), 1);
        List<TeamConvention> teamConventions = selectConventions(
                projectMemory.teamConventions(),
                queryTokens,
                remainingBudget
        );

        return new ProjectMemory(projectMemory.projectId(), reviewPatterns, teamConventions);
    }

    public Set<String> buildQueryTokens(
            DiffSummary diffSummary,
            ImpactSet impactSet,
            String rawDiff
    ) {
        return collectQueryTokens(diffSummary, impactSet, rawDiff);
    }

    private List<ReviewPattern> selectPatterns(
            List<ReviewPattern> reviewPatterns,
            Set<String> queryTokens,
            int tokenBudget
    ) {
        List<RankedEntry<ReviewPattern>> ranked = reviewPatterns.stream()
                .map(pattern -> new RankedEntry<>(pattern, reviewPatternScore(pattern, queryTokens)))
                .filter(entry -> entry.score() > 0.0d)
                .sorted(RankedEntry.<ReviewPattern>descending()
                        .thenComparing(entry -> entry.entry().patternId()))
                .toList();

        return trimToBudget(
                ranked,
                tokenBudget,
                maxPatterns,
                pattern -> "%s %s %s %s".formatted(
                        pattern.patternType(),
                        pattern.title(),
                        pattern.description(),
                        pattern.codeExample()
                )
        );
    }

    private List<TeamConvention> selectConventions(
            List<TeamConvention> teamConventions,
            Set<String> queryTokens,
            int tokenBudget
    ) {
        List<RankedEntry<TeamConvention>> ranked = teamConventions.stream()
                .map(convention -> new RankedEntry<>(convention, conventionScore(convention, queryTokens)))
                .filter(entry -> entry.score() > 0.0d)
                .sorted(RankedEntry.<TeamConvention>descending()
                        .thenComparing(entry -> entry.entry().conventionId()))
                .toList();

        return trimToBudget(
                ranked,
                tokenBudget,
                maxConventions,
                convention -> "%s %s %s %s".formatted(
                        convention.category(),
                        convention.rule(),
                        convention.exampleGood(),
                        convention.exampleBad()
                )
        );
    }

    private <T> List<T> trimToBudget(
            List<RankedEntry<T>> rankedEntries,
            int tokenBudget,
            int maxItems,
            java.util.function.Function<T, String> summary
    ) {
        int remaining = Math.max(tokenBudget, 0);
        List<T> selected = new ArrayList<>();
        for (RankedEntry<T> rankedEntry : rankedEntries) {
            if (selected.size() >= maxItems) {
                break;
            }
            int entryTokens = Math.max(tokenCounter.countText(summary.apply(rankedEntry.entry())), 1);
            if (!selected.isEmpty() && entryTokens > remaining) {
                continue;
            }
            selected.add(rankedEntry.entry());
            remaining = Math.max(remaining - entryTokens, 0);
        }
        return List.copyOf(selected);
    }

    private int tokenCount(List<ReviewPattern> reviewPatterns) {
        int count = 0;
        for (ReviewPattern reviewPattern : reviewPatterns) {
            count += tokenCounter.countText("%s %s %s".formatted(
                    reviewPattern.title(),
                    reviewPattern.description(),
                    reviewPattern.codeExample()
            ));
        }
        return count;
    }

    private double reviewPatternScore(ReviewPattern reviewPattern, Set<String> queryTokens) {
        double lexicalScore = lexicalScore(
                queryTokens,
                reviewPattern.title(),
                reviewPattern.description(),
                reviewPattern.codeExample()
        );
        if (lexicalScore <= 0.0d) {
            return 0.0d;
        }
        return lexicalScore + Math.min(reviewPattern.frequency(), 10) * 0.15d;
    }

    private double conventionScore(TeamConvention teamConvention, Set<String> queryTokens) {
        double lexicalScore = lexicalScore(
                queryTokens,
                teamConvention.rule(),
                teamConvention.exampleGood(),
                teamConvention.exampleBad()
        );
        if (lexicalScore <= 0.0d) {
            return 0.0d;
        }
        return lexicalScore + teamConvention.confidence() * 0.4d;
    }

    private double lexicalScore(Set<String> queryTokens, String... texts) {
        return weightedScore(queryTokens, texts);
    }

    private double weightedScore(Set<String> queryTokens, String... texts) {
        double[] weights = switch (texts.length) {
            case 3 -> new double[]{4.0d, 1.25d, 1.5d};
            default -> new double[]{3.0d, 1.25d, 1.25d};
        };
        double score = 0.0d;
        for (int i = 0; i < texts.length; i++) {
            double weight = i < weights.length ? weights[i] : 1.0d;
            score += overlapScore(queryTokens, tokenize(texts[i])) * weight;
        }
        return score;
    }

    private double overlapScore(Set<String> queryTokens, Set<String> fieldTokens) {
        double score = 0.0d;
        for (String token : queryTokens) {
            if (fieldTokens.contains(token)) {
                score += 1.0d;
            }
        }
        return score;
    }

    private Set<String> collectQueryTokens(
            DiffSummary diffSummary,
            ImpactSet impactSet,
            String rawDiff
    ) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (diffSummary != null) {
            for (DiffSummary.ChangedFile changedFile : diffSummary.changedFiles()) {
                tokens.addAll(tokenize(changedFile.path()));
                for (String touchedSymbol : changedFile.touchedSymbols()) {
                    tokens.addAll(tokenize(touchedSymbol));
                }
            }
        }
        if (impactSet != null) {
            for (String impactedFile : impactSet.impactedFiles()) {
                tokens.addAll(tokenize(impactedFile));
            }
            for (String impactedSymbol : impactSet.impactedSymbols()) {
                tokens.addAll(tokenize(impactedSymbol));
            }
            for (List<String> callChain : impactSet.callChains()) {
                for (String symbol : callChain) {
                    tokens.addAll(tokenize(symbol));
                }
            }
        }
        tokens.addAll(tokenize(rawDiff));
        return Set.copyOf(tokens);
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

    private record RankedEntry<T>(
            T entry,
            double score
    ) {
        private static <T> java.util.Comparator<RankedEntry<T>> descending() {
            return java.util.Comparator.comparingDouble(RankedEntry<T>::score).reversed();
        }
    }
}
