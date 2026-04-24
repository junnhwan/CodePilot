package com.codepilot.eval;

import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.review.Severity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LintOnlyBaselineReviewer {

    private static final Pattern HUNK_HEADER_PATTERN =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
    private static final Pattern DIFF_HEADER_PATTERN =
            Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern SQL_PATTERN =
            Pattern.compile("(?i)\\b(select|insert|update|delete)\\b");
    private static final Pattern LOOP_PATTERN =
            Pattern.compile("(?i)\\b(for|while)\\s*\\(");
    private static final Pattern REPOSITORY_CALL_PATTERN =
            Pattern.compile("(?i)\\b\\w*repository\\w*\\s*\\.");
    private static final Pattern HARD_CODED_SECRET_PATTERN =
            Pattern.compile("(?i)\\b\\w*(token|secret|api[_-]?key|password)\\w*\\b\\s*=\\s*\"[^\"]{6,}\"");

    ReviewResult review(String sessionId, EvalScenario scenario) {
        List<AddedLine> addedLines = parseAddedLines(scenario);
        List<Finding> findings = new ArrayList<>();
        findings.addAll(sqlInjectionFindings(sessionId, addedLines));
        findings.addAll(repositoryLoopFindings(sessionId, addedLines));
        findings.addAll(hardcodedSecretFindings(sessionId, addedLines));
        findings.addAll(swallowedExceptionFindings(sessionId, addedLines));

        return new ReviewResult(
                sessionId,
                deduplicate(findings),
                false,
                Instant.now()
        );
    }

    private List<Finding> sqlInjectionFindings(String sessionId, List<AddedLine> addedLines) {
        List<Finding> findings = new ArrayList<>();
        for (AddedLine addedLine : addedLines) {
            String normalized = addedLine.content().toLowerCase(Locale.ROOT);
            if (!SQL_PATTERN.matcher(normalized).find()) {
                continue;
            }
            if (!addedLine.content().contains("+") || !addedLine.content().contains("\"")) {
                continue;
            }
            findings.add(reported(
                    sessionId,
                    findings.size() + 1,
                    "security",
                    Severity.HIGH,
                    0.97d,
                    addedLine,
                    "SQL injection risk",
                    "The diff builds SQL by concatenating runtime input into the query string.",
                    "Use parameterized queries instead of string-built SQL.",
                    "The added SQL line concatenates user-controlled data with '+'."
            ));
        }
        return List.copyOf(findings);
    }

    private List<Finding> repositoryLoopFindings(String sessionId, List<AddedLine> addedLines) {
        List<Finding> findings = new ArrayList<>();
        Map<String, List<AddedLine>> linesByFile = groupByFile(addedLines);
        for (List<AddedLine> fileLines : linesByFile.values()) {
            int braceDepth = 0;
            Integer loopDepth = null;
            boolean waitingForLoopBlock = false;
            for (AddedLine addedLine : fileLines) {
                String trimmed = addedLine.content().trim();
                int openBraces = count(addedLine.content(), '{');
                int closeBraces = count(addedLine.content(), '}');

                if (waitingForLoopBlock && openBraces > 0) {
                    loopDepth = braceDepth + openBraces;
                    waitingForLoopBlock = false;
                }
                if (LOOP_PATTERN.matcher(trimmed).find()) {
                    if (openBraces > 0) {
                        loopDepth = braceDepth + openBraces;
                    } else {
                        waitingForLoopBlock = true;
                    }
                }
                if (loopDepth != null && REPOSITORY_CALL_PATTERN.matcher(trimmed).find()) {
                    findings.add(reported(
                            sessionId,
                            findings.size() + 1,
                            "perf",
                            Severity.MEDIUM,
                            0.91d,
                            addedLine,
                            "Repository call inside loop",
                            "The diff introduces one repository lookup per loop iteration.",
                            "Batch or prefetch repository data outside the loop body.",
                            "The repository access is nested inside an added loop."
                    ));
                    loopDepth = null;
                    waitingForLoopBlock = false;
                }

                braceDepth += openBraces - closeBraces;
                if (loopDepth != null && braceDepth < loopDepth) {
                    loopDepth = null;
                }
            }
        }
        return List.copyOf(findings);
    }

    private List<Finding> hardcodedSecretFindings(String sessionId, List<AddedLine> addedLines) {
        List<Finding> findings = new ArrayList<>();
        for (AddedLine addedLine : addedLines) {
            if (!HARD_CODED_SECRET_PATTERN.matcher(addedLine.content()).find()) {
                continue;
            }
            findings.add(reported(
                    sessionId,
                    findings.size() + 1,
                    "security",
                    Severity.HIGH,
                    0.95d,
                    addedLine,
                    "Hardcoded API token",
                    "The diff introduces a hardcoded credential-like token in source code.",
                    "Load the token from configuration or a secret manager instead of embedding it in code.",
                    "A token/secret-like variable is assigned to a string literal in the added line."
            ));
        }
        return List.copyOf(findings);
    }

    private List<Finding> swallowedExceptionFindings(String sessionId, List<AddedLine> addedLines) {
        List<Finding> findings = new ArrayList<>();
        Map<String, List<AddedLine>> linesByFile = groupByFile(addedLines);
        for (List<AddedLine> fileLines : linesByFile.values()) {
            for (int index = 0; index < fileLines.size(); index++) {
                AddedLine catchLine = fileLines.get(index);
                if (!catchLine.content().contains("catch (")) {
                    continue;
                }
                boolean handled = false;
                for (int lookahead = index + 1; lookahead < fileLines.size(); lookahead++) {
                    String trimmed = fileLines.get(lookahead).content().trim();
                    if (trimmed.startsWith("}")) {
                        break;
                    }
                    if (trimmed.isBlank() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                        continue;
                    }
                    if (trimmed.contains("throw ")
                            || trimmed.contains("log.")
                            || trimmed.contains("logger.")
                            || trimmed.contains("System.err")
                            || trimmed.contains("return ")) {
                        handled = true;
                        break;
                    }
                }
                if (!handled) {
                    findings.add(reported(
                            sessionId,
                            findings.size() + 1,
                            "maintain",
                            Severity.MEDIUM,
                            0.88d,
                            catchLine,
                            "Exception swallowed",
                            "The catch block drops the exception without logging, rethrowing, or returning an error signal.",
                            "Log the exception, rethrow it, or convert it into an explicit failure path.",
                            "The added catch block contains no handling beyond comments or whitespace."
                    ));
                }
            }
        }
        return List.copyOf(findings);
    }

    private List<AddedLine> parseAddedLines(EvalScenario scenario) {
        List<AddedLine> addedLines = new ArrayList<>();
        String currentFile = null;
        int nextNewLine = 0;
        for (String diffLine : scenario.diffLines()) {
            Matcher diffHeader = DIFF_HEADER_PATTERN.matcher(diffLine);
            if (diffHeader.find()) {
                currentFile = diffHeader.group(2);
                continue;
            }
            if (diffLine.startsWith("+++ ")) {
                currentFile = normalizeDiffPath(diffLine.substring(4).trim());
                continue;
            }
            Matcher matcher = HUNK_HEADER_PATTERN.matcher(diffLine);
            if (matcher.find()) {
                nextNewLine = Integer.parseInt(matcher.group(1));
                continue;
            }
            if (currentFile == null) {
                continue;
            }
            if (diffLine.startsWith("+") && !diffLine.startsWith("+++")) {
                addedLines.add(new AddedLine(currentFile, nextNewLine, diffLine.substring(1)));
                nextNewLine++;
                continue;
            }
            if (diffLine.startsWith(" ")) {
                nextNewLine++;
            }
        }
        return List.copyOf(addedLines);
    }

    private Map<String, List<AddedLine>> groupByFile(List<AddedLine> addedLines) {
        Map<String, List<AddedLine>> linesByFile = new LinkedHashMap<>();
        for (AddedLine addedLine : addedLines) {
            linesByFile.computeIfAbsent(addedLine.filePath(), ignored -> new ArrayList<>()).add(addedLine);
        }
        return linesByFile;
    }

    private List<Finding> deduplicate(List<Finding> findings) {
        Map<String, Finding> unique = new LinkedHashMap<>();
        for (Finding finding : findings) {
            String key = finding.location().filePath()
                    + ":" + finding.location().startLine()
                    + ":" + finding.title();
            unique.putIfAbsent(key, finding);
        }
        return List.copyOf(unique.values());
    }

    private Finding reported(
            String sessionId,
            int sequence,
            String category,
            Severity severity,
            double confidence,
            AddedLine addedLine,
            String title,
            String description,
            String suggestion,
            String evidence
    ) {
        return Finding.reported(
                sessionId + "-lint-finding-" + sequence,
                sessionId + "-lint-task",
                category,
                severity,
                confidence,
                new Finding.CodeLocation(addedLine.filePath(), addedLine.lineNumber(), addedLine.lineNumber()),
                title,
                description,
                suggestion,
                List.of(evidence)
        );
    }

    private String normalizeDiffPath(String path) {
        String normalized = path;
        if (normalized.startsWith("a/") || normalized.startsWith("b/")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private int count(String text, char target) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == target) {
                count++;
            }
        }
        return count;
    }

    private record AddedLine(
            String filePath,
            int lineNumber,
            String content
    ) {
    }
}
