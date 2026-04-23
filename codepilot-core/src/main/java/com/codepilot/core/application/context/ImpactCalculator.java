package com.codepilot.core.application.context;

import com.codepilot.core.domain.context.AstParser;
import com.codepilot.core.domain.context.CompilationStrategy;
import com.codepilot.core.domain.context.DiffSummary;
import com.codepilot.core.domain.context.ImpactSet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ImpactCalculator {

    public ImpactAnalysis calculate(
            Path repoRoot,
            DiffAnalyzer.DiffAnalysis diffAnalysis,
            Map<String, AstParser.ParsedSourceFile> parsedChangedFiles,
            AstParser astParser,
            CompilationStrategy strategy
    ) {
        Path normalizedRepoRoot = repoRoot.toAbsolutePath().normalize();
        Map<String, AstParser.ParsedSourceFile> safeParsedFiles = parsedChangedFiles == null ? Map.of() : Map.copyOf(parsedChangedFiles);

        List<DiffSummary.ChangedFile> changedFiles = new ArrayList<>();
        LinkedHashSet<String> impactedFiles = new LinkedHashSet<>();
        LinkedHashSet<String> impactedSymbols = new LinkedHashSet<>();
        List<List<String>> callChains = new ArrayList<>();
        LinkedHashMap<String, AstParser.ParsedSourceFile> dependencyFiles = new LinkedHashMap<>();

        for (DiffAnalyzer.FileDelta fileDelta : diffAnalysis.fileDeltas()) {
            impactedFiles.add(fileDelta.path());

            AstParser.ParsedSourceFile parsedChangedFile = safeParsedFiles.get(fileDelta.path());
            LinkedHashSet<String> touchedSymbols = new LinkedHashSet<>();
            LinkedHashSet<String> referencedTypes = new LinkedHashSet<>();

            if (parsedChangedFile != null) {
                for (DiffAnalyzer.ChangedHunk hunk : fileDelta.hunks()) {
                    touchedSymbols.addAll(parsedChangedFile.findTouchedSymbols(hunk.startLine(), hunk.endLine()));
                }
                referencedTypes.addAll(parsedChangedFile.referencedTypesForSymbols(touchedSymbols));
                impactedSymbols.addAll(touchedSymbols);
            }

            for (String dependencyPath : resolveDependencyFiles(normalizedRepoRoot, fileDelta.path(), referencedTypes, strategy.excludePatterns())) {
                if (dependencyPath.equals(fileDelta.path())) {
                    continue;
                }
                AstParser.ParsedSourceFile dependencyFile = dependencyFiles.computeIfAbsent(
                        dependencyPath,
                        ignored -> astParser.parse(normalizedRepoRoot, dependencyPath)
                );
                impactedFiles.add(dependencyPath);
                dependencyFile.types().forEach(type -> impactedSymbols.add(type.simpleName()));
                dependencyFile.allMethodSymbols().forEach(method -> impactedSymbols.add(method.symbolName()));

                String dependencyLabel = dependencyFile.types().isEmpty()
                        ? dependencyPath
                        : dependencyFile.types().getFirst().simpleName();
                if (touchedSymbols.isEmpty()) {
                    callChains.add(List.of(fileDelta.path(), dependencyLabel));
                } else {
                    touchedSymbols.forEach(symbol -> callChains.add(List.of(symbol, dependencyLabel)));
                }
            }

            changedFiles.add(new DiffSummary.ChangedFile(
                    fileDelta.path(),
                    fileDelta.changeType(),
                    fileDelta.additions(),
                    fileDelta.deletions(),
                    List.copyOf(touchedSymbols)
            ));
        }

        return new ImpactAnalysis(
                DiffSummary.of(changedFiles),
                new ImpactSet(impactedFiles, impactedSymbols, callChains),
                dependencyFiles
        );
    }

    private List<String> resolveDependencyFiles(
            Path repoRoot,
            String changedFilePath,
            Set<String> referencedTypes,
            List<String> excludePatterns
    ) {
        if (referencedTypes == null || referencedTypes.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String referencedType : referencedTypes) {
            if (referencedType == null || referencedType.isBlank() || referencedType.endsWith(".*")) {
                continue;
            }
            if (isJdkType(referencedType)) {
                continue;
            }
            for (String candidatePath : candidatePaths(changedFilePath, referencedType)) {
                Path absoluteCandidate = repoRoot.resolve(candidatePath).normalize();
                if (!absoluteCandidate.startsWith(repoRoot) || !Files.exists(absoluteCandidate)) {
                    continue;
                }
                if (isExcluded(candidatePath, excludePatterns)) {
                    continue;
                }
                resolved.add(candidatePath.replace('\\', '/'));
                break;
            }
        }
        return List.copyOf(resolved);
    }

    private List<String> candidatePaths(String changedFilePath, String referencedType) {
        String normalizedChangedFile = changedFilePath == null ? "" : changedFilePath.replace('\\', '/');
        String normalizedType = referencedType.replace('$', '.');
        String qualifiedCandidate = normalizedType.contains(".")
                ? normalizedType.replace('.', '/') + ".java"
                : normalizedType + ".java";

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String sourceRoot = sourceRootOf(normalizedChangedFile);
        if (sourceRoot != null && normalizedType.contains(".")) {
            candidates.add(sourceRoot + qualifiedCandidate);
        }
        if (normalizedType.contains(".")) {
            candidates.add("src/main/java/" + qualifiedCandidate);
            candidates.add("src/test/java/" + qualifiedCandidate);
        }
        if (!normalizedType.contains(".")) {
            int lastSlash = normalizedChangedFile.lastIndexOf('/');
            if (lastSlash >= 0) {
                candidates.add(normalizedChangedFile.substring(0, lastSlash + 1) + qualifiedCandidate);
            }
        }
        return List.copyOf(candidates);
    }

    private String sourceRootOf(String changedFilePath) {
        List<String> roots = List.of("src/main/java/", "src/test/java/");
        for (String root : roots) {
            int index = changedFilePath.indexOf(root);
            if (index >= 0) {
                return changedFilePath.substring(0, index + root.length());
            }
        }
        return null;
    }

    private boolean isExcluded(String path, List<String> excludePatterns) {
        if (excludePatterns == null || excludePatterns.isEmpty()) {
            return false;
        }
        String normalizedPath = path.replace('\\', '/');
        for (String pattern : excludePatterns) {
            String normalizedPattern = pattern.replace('\\', '/');
            if (normalizedPattern.startsWith("**/") && normalizedPattern.endsWith("/**")) {
                String token = normalizedPattern.substring(3, normalizedPattern.length() - 3);
                if (normalizedPath.contains("/" + token + "/")) {
                    return true;
                }
            }
            if (normalizedPattern.startsWith("**/") && normalizedPattern.endsWith(".java")) {
                String suffix = normalizedPattern.substring(3);
                if (normalizedPath.endsWith(suffix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isJdkType(String referencedType) {
        return referencedType.startsWith("java.")
                || referencedType.startsWith("javax.")
                || referencedType.equals("String")
                || referencedType.equals("Integer")
                || referencedType.equals("Long")
                || referencedType.equals("Boolean")
                || referencedType.equals("Double")
                || referencedType.equals("Float")
                || referencedType.equals("Short")
                || referencedType.equals("Byte")
                || referencedType.equals("Character");
    }

    public record ImpactAnalysis(
            DiffSummary diffSummary,
            ImpactSet impactSet,
            Map<String, AstParser.ParsedSourceFile> dependencyFiles
    ) {

        public ImpactAnalysis {
            dependencyFiles = dependencyFiles == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(dependencyFiles));
        }
    }
}
