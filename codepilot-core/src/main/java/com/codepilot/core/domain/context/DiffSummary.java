package com.codepilot.core.domain.context;

import com.codepilot.core.domain.DomainRuleViolationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record DiffSummary(
        List<ChangedFile> changedFiles,
        int totalAdditions,
        int totalDeletions
) {

    public DiffSummary {
        changedFiles = changedFiles == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(changedFiles));
        if (totalAdditions < 0 || totalDeletions < 0) {
            throw new DomainRuleViolationException("DiffSummary additions and deletions must not be negative");
        }
    }

    public static DiffSummary of(List<ChangedFile> changedFiles) {
        int additions = changedFiles == null ? 0 : changedFiles.stream().mapToInt(ChangedFile::additions).sum();
        int deletions = changedFiles == null ? 0 : changedFiles.stream().mapToInt(ChangedFile::deletions).sum();
        return new DiffSummary(changedFiles, additions, deletions);
    }

    public int changedFileCount() {
        return changedFiles.size();
    }

    public int totalChangedLines() {
        return totalAdditions + totalDeletions;
    }

    public record ChangedFile(
            String path,
            ChangeType changeType,
            int additions,
            int deletions,
            List<String> touchedSymbols
    ) {

        public ChangedFile {
            path = requireText(path, "path");
            if (changeType == null) {
                throw new DomainRuleViolationException("DiffSummary.ChangedFile[%s] changeType must not be null"
                        .formatted(path));
            }
            if (additions < 0 || deletions < 0) {
                throw new DomainRuleViolationException("DiffSummary.ChangedFile[%s] additions and deletions must not be negative"
                        .formatted(path));
            }
            touchedSymbols = touchedSymbols == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(touchedSymbols));
        }
    }

    public enum ChangeType {
        ADDED,
        MODIFIED,
        DELETED,
        RENAMED
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
