package com.codepilot.core.infrastructure.context;

import com.codepilot.core.domain.context.AstParser;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaParserAstParser implements AstParser {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([\\w\\.]+)\\s*;");

    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+([\\w\\.\\*]+)\\s*;");

    private static final Pattern TYPE_PATTERN = Pattern.compile("\\b(class|interface|record|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private static final Pattern METHOD_PATTERN = Pattern.compile("(?:[A-Za-z_][A-Za-z0-9_<>\\[\\]]*\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    private static final Set<String> NON_METHOD_KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "new", "return", "throw", "else", "try");

    private final JavaParser javaParser = new JavaParser();

    @Override
    public ParsedSourceFile parse(Path repoRoot, String filePath) {
        Path normalizedRepoRoot = repoRoot.toAbsolutePath().normalize();
        Path resolvedPath = normalizedRepoRoot.resolve(filePath).normalize();
        if (!resolvedPath.startsWith(normalizedRepoRoot)) {
            throw new IllegalStateException("AstParser file_path must stay inside the repository root: " + filePath);
        }
        if (!Files.exists(resolvedPath)) {
            throw new IllegalStateException("AstParser file does not exist: " + filePath);
        }

        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(resolvedPath);
            if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                return toParsedSourceFile(filePath, parseResult.getResult().orElseThrow());
            }
            return fallbackParse(filePath, Files.readAllLines(resolvedPath));
        } catch (IOException error) {
            throw new IllegalStateException("Failed to parse Java source " + filePath, error);
        }
    }

    private ParsedSourceFile toParsedSourceFile(String filePath, CompilationUnit unit) {
        String packageName = unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
        List<String> imports = unit.getImports().stream()
                .map(importDeclaration -> importDeclaration.getNameAsString())
                .toList();
        Map<String, String> importIndex = indexImports(packageName, imports);

        List<TypeSymbol> types = new ArrayList<>();
        for (TypeDeclaration<?> typeDeclaration : unit.getTypes()) {
            if (!(typeDeclaration instanceof ClassOrInterfaceDeclaration)
                    && !(typeDeclaration instanceof EnumDeclaration)
                    && !(typeDeclaration instanceof RecordDeclaration)) {
                continue;
            }
            types.add(toTypeSymbol(packageName, importIndex, typeDeclaration));
        }
        return new ParsedSourceFile(filePath, packageName, imports, types, ParseMode.FULL_AST);
    }

    private TypeSymbol toTypeSymbol(
            String packageName,
            Map<String, String> importIndex,
            TypeDeclaration<?> typeDeclaration
    ) {
        String simpleName = typeDeclaration.getNameAsString();
        String qualifiedName = qualify(packageName, simpleName);
        LinkedHashSet<String> referencedTypes = new LinkedHashSet<>();

        if (typeDeclaration instanceof ClassOrInterfaceDeclaration classOrInterface) {
            classOrInterface.getExtendedTypes().forEach(type -> referencedTypes.add(resolveTypeName(type.asString(), packageName, importIndex)));
            classOrInterface.getImplementedTypes().forEach(type -> referencedTypes.add(resolveTypeName(type.asString(), packageName, importIndex)));
        } else if (typeDeclaration instanceof RecordDeclaration recordDeclaration) {
            recordDeclaration.getImplementedTypes().forEach(type -> referencedTypes.add(resolveTypeName(type.asString(), packageName, importIndex)));
            recordDeclaration.getParameters().forEach(parameter -> referencedTypes.add(resolveTypeName(parameter.getType().asString(), packageName, importIndex)));
        }

        typeDeclaration.getMembers().stream()
                .filter(FieldDeclaration.class::isInstance)
                .map(FieldDeclaration.class::cast)
                .forEach(field -> referencedTypes.add(resolveTypeName(field.getElementType().asString(), packageName, importIndex)));

        typeDeclaration.getMembers().stream()
                .filter(ConstructorDeclaration.class::isInstance)
                .map(ConstructorDeclaration.class::cast)
                .forEach(constructor -> constructor.getParameters()
                        .forEach(parameter -> referencedTypes.add(resolveTypeName(parameter.getType().asString(), packageName, importIndex))));

        List<MethodSymbol> methods = typeDeclaration.getMembers().stream()
                .filter(MethodDeclaration.class::isInstance)
                .map(MethodDeclaration.class::cast)
                .map(method -> toMethodSymbol(simpleName, packageName, importIndex, referencedTypes, method))
                .toList();

        return new TypeSymbol(
                qualifiedName,
                simpleName,
                beginLine(typeDeclaration),
                endLine(typeDeclaration),
                referencedTypes,
                methods
        );
    }

    private MethodSymbol toMethodSymbol(
            String typeSimpleName,
            String packageName,
            Map<String, String> importIndex,
            Set<String> typeReferencedTypes,
            MethodDeclaration method
    ) {
        LinkedHashSet<String> referencedTypes = new LinkedHashSet<>(typeReferencedTypes);
        referencedTypes.add(resolveTypeName(method.getType().asString(), packageName, importIndex));
        method.getParameters().forEach(parameter -> referencedTypes.add(resolveTypeName(parameter.getType().asString(), packageName, importIndex)));
        method.findAll(ClassOrInterfaceType.class).forEach(type -> referencedTypes.add(resolveTypeName(type.asString(), packageName, importIndex)));
        method.findAll(ObjectCreationExpr.class).forEach(expr -> referencedTypes.add(resolveTypeName(expr.getType().asString(), packageName, importIndex)));

        String signature = "%s %s(%s)".formatted(
                method.getType().asString(),
                method.getNameAsString(),
                method.getParameters().stream()
                        .map(parameter -> parameter.getType().asString() + " " + parameter.getNameAsString())
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("")
        );

        return new MethodSymbol(
                typeSimpleName + "#" + method.getNameAsString(),
                method.getNameAsString(),
                signature,
                beginLine(method),
                endLine(method),
                referencedTypes
        );
    }

    private ParsedSourceFile fallbackParse(String filePath, List<String> lines) {
        String packageName = "";
        List<String> imports = new ArrayList<>();
        List<TypeSymbol> types = new ArrayList<>();
        String currentTypeName = null;
        int currentTypeStart = 1;
        List<MethodSymbol> methods = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher packageMatcher = PACKAGE_PATTERN.matcher(line);
            if (packageMatcher.find()) {
                packageName = packageMatcher.group(1);
            }
            Matcher importMatcher = IMPORT_PATTERN.matcher(line);
            if (importMatcher.find()) {
                imports.add(importMatcher.group(1));
            }
            Matcher typeMatcher = TYPE_PATTERN.matcher(line);
            if (typeMatcher.find()) {
                if (currentTypeName != null) {
                    types.add(new TypeSymbol(
                            qualify(packageName, currentTypeName),
                            currentTypeName,
                            currentTypeStart,
                            Math.max(index, currentTypeStart),
                            new LinkedHashSet<>(imports),
                            List.copyOf(methods)
                    ));
                    methods = new ArrayList<>();
                }
                currentTypeName = typeMatcher.group(2);
                currentTypeStart = index + 1;
            }
            Matcher methodMatcher = METHOD_PATTERN.matcher(line);
            if (methodMatcher.find() && currentTypeName != null) {
                String methodName = methodMatcher.group(1);
                if (!NON_METHOD_KEYWORDS.contains(methodName)) {
                    methods.add(new MethodSymbol(
                            currentTypeName + "#" + methodName,
                            methodName,
                            line.trim(),
                            index + 1,
                            index + 1,
                            new LinkedHashSet<>(imports)
                    ));
                }
            }
        }

        if (currentTypeName != null) {
            types.add(new TypeSymbol(
                    qualify(packageName, currentTypeName),
                    currentTypeName,
                    currentTypeStart,
                    Math.max(lines.size(), currentTypeStart),
                    new LinkedHashSet<>(imports),
                    List.copyOf(methods)
            ));
        }

        return new ParsedSourceFile(filePath, packageName, imports, types, ParseMode.REGEX_FALLBACK);
    }

    private Map<String, String> indexImports(String packageName, List<String> imports) {
        LinkedHashMap<String, String> importIndex = new LinkedHashMap<>();
        for (String importName : imports) {
            if (importName.endsWith(".*")) {
                continue;
            }
            int lastDot = importName.lastIndexOf('.');
            String simpleName = lastDot >= 0 ? importName.substring(lastDot + 1) : importName;
            importIndex.put(simpleName, importName);
        }
        importIndex.putIfAbsent("String", "java.lang.String");
        if (!packageName.isBlank()) {
            importIndex.putIfAbsent(packageName.substring(packageName.lastIndexOf('.') + 1), packageName);
        }
        return importIndex;
    }

    private String resolveTypeName(String rawType, String packageName, Map<String, String> importIndex) {
        String normalized = normalizeType(rawType);
        if (normalized.isBlank()) {
            return normalized;
        }
        if (normalized.contains(".")) {
            return normalized;
        }
        String imported = importIndex.get(normalized);
        if (imported != null) {
            return imported;
        }
        if (isBuiltin(normalized)) {
            return normalized;
        }
        return qualify(packageName, normalized);
    }

    private String normalizeType(String rawType) {
        if (rawType == null) {
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
        return normalized;
    }

    private boolean isBuiltin(String typeName) {
        return switch (typeName) {
            case "byte", "short", "int", "long", "float", "double", "boolean", "char", "void",
                 "String", "Integer", "Long", "Boolean", "Double", "Float", "Short", "Byte", "Character" -> true;
            default -> false;
        };
    }

    private String qualify(String packageName, String simpleName) {
        return packageName == null || packageName.isBlank()
                ? simpleName
                : packageName + "." + simpleName;
    }

    private int beginLine(Node node) {
        return node.getBegin().map(position -> position.line).orElse(1);
    }

    private int endLine(Node node) {
        return node.getEnd().map(position -> position.line).orElse(beginLine(node));
    }
}
