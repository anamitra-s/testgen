package com.company.testgen.validation;

import com.company.testgen.idempotency.IdempotencyKeyDeriver;
import com.company.testgen.model.ExistingCodeContext;
import com.company.testgen.model.LocatorDigest;
import com.company.testgen.model.MethodDigest;
import com.company.testgen.model.PageObjectDigest;
import com.company.testgen.orchestrator.PipelineException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Static analyzer extracting method signatures, Javadoc, and locator literals
 * from existing *Page.java classes using JavaParser AST walks (§6.11, FR-16).
 * Features an intra-job Caffeine cache keyed on sha256(gitTreeSha + analyzerVersion).
 */
public class ExistingPageObjectAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ExistingPageObjectAnalyzer.class);
    public static final String ANALYZER_VERSION = "v1.0.0";

    private static final Set<String> LOCATOR_METHODS = Set.of(
            "locator", "getByTestId", "getByRole", "getByText",
            "getByLabel", "getByPlaceholder", "getByTitle", "getByAltText"
    );

    private final String analyzerVersion;
    private final Cache<String, ExistingCodeContext> cache;

    public ExistingPageObjectAnalyzer() {
        this(ANALYZER_VERSION);
    }

    public ExistingPageObjectAnalyzer(String analyzerVersion) {
        this.analyzerVersion = analyzerVersion != null ? analyzerVersion : ANALYZER_VERSION;
        this.cache = Caffeine.newBuilder()
                .maximumSize(256)
                .build();
    }

    /**
     * Analyzes Page Objects in the given directory using the git tree SHA for memoization.
     */
    public ExistingCodeContext analyze(Path pageObjectDir, String treeSha) {
        return analyze(pageObjectDir, treeSha, "");
    }

    /**
     * Analyzes Page Objects in the given directory for a specific target area.
     */
    public ExistingCodeContext analyze(Path pageObjectDir, String treeSha, String targetArea) {
        String key = cacheKey(treeSha, pageObjectDir);
        return cache.get(key, k -> doAnalyze(pageObjectDir, targetArea));
    }

    /**
     * Computes the intra-job cache key: sha256(gitTreeSha + analyzerVersion) (§6.11).
     */
    public String cacheKey(String treeSha) {
        return cacheKey(treeSha, null);
    }

    public String cacheKey(String treeSha, Path pageObjectDir) {
        String base = (treeSha != null && !treeSha.isBlank())
                ? treeSha.trim()
                : (pageObjectDir != null ? pageObjectDir.toAbsolutePath().normalize().toString() : "empty");
        return IdempotencyKeyDeriver.sha256Hex(base + ":" + analyzerVersion);
    }

    private ExistingCodeContext doAnalyze(Path dir, String targetArea) {
        if (dir == null || !Files.exists(dir)) {
            log.warn("Page object directory {} does not exist; returning empty ExistingCodeContext", dir);
            return new ExistingCodeContext(targetArea, List.of());
        }

        List<PageObjectDigest> digests = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.toString().endsWith("Page.java"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> {
                        try {
                            CompilationUnit cu = StaticJavaParser.parse(path.toFile());
                            String className = cu.getPrimaryTypeName()
                                    .orElseGet(() -> {
                                        String fileName = path.getFileName().toString();
                                        return fileName.endsWith(".java")
                                                ? fileName.substring(0, fileName.length() - 5)
                                                : fileName;
                                    });

                            List<MethodDigest> methods = cu.findAll(MethodDeclaration.class).stream()
                                    .filter(MethodDeclaration::isPublic)
                                    .map(m -> new MethodDigest(
                                            m.getNameAsString(),
                                            m.getSignature().asString(),
                                            m.getJavadocComment().map(c -> c.getContent().strip()).orElse(null),
                                            m.getBody().map(this::extractLocatorLiterals).orElse(List.of())
                                    ))
                                    .toList();

                            List<LocatorDigest> locators = cu.findAll(FieldDeclaration.class).stream()
                                    .flatMap(f -> f.getVariables().stream())
                                    .flatMap(v -> v.getInitializer().stream()
                                            .map(init -> new LocatorDigest(v.getNameAsString(), init.toString())))
                                    .toList();

                            String relPath = relativePath(dir, path);
                            digests.add(new PageObjectDigest(className, relPath, methods, locators));
                        } catch (Exception e) {
                            log.warn("Failed to parse Page Object file {}: {}", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            throw new PipelineException("Failed to analyze Page Objects in " + dir, e);
        }

        log.info("Analyzed {} Page Objects in {} for targetArea={}", digests.size(), dir, targetArea);
        return new ExistingCodeContext(targetArea, digests);
    }

    /**
     * Extracts string literals passed into locator calls or locator options inside a method body.
     */
    public List<String> extractLocatorLiterals(BlockStmt body) {
        if (body == null) {
            return List.of();
        }
        List<String> literals = new ArrayList<>();

        // 1. Direct locator method calls: page.locator("..."), page.getByTestId("..."), etc.
        body.findAll(MethodCallExpr.class).forEach(mc -> {
            String name = mc.getNameAsString();
            if (LOCATOR_METHODS.contains(name)) {
                for (Expression arg : mc.getArguments()) {
                    if (arg.isStringLiteralExpr()) {
                        literals.add(arg.asStringLiteralExpr().getValue());
                    }
                }
            } else if ("setName".equals(name) || "hasText".equals(name)) {
                // e.g., new Page.GetByRoleOptions().setName("Save")
                for (Expression arg : mc.getArguments()) {
                    if (arg.isStringLiteralExpr()) {
                        literals.add(arg.asStringLiteralExpr().getValue());
                    }
                }
            }
        });

        return literals.stream().distinct().toList();
    }

    private String relativePath(Path baseDir, Path file) {
        try {
            return baseDir.relativize(file).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.getFileName().toString();
        }
    }

    public String analyzerVersion() {
        return analyzerVersion;
    }

    public Cache<String, ExistingCodeContext> cache() {
        return cache;
    }
}
