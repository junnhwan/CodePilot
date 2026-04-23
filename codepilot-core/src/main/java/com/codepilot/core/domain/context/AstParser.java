package com.codepilot.core.domain.context;

import com.codepilot.core.domain.DomainRuleViolationException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public interface AstParser {

    ParsedSourceFile parse(Path repoRoot, String filePath);

    record ParsedSourceFile(
            String filePath,
            String packageName,
            List<String> imports,
            List<TypeSymbol> types,
            ParseMode parseMode
    ) {

        public ParsedSourceFile {
            filePath = requireText(filePath, "filePath");
            packageName = packageName == null ? "" : packageName.trim();
            imports = imports == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(imports));
            types = types == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(types));
            if (parseMode == null) {
                throw new DomainRuleViolationException("ParsedSourceFile[%s] parseMode must not be null".formatted(filePath));
            }
        }

        public List<MethodSymbol> allMethodSymbols() {
            return types.stream()
                    .flatMap(type -> type.methods().stream())
                    .toList();
        }

        public Set<String> findTouchedSymbols(int startLine, int endLine) {
            LinkedHashSet<String> touched = new LinkedHashSet<>();
            allMethodSymbols().stream()
                    .filter(method -> intersects(method.startLine(), method.endLine(), startLine, endLine))
                    .map(MethodSymbol::symbolName)
                    .forEach(touched::add);
            if (!touched.isEmpty()) {
                return Collections.unmodifiableSet(touched);
            }
            types.stream()
                    .filter(type -> intersects(type.startLine(), type.endLine(), startLine, endLine))
                    .map(TypeSymbol::simpleName)
                    .forEach(touched::add);
            return Collections.unmodifiableSet(touched);
        }

        public Set<String> referencedTypes() {
            LinkedHashSet<String> referenced = new LinkedHashSet<>(imports);
            types.forEach(type -> {
                referenced.addAll(type.referencedTypes());
                type.methods().forEach(method -> referenced.addAll(method.referencedTypes()));
            });
            return Collections.unmodifiableSet(referenced);
        }

        public Set<String> referencedTypesForSymbols(Set<String> touchedSymbols) {
            if (touchedSymbols == null || touchedSymbols.isEmpty()) {
                return referencedTypes();
            }

            LinkedHashSet<String> referenced = new LinkedHashSet<>();
            types.forEach(type -> {
                if (matchesTypeSymbol(touchedSymbols, type)) {
                    referenced.addAll(type.referencedTypes());
                }
                type.methods().stream()
                        .filter(method -> touchedSymbols.contains(method.symbolName()))
                        .forEach(method -> {
                            referenced.addAll(type.referencedTypes());
                            referenced.addAll(method.referencedTypes());
                        });
            });
            return Collections.unmodifiableSet(referenced);
        }

        private boolean matchesTypeSymbol(Set<String> touchedSymbols, TypeSymbol type) {
            return touchedSymbols.contains(type.simpleName()) || touchedSymbols.contains(type.qualifiedName());
        }

        private static boolean intersects(int leftStart, int leftEnd, int rightStart, int rightEnd) {
            return leftStart <= rightEnd && rightStart <= leftEnd;
        }
    }

    record TypeSymbol(
            String qualifiedName,
            String simpleName,
            int startLine,
            int endLine,
            Set<String> referencedTypes,
            List<MethodSymbol> methods
    ) {

        public TypeSymbol {
            qualifiedName = requireText(qualifiedName, "qualifiedName");
            simpleName = requireText(simpleName, "simpleName");
            if (startLine <= 0 || endLine < startLine) {
                throw new DomainRuleViolationException("TypeSymbol[%s] has invalid line range %d-%d"
                        .formatted(qualifiedName, startLine, endLine));
            }
            referencedTypes = referencedTypes == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(referencedTypes));
            methods = methods == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(methods));
        }
    }

    record MethodSymbol(
            String symbolName,
            String methodName,
            String signature,
            int startLine,
            int endLine,
            Set<String> referencedTypes
    ) {

        public MethodSymbol {
            symbolName = requireText(symbolName, "symbolName");
            methodName = requireText(methodName, "methodName");
            signature = signature == null ? "" : signature.trim();
            if (startLine <= 0 || endLine < startLine) {
                throw new DomainRuleViolationException("MethodSymbol[%s] has invalid line range %d-%d"
                        .formatted(symbolName, startLine, endLine));
            }
            referencedTypes = referencedTypes == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(referencedTypes));
        }
    }

    enum ParseMode {
        FULL_AST,
        REGEX_FALLBACK
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
