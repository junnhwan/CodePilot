package com.codepilot.core.application.memory;

import com.codepilot.core.domain.memory.MemoryPlan;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.memory.ReviewPattern;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.review.Severity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class DreamService {

    private static final double MIN_CONFIDENCE = 0.90d;

    private final ProjectMemoryRepository repository;

    public DreamService(ProjectMemoryRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("repository must not be null");
        }
        this.repository = repository;
    }

    public ProjectMemory dream(String projectId, ReviewResult reviewResult) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (reviewResult == null) {
            throw new IllegalArgumentException("reviewResult must not be null");
        }

        ProjectMemory current = repository.load(projectId);
        MemoryPlan memoryPlan = analyze(projectId, reviewResult, current);
        if (memoryPlan.isEmpty()) {
            return current;
        }

        ProjectMemory updated = apply(memoryPlan, current);
        if (updated.equals(current)) {
            return current;
        }

        try {
            repository.save(updated);
            return updated;
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Failed to persist dream memory for project %s in session %s"
                            .formatted(projectId, reviewResult.sessionId()),
                    exception
            );
        }
    }

    MemoryPlan analyze(String projectId, ReviewResult reviewResult, ProjectMemory current) {
        Map<String, ReviewPattern> existingPatterns = new LinkedHashMap<>();
        current.reviewPatterns().forEach(pattern -> existingPatterns.put(pattern.patternId(), pattern));

        List<ReviewPattern> patternsToAdd = new ArrayList<>();
        for (Finding finding : reviewResult.findings()) {
            if (!isWorthRemembering(finding)) {
                continue;
            }

            String patternId = patternId(finding);
            if (patternsToAdd.stream().anyMatch(pattern -> pattern.patternId().equals(patternId))) {
                continue;
            }

            ReviewPattern existing = existingPatterns.get(patternId);
            if (existing != null && !existing.lastSeenAt().isBefore(reviewResult.generatedAt())) {
                continue;
            }

            patternsToAdd.add(existing == null
                    ? new ReviewPattern(
                    patternId,
                    projectId,
                    patternType(finding),
                    finding.title(),
                    finding.description(),
                    codeExample(finding),
                    1,
                    reviewResult.generatedAt()
            )
                    : existing.seenAgain(reviewResult.generatedAt()));
        }

        return new MemoryPlan(projectId, patternsToAdd, List.of());
    }

    ProjectMemory apply(MemoryPlan memoryPlan, ProjectMemory current) {
        ProjectMemory updated = current;
        for (ReviewPattern reviewPattern : memoryPlan.patternsToAdd()) {
            updated = updated.addPattern(reviewPattern);
        }
        return updated;
    }

    private boolean isWorthRemembering(Finding finding) {
        return finding.confidence() >= MIN_CONFIDENCE
                && finding.severity() != Severity.INFO
                && !finding.evidence().isEmpty()
                && finding.status() != Finding.Status.DISMISSED;
    }

    private ReviewPattern.PatternType patternType(Finding finding) {
        return switch (finding.category().trim().toLowerCase(Locale.ROOT)) {
            case "security" -> ReviewPattern.PatternType.SECURITY_PATTERN;
            case "perf", "performance" -> ReviewPattern.PatternType.PERF_PATTERN;
            case "style" -> ReviewPattern.PatternType.CONVENTION;
            default -> ReviewPattern.PatternType.BUG_PATTERN;
        };
    }

    private String codeExample(Finding finding) {
        if (!finding.evidence().isEmpty()) {
            return String.join("\n", finding.evidence());
        }
        return finding.suggestion();
    }

    private String patternId(Finding finding) {
        String fingerprint = "%s|%s|%s".formatted(
                finding.category().trim().toLowerCase(Locale.ROOT),
                finding.title().trim().toLowerCase(Locale.ROOT),
                finding.suggestion().trim().toLowerCase(Locale.ROOT)
        );
        return "dream-pattern-" + UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8));
    }
}
