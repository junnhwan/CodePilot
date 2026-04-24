package com.codepilot.core.infrastructure.tool;

import com.codepilot.core.domain.context.AstParser;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.body.VariableDeclarator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class JavaRepositoryAstIndex {

    private static final Set<String> BUILTIN_TYPES = Set.of(
            "byte", "short", "int", "long", "float", "double", "boolean", "char", "void",
            "String", "Integer", "Long", "Boolean", "Double", "Float", "Short", "Byte", "Character"
    );

    private final Path repoRoot;

    private final Map<String, AstParser.ParsedSourceFile> parsedFiles;

    private final Map<String, MethodLocation> methodsBySymbol;

    private final Map<String, List<String>> methodSymbolsByName;

    private final Map<String, String> qualifiedTypesBySimpleName;

    private final Map<String, List<ReferenceHit>> referencesBySymbol;

    private final Map<String, List<String>> outgoingEdges;

    private final Map<String, List<String>> incomingEdges;

    private JavaRepositoryAstIndex(
            Path repoRoot,
            Map<String, AstParser.ParsedSourceFile> parsedFiles,
            Map<String, MethodLocation> methodsBySymbol,
            Map<String, List<String>> methodSymbolsByName,
            Map<String, String> qualifiedTypesBySimpleName,
            Map<String, List<ReferenceHit>> referencesBySymbol,
            Map<String, List<String>> outgoingEdges,
            Map<String, List<String>> incomingEdges
    ) {
        this.repoRoot = repoRoot;
        this.parsedFiles = parsedFiles;
        this.methodsBySymbol = methodsBySymbol;
        this.methodSymbolsByName = methodSymbolsByName;
        this.qualifiedTypesBySimpleName = qualifiedTypesBySimpleName;
        this.referencesBySymbol = referencesBySymbol;
        this.outgoingEdges = outgoingEdges;
        this.incomingEdges = incomingEdges;
    }

    static JavaRepositoryAstIndex build(Path repoRoot, AstParser astParser) {
        Path normalizedRepoRoot = RepositoryToolSupport.normalizeRepoRoot(repoRoot);
        LinkedHashMap<String, AstParser.ParsedSourceFile> parsedFiles = new LinkedHashMap<>();
        LinkedHashMap<String, MethodLocation> methodsBySymbol = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> methodSymbolsByName = new LinkedHashMap<>();
        LinkedHashMap<String, String> qualifiedTypesBySimpleName = new LinkedHashMap<>();
        LinkedHashMap<String, List<ReferenceHit>> referencesBySymbol = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> outgoingEdges = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> incomingEdges = new LinkedHashMap<>();

        for (Path sourceFile : RepositoryToolSupport.listJavaSourceFiles(normalizedRepoRoot)) {
            String relativePath = RepositoryToolSupport.relativePath(normalizedRepoRoot, sourceFile);
            AstParser.ParsedSourceFile parsedSourceFile = astParser.parse(normalizedRepoRoot, relativePath);
            parsedFiles.put(relativePath, parsedSourceFile);

            for (AstParser.TypeSymbol typeSymbol : parsedSourceFile.types()) {
                qualifiedTypesBySimpleName.putIfAbsent(typeSymbol.simpleName(), typeSymbol.qualifiedName());
                addTypeReference(referencesBySymbol, typeSymbol.qualifiedName(), new ReferenceHit(
                        relativePath,
                        typeSymbol.startLine(),
                        typeSymbol.simpleName(),
                        "type reference"
                ));
                for (String referencedType : typeSymbol.referencedTypes()) {
                    addTypeReference(referencesBySymbol, referencedType, new ReferenceHit(
                            relativePath,
                            typeSymbol.startLine(),
                            typeSymbol.simpleName(),
                            "type reference"
                    ));
                }
                for (AstParser.MethodSymbol methodSymbol : typeSymbol.methods()) {
                    methodsBySymbol.put(methodSymbol.symbolName(), new MethodLocation(relativePath, methodSymbol.startLine()));
                    methodSymbolsByName.computeIfAbsent(methodSymbol.methodName(), ignored -> new ArrayList<>()).add(methodSymbol.symbolName());
                    for (String referencedType : methodSymbol.referencedTypes()) {
                        addTypeReference(referencesBySymbol, referencedType, new ReferenceHit(
                                relativePath,
                                methodSymbol.startLine(),
                                methodSymbol.symbolName(),
                                "type reference"
                        ));
                    }
                }
            }
        }

        JavaRepositoryAstIndex workingIndex = new JavaRepositoryAstIndex(
                normalizedRepoRoot,
                parsedFiles,
                methodsBySymbol,
                methodSymbolsByName,
                qualifiedTypesBySimpleName,
                referencesBySymbol,
                outgoingEdges,
                incomingEdges
        );

        JavaParser javaParser = new JavaParser();
        for (Map.Entry<String, AstParser.ParsedSourceFile> entry : parsedFiles.entrySet()) {
            Path sourceFile = normalizedRepoRoot.resolve(entry.getKey()).normalize();
            ParseResult<CompilationUnit> parseResult;
            try {
                parseResult = javaParser.parse(sourceFile);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to build AST index for " + entry.getKey(), exception);
            }
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                continue;
            }

            CompilationUnit unit = parseResult.getResult().orElseThrow();
            for (TypeDeclaration<?> typeDeclaration : unit.getTypes()) {
                if (!(typeDeclaration instanceof ClassOrInterfaceDeclaration)
                        && !(typeDeclaration instanceof EnumDeclaration)
                        && !(typeDeclaration instanceof RecordDeclaration)) {
                    continue;
                }
                workingIndex.analyzeType(
                        entry.getKey(),
                        entry.getValue(),
                        typeDeclaration,
                        methodsBySymbol,
                        methodSymbolsByName,
                        qualifiedTypesBySimpleName,
                        referencesBySymbol,
                        outgoingEdges,
                        incomingEdges
                );
            }
        }

        return new JavaRepositoryAstIndex(
                normalizedRepoRoot,
                Collections.unmodifiableMap(parsedFiles),
                Collections.unmodifiableMap(methodsBySymbol),
                toImmutableMap(methodSymbolsByName),
                Collections.unmodifiableMap(qualifiedTypesBySimpleName),
                toImmutableReferenceMap(referencesBySymbol),
                toImmutableMap(outgoingEdges),
                toImmutableMap(incomingEdges)
        );
    }

    List<ReferenceHit> findReferences(String rawSymbol, int maxResults) {
        String methodSymbol = resolveMethodSymbol(rawSymbol);
        if (methodSymbol != null) {
            return referencesBySymbol.getOrDefault(methodSymbol, List.of()).stream()
                    .sorted(Comparator.comparing(ReferenceHit::filePath).thenComparingInt(ReferenceHit::line))
                    .limit(maxResults)
                    .toList();
        }

        LinkedHashSet<ReferenceHit> hits = new LinkedHashSet<>();
        for (String candidate : resolveTypeCandidates(rawSymbol)) {
            hits.addAll(referencesBySymbol.getOrDefault(candidate, List.of()));
        }
        return hits.stream()
                .sorted(Comparator.comparing(ReferenceHit::filePath).thenComparingInt(ReferenceHit::line))
                .limit(maxResults)
                .toList();
    }

    List<String> findCallChains(String rawSymbol, AstGetCallChainTool.Direction direction, int maxDepth, int maxResults) {
        String methodSymbol = resolveMethodSymbol(rawSymbol);
        if (methodSymbol == null) {
            return List.of();
        }

        LinkedHashSet<String> chains = new LinkedHashSet<>();
        if (direction == AstGetCallChainTool.Direction.DOWNSTREAM || direction == AstGetCallChainTool.Direction.BOTH) {
            collectChains(methodSymbol, outgoingEdges, maxDepth, maxResults, false, chains);
        }
        if (direction == AstGetCallChainTool.Direction.UPSTREAM || direction == AstGetCallChainTool.Direction.BOTH) {
            collectChains(methodSymbol, incomingEdges, maxDepth, maxResults, true, chains);
        }
        return chains.stream().limit(maxResults).toList();
    }

    private void collectChains(
            String start,
            Map<String, List<String>> adjacency,
            int maxDepth,
            int maxResults,
            boolean reversePath,
            Set<String> chains
    ) {
        Deque<String> path = new ArrayDeque<>();
        path.addLast(start);
        dfs(start, adjacency, maxDepth, maxResults, reversePath, path, new LinkedHashSet<>(Set.of(start)), chains);
    }

    private void dfs(
            String current,
            Map<String, List<String>> adjacency,
            int maxDepth,
            int maxResults,
            boolean reversePath,
            Deque<String> path,
            Set<String> seen,
            Set<String> chains
    ) {
        if (chains.size() >= maxResults) {
            return;
        }

        List<String> nextNodes = adjacency.getOrDefault(current, List.of());
        if (path.size() - 1 >= maxDepth || nextNodes.isEmpty()) {
            if (path.size() > 1) {
                chains.add(formatPath(path, reversePath));
            }
            return;
        }

        boolean expanded = false;
        for (String nextNode : nextNodes) {
            if (!seen.add(nextNode)) {
                continue;
            }
            expanded = true;
            path.addLast(nextNode);
            dfs(nextNode, adjacency, maxDepth, maxResults, reversePath, path, seen, chains);
            path.removeLast();
            seen.remove(nextNode);
            if (chains.size() >= maxResults) {
                return;
            }
        }

        if (!expanded && path.size() > 1) {
            chains.add(formatPath(path, reversePath));
        }
    }

    private String formatPath(Deque<String> path, boolean reversePath) {
        List<String> nodes = new ArrayList<>(path);
        if (reversePath) {
            Collections.reverse(nodes);
        }
        return String.join(" -> ", nodes);
    }

    private void analyzeType(
            String filePath,
            AstParser.ParsedSourceFile parsedSourceFile,
            TypeDeclaration<?> typeDeclaration,
            Map<String, MethodLocation> methodsBySymbol,
            Map<String, List<String>> methodSymbolsByName,
            Map<String, String> qualifiedTypesBySimpleName,
            Map<String, List<ReferenceHit>> referencesBySymbol,
            Map<String, List<String>> outgoingEdges,
            Map<String, List<String>> incomingEdges
    ) {
        String typeSimpleName = typeDeclaration.getNameAsString();
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        typeDeclaration.getMembers().stream()
                .filter(FieldDeclaration.class::isInstance)
                .map(FieldDeclaration.class::cast)
                .forEach(field -> field.getVariables().forEach(variable ->
                        fieldTypes.put(variable.getNameAsString(), resolveTypeName(field.getElementType().asString(), parsedSourceFile, qualifiedTypesBySimpleName))
                ));

        typeDeclaration.getMembers().stream()
                .filter(MethodDeclaration.class::isInstance)
                .map(MethodDeclaration.class::cast)
                .forEach(method -> analyzeMethod(
                        filePath,
                        parsedSourceFile,
                        typeSimpleName,
                        fieldTypes,
                        method,
                        methodsBySymbol,
                        methodSymbolsByName,
                        qualifiedTypesBySimpleName,
                        referencesBySymbol,
                        outgoingEdges,
                        incomingEdges
                ));
    }

    private void analyzeMethod(
            String filePath,
            AstParser.ParsedSourceFile parsedSourceFile,
            String typeSimpleName,
            Map<String, String> fieldTypes,
            MethodDeclaration method,
            Map<String, MethodLocation> methodsBySymbol,
            Map<String, List<String>> methodSymbolsByName,
            Map<String, String> qualifiedTypesBySimpleName,
            Map<String, List<ReferenceHit>> referencesBySymbol,
            Map<String, List<String>> outgoingEdges,
            Map<String, List<String>> incomingEdges
    ) {
        String callerSymbol = typeSimpleName + "#" + method.getNameAsString();
        if (!methodsBySymbol.containsKey(callerSymbol)) {
            return;
        }

        Map<String, String> visibleTypes = new LinkedHashMap<>(fieldTypes);
        method.getParameters().forEach(parameter ->
                visibleTypes.put(parameter.getNameAsString(), resolveTypeName(parameter.getType().asString(), parsedSourceFile, qualifiedTypesBySimpleName))
        );
        method.findAll(VariableDeclarator.class).forEach(variable ->
                visibleTypes.put(variable.getNameAsString(), resolveTypeName(variable.getType().asString(), parsedSourceFile, qualifiedTypesBySimpleName))
        );

        for (MethodCallExpr methodCallExpr : method.findAll(MethodCallExpr.class)) {
            String targetSymbol = resolveMethodTarget(
                    methodCallExpr,
                    typeSimpleName,
                    visibleTypes,
                    parsedSourceFile,
                    methodSymbolsByName,
                    qualifiedTypesBySimpleName
            ).orElse(null);
            if (targetSymbol == null) {
                continue;
            }

            addUniqueEdge(outgoingEdges, callerSymbol, targetSymbol);
            addUniqueEdge(incomingEdges, targetSymbol, callerSymbol);
            addReference(referencesBySymbol, targetSymbol, new ReferenceHit(
                    filePath,
                    methodCallExpr.getBegin().map(position -> position.line).orElse(methodsBySymbol.get(callerSymbol).line()),
                    callerSymbol,
                    "method call"
            ));
        }
    }

    private Optional<String> resolveMethodTarget(
            MethodCallExpr methodCallExpr,
            String currentTypeSimpleName,
            Map<String, String> visibleTypes,
            AstParser.ParsedSourceFile parsedSourceFile,
            Map<String, List<String>> methodSymbolsByName,
            Map<String, String> qualifiedTypesBySimpleName
    ) {
        String methodName = methodCallExpr.getNameAsString();
        if (methodCallExpr.getScope().isEmpty()) {
            return Optional.ofNullable(resolveMethodSymbol(currentTypeSimpleName + "#" + methodName));
        }

        Expression scope = methodCallExpr.getScope().orElseThrow();
        if (scope instanceof ThisExpr) {
            return Optional.ofNullable(resolveMethodSymbol(currentTypeSimpleName + "#" + methodName));
        }
        if (scope instanceof NameExpr nameExpr) {
            return resolveByReceiverType(visibleTypes.get(nameExpr.getNameAsString()), methodName);
        }
        if (scope instanceof FieldAccessExpr fieldAccessExpr && fieldAccessExpr.getScope() instanceof ThisExpr) {
            return resolveByReceiverType(visibleTypes.get(fieldAccessExpr.getNameAsString()), methodName);
        }
        if (scope instanceof ObjectCreationExpr objectCreationExpr) {
            return resolveByReceiverType(
                    resolveTypeName(objectCreationExpr.getType().asString(), parsedSourceFile, qualifiedTypesBySimpleName),
                    methodName
            );
        }
        if (scope.calculateResolvedType().isReferenceType()) {
            String receiverType = scope.calculateResolvedType().describe();
            return resolveByReceiverType(receiverType, methodName);
        }

        List<String> candidates = methodSymbolsByName.getOrDefault(methodName, List.of());
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private Optional<String> resolveByReceiverType(String receiverType, String methodName) {
        if (receiverType == null || receiverType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(resolveMethodSymbol(simpleName(receiverType) + "#" + methodName));
    }

    private String resolveMethodSymbol(String rawSymbol) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            return null;
        }
        String normalized = rawSymbol.trim();
        if (methodsBySymbol.containsKey(normalized)) {
            return normalized;
        }
        if (normalized.contains("#")) {
            String[] parts = normalized.split("#", 2);
            String simpleCandidate = simpleName(parts[0]) + "#" + parts[1];
            if (methodsBySymbol.containsKey(simpleCandidate)) {
                return simpleCandidate;
            }
            List<String> byName = methodSymbolsByName.getOrDefault(parts[1], List.of());
            if (byName.size() == 1) {
                return byName.getFirst();
            }
            return null;
        }
        List<String> byName = methodSymbolsByName.getOrDefault(normalized, List.of());
        return byName.size() == 1 ? byName.getFirst() : null;
    }

    private List<String> resolveTypeCandidates(String rawSymbol) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String normalized = rawSymbol.trim();
        candidates.add(normalized);
        candidates.add(simpleName(normalized));
        String qualified = qualifiedTypesBySimpleName.get(simpleName(normalized));
        if (qualified != null) {
            candidates.add(qualified);
        }
        return List.copyOf(candidates);
    }

    private static void addTypeReference(Map<String, List<ReferenceHit>> referencesBySymbol, String rawType, ReferenceHit hit) {
        if (rawType == null || rawType.isBlank()) {
            return;
        }
        String normalizedType = rawType.trim();
        if (normalizedType.startsWith("java.") || normalizedType.startsWith("javax.") || BUILTIN_TYPES.contains(normalizedType)) {
            return;
        }
        addReference(referencesBySymbol, normalizedType, hit);
        addReference(referencesBySymbol, simpleName(normalizedType), hit);
    }

    private static void addReference(Map<String, List<ReferenceHit>> referencesBySymbol, String symbol, ReferenceHit hit) {
        referencesBySymbol.computeIfAbsent(symbol, ignored -> new ArrayList<>()).add(hit);
    }

    private static void addUniqueEdge(Map<String, List<String>> adjacency, String from, String to) {
        List<String> targets = adjacency.computeIfAbsent(from, ignored -> new ArrayList<>());
        if (!targets.contains(to)) {
            targets.add(to);
        }
    }

    private static String resolveTypeName(
            String rawType,
            AstParser.ParsedSourceFile parsedSourceFile,
            Map<String, String> qualifiedTypesBySimpleName
    ) {
        if (rawType == null || rawType.isBlank()) {
            return "";
        }
        String normalized = rawType.replace("...", "")
                .replace("[]", "")
                .trim();
        int genericStart = normalized.indexOf('<');
        if (genericStart >= 0) {
            normalized = normalized.substring(0, genericStart);
        }
        normalized = normalized.replace("? extends ", "")
                .replace("? super ", "")
                .replace("?", "")
                .trim();

        if (normalized.isBlank() || BUILTIN_TYPES.contains(normalized)) {
            return normalized;
        }
        if (normalized.contains(".")) {
            return normalized;
        }
        for (String importName : parsedSourceFile.imports()) {
            if (!importName.endsWith("." + normalized)) {
                continue;
            }
            return importName;
        }
        return qualifiedTypesBySimpleName.getOrDefault(normalized, parsedSourceFile.packageName().isBlank()
                ? normalized
                : parsedSourceFile.packageName() + "." + normalized);
    }

    private static String simpleName(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "";
        }
        int lastDot = rawType.lastIndexOf('.');
        return lastDot >= 0 ? rawType.substring(lastDot + 1) : rawType;
    }

    private static Map<String, List<String>> toImmutableMap(Map<String, List<String>> source) {
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, List<ReferenceHit>> toImmutableReferenceMap(Map<String, List<ReferenceHit>> source) {
        LinkedHashMap<String, List<ReferenceHit>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }

    record ReferenceHit(
            String filePath,
            int line,
            String ownerSymbol,
            String reason
    ) {
    }

    private record MethodLocation(
            String filePath,
            int line
    ) {
    }
}
