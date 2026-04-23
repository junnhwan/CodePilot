package com.codepilot.core.application.context;

import com.codepilot.core.domain.context.DiffSummary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DiffAnalyzer {

    private static final Pattern HUNK_PATTERN = Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    public DiffAnalysis analyze(String rawDiff) {
        if (rawDiff == null || rawDiff.isBlank()) {
            return new DiffAnalysis("", List.of());
        }

        List<FileDelta> fileDeltas = new ArrayList<>();
        String[] lines = rawDiff.split("\\R", -1);

        String currentPath = null;
        DiffSummary.ChangeType currentType = DiffSummary.ChangeType.MODIFIED;
        int additions = 0;
        int deletions = 0;
        List<ChangedHunk> hunks = new ArrayList<>();
        int hunkStartLine = -1;
        int hunkLength = 0;
        StringBuilder hunkContent = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                if (currentPath != null) {
                    flushHunk(hunks, hunkStartLine, hunkLength, hunkContent);
                    fileDeltas.add(new FileDelta(currentPath, currentType, additions, deletions, hunks));
                }
                currentPath = extractPath(line);
                currentType = DiffSummary.ChangeType.MODIFIED;
                additions = 0;
                deletions = 0;
                hunks = new ArrayList<>();
                hunkStartLine = -1;
                hunkLength = 0;
                hunkContent = new StringBuilder();
                continue;
            }
            if (line.startsWith("new file mode")) {
                currentType = DiffSummary.ChangeType.ADDED;
                continue;
            }
            if (line.startsWith("deleted file mode")) {
                currentType = DiffSummary.ChangeType.DELETED;
                continue;
            }
            if (line.startsWith("rename to ")) {
                currentType = DiffSummary.ChangeType.RENAMED;
                currentPath = line.substring("rename to ".length()).trim();
                continue;
            }
            if (line.startsWith("@@ ")) {
                flushHunk(hunks, hunkStartLine, hunkLength, hunkContent);
                Matcher matcher = HUNK_PATTERN.matcher(line);
                if (matcher.find()) {
                    hunkStartLine = Integer.parseInt(matcher.group(1));
                    hunkLength = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
                } else {
                    hunkStartLine = 1;
                    hunkLength = 1;
                }
                hunkContent = new StringBuilder().append(line).append(System.lineSeparator());
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                additions++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
            if (hunkStartLine >= 0) {
                hunkContent.append(line).append(System.lineSeparator());
            }
        }

        if (currentPath != null) {
            flushHunk(hunks, hunkStartLine, hunkLength, hunkContent);
            fileDeltas.add(new FileDelta(currentPath, currentType, additions, deletions, hunks));
        }

        return new DiffAnalysis(rawDiff, fileDeltas);
    }

    private void flushHunk(List<ChangedHunk> hunks, int startLine, int length, StringBuilder content) {
        if (content == null || content.isEmpty() || startLine < 0) {
            return;
        }
        int endLine = length <= 0 ? startLine : startLine + Math.max(length - 1, 0);
        hunks.add(new ChangedHunk(startLine, endLine, content.toString().trim()));
    }

    private String extractPath(String diffHeader) {
        String[] parts = diffHeader.split(" ");
        if (parts.length < 4) {
            return null;
        }
        String candidate = parts[3];
        return candidate.startsWith("b/") ? candidate.substring(2) : candidate;
    }

    public record DiffAnalysis(
            String rawDiff,
            List<FileDelta> fileDeltas
    ) {

        public DiffAnalysis {
            rawDiff = rawDiff == null ? "" : rawDiff;
            fileDeltas = fileDeltas == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(fileDeltas));
        }
    }

    public record FileDelta(
            String path,
            DiffSummary.ChangeType changeType,
            int additions,
            int deletions,
            List<ChangedHunk> hunks
    ) {

        public FileDelta {
            hunks = hunks == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(hunks));
        }
    }

    public record ChangedHunk(
            int startLine,
            int endLine,
            String content
    ) {

        public ChangedHunk {
            content = content == null ? "" : content;
        }
    }
}
