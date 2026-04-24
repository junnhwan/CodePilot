package com.codepilot.core.infrastructure.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class RepositoryToolSupport {

    private RepositoryToolSupport() {
    }

    static Path normalizeRepoRoot(Path repoRoot) {
        return repoRoot.toAbsolutePath().normalize();
    }

    static Path resolveInsideRepo(Path repoRoot, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Path resolvedPath = repoRoot.resolve(filePath).normalize();
        return resolvedPath.startsWith(repoRoot) ? resolvedPath : null;
    }

    static String normalizeRelativePath(String filePath) {
        return filePath == null ? "" : filePath.replace('\\', '/').trim();
    }

    static int positiveInt(Object rawValue, int defaultValue) {
        if (rawValue instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        if (rawValue instanceof String text) {
            try {
                int parsed = Integer.parseInt(text.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    static List<Path> listJavaSourceFiles(Path repoRoot) {
        try (var files = Files.walk(repoRoot)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !relativePath(repoRoot, path).startsWith("target/"))
                    .filter(path -> !relativePath(repoRoot, path).startsWith(".git/"))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan Java sources under " + repoRoot, exception);
        }
    }

    static String relativePath(Path repoRoot, Path file) {
        return repoRoot.relativize(file).toString().replace('\\', '/');
    }
}
