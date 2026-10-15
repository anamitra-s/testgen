package com.company.testgen.phases;

import com.company.testgen.model.GeneratedFile;
import com.company.testgen.model.GeneratedFileSet;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import com.company.testgen.validation.LocatorRenderer;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes post-generation linting checks in strict sequential order (§6.12, FR-17):
 * 1. JavaParser AST parse check — catches malformed or truncated Java syntax immediately.
 * 2. Locator substitution check — verifies every validated locator expression appears verbatim and no unvalidated locator expressions exist.
 * 3. Checkstyle validation against target repository rules.
 * 4. Package and import sanity check for target_area.
 */
public class LintPhase {

    private static final Logger log = LoggerFactory.getLogger(LintPhase.class);

    private static final Set<String> PLAYWRIGHT_LOCATOR_METHODS = Set.of(
            "getByTestId",
            "getByRole",
            "getByLabel",
            "getByPlaceholder",
            "getByText",
            "getByAltText",
            "getByTitle",
            "locator"
    );

    private final CheckstyleValidator checkstyleValidator;

    public LintPhase() {
        this(new DefaultCheckstyleValidator());
    }

    public LintPhase(CheckstyleValidator checkstyleValidator) {
        this.checkstyleValidator = Objects.requireNonNull(checkstyleValidator, "checkstyleValidator must not be null");
    }

    public LintResult check(GeneratedFileSet fileSet) {
        return check(fileSet, null, (String) null);
    }

    public LintResult check(GeneratedFileSet fileSet, ResolvedLocatorSet locators) {
        return check(fileSet, locators, (String) null);
    }

    public LintResult check(GeneratedFileSet fileSet, ResolvedLocatorSet locators, ParsedTestPlan plan) {
        String targetArea = plan != null ? plan.targetArea() : null;
        return check(fileSet, locators, targetArea);
    }

    public LintResult check(GeneratedFileSet fileSet, ResolvedLocatorSet locators, String targetArea) {
        Objects.requireNonNull(fileSet, "fileSet must not be null");
        List<GeneratedFile> files = fileSet.files();
        if (files == null || files.isEmpty()) {
            return LintResult.failed("No generated files present in GeneratedFileSet.");
        }

        List<String> errors = new ArrayList<>();
        JavaParser parser = new JavaParser();
        List<CompilationUnit> parsedCus = new ArrayList<>();

        // ═════════════════════════════════════════════════════════════════════
        // Check 1: JavaParser AST parse check (malformed / truncated syntax)
        // ═════════════════════════════════════════════════════════════════════
        for (GeneratedFile file : files) {
            String path = file.path() != null ? file.path() : "<unknown>";
            String content = file.content();
            if (content == null || content.isBlank()) {
                errors.add("File " + path + " is empty or contains no content.");
                continue;
            }

            ParseResult<CompilationUnit> result = parser.parse(content);
            if (!result.isSuccessful()) {
                List<String> problemMsgs = result.getProblems().stream()
                        .map(Problem::getMessage)
                        .toList();
                errors.add("JavaParser syntax parse error in " + path + ": " + String.join("; ", problemMsgs));
            } else {
                result.getResult().ifPresent(parsedCus::add);
            }
        }

        // If AST parsing failed, abort early — malformed syntax invalidates downstream checks
        if (!errors.isEmpty()) {
            log.warn("LintPhase check 1 (JavaParser AST parse) failed with {} errors", errors.size());
            return LintResult.failed(errors);
        }

        // ═════════════════════════════════════════════════════════════════════
        // Check 2: Locator substitution check
        // ═════════════════════════════════════════════════════════════════════
        if (locators != null && locators.locators() != null) {
            // (a) Verify every ResolvedLocator.expression() appears verbatim in generated code
            for (ResolvedLocator loc : locators.locators()) {
                String expectedExpr = (loc.expression() != null && !loc.expression().isBlank())
                        ? loc.expression()
                        : LocatorRenderer.render(loc);

                boolean verbatimMatch = files.stream().anyMatch(f ->
                        f.content() != null && f.content().contains(expectedExpr));

                if (!verbatimMatch) {
                    errors.add("Locator substitution error: required expression '%s' for element '%s' was not found verbatim in any generated file."
                            .formatted(expectedExpr, loc.elementRefId()));
                }
            }

            // (b) Verify NO unvalidated locator expressions exist
            Set<String> normalizedAllowed = new HashSet<>();
            for (ResolvedLocator loc : locators.locators()) {
                String rendered = (loc.expression() != null && !loc.expression().isBlank())
                        ? loc.expression()
                        : LocatorRenderer.render(loc);
                normalizedAllowed.add(normalizeLocatorCall(rendered));
                if (rendered.startsWith("page.")) {
                    normalizedAllowed.add(normalizeLocatorCall(rendered.substring("page.".length())));
                }
            }

            for (int i = 0; i < files.size(); i++) {
                GeneratedFile file = files.get(i);
                CompilationUnit cu = (i < parsedCus.size()) ? parsedCus.get(i) : null;
                if (cu == null) continue;

                List<MethodCallExpr> locatorCalls = cu.findAll(MethodCallExpr.class).stream()
                        .filter(m -> PLAYWRIGHT_LOCATOR_METHODS.contains(m.getNameAsString()))
                        .toList();

                for (MethodCallExpr call : locatorCalls) {
                    String actualCallStr = call.toString();
                    String normCall = normalizeLocatorCall(actualCallStr);

                    if (!isAllowedLocatorCall(normCall, normalizedAllowed)) {
                        errors.add("Unvalidated locator expression found in %s: %s"
                                .formatted(file.path(), actualCallStr));
                    }
                }
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // Check 3: Checkstyle validation against repository rules
        // ═════════════════════════════════════════════════════════════════════
        List<String> checkstyleErrors = checkstyleValidator.validate(files);
        if (checkstyleErrors != null && !checkstyleErrors.isEmpty()) {
            errors.addAll(checkstyleErrors);
        }

        // ═════════════════════════════════════════════════════════════════════
        // Check 4: Package and import sanity check for target_area
        // ═════════════════════════════════════════════════════════════════════
        String cleanArea = targetArea != null ? targetArea.trim().toLowerCase().replaceAll("[^a-z0-9]", "") : "";

        for (int i = 0; i < files.size(); i++) {
            GeneratedFile file = files.get(i);
            String path = file.path() != null ? file.path().replace('\\', '/') : "";
            CompilationUnit cu = (i < parsedCus.size()) ? parsedCus.get(i) : null;
            if (cu == null) continue;

            Optional<PackageDeclaration> pkgOpt = cu.getPackageDeclaration();
            if (pkgOpt.isEmpty()) {
                errors.add("Package sanity check failed: File %s has no package declaration.".formatted(path));
                continue;
            }

            String pkgName = pkgOpt.get().getNameAsString();

            // Determine if test class or page object
            boolean isTestFile = path.endsWith("Test.java") || cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .anyMatch(c -> c.getNameAsString().endsWith("Test"));
            boolean isPageFile = path.endsWith("Page.java") || cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .anyMatch(c -> c.getNameAsString().endsWith("Page"));

            if (isTestFile) {
                if (!pkgName.startsWith("com.company.tests")) {
                    errors.add("Package sanity check failed: Test file %s declared package '%s' but must start with 'com.company.tests'."
                            .formatted(path, pkgName));
                }
                // Verify test class imports JUnit 5
                boolean hasJunit5 = cu.getImports().stream()
                        .map(ImportDeclaration::getNameAsString)
                        .anyMatch(imp -> imp.startsWith("org.junit.jupiter.api"));
                if (!hasJunit5) {
                    errors.add("Import sanity check failed: Test file %s does not import JUnit 5 (org.junit.jupiter.api.*)."
                            .formatted(path));
                }
            } else if (isPageFile) {
                if (!pkgName.startsWith("com.company.pages")) {
                    errors.add("Package sanity check failed: Page object file %s declared package '%s' but must start with 'com.company.pages'."
                            .formatted(path, pkgName));
                }
                // Verify page object imports Playwright
                boolean hasPlaywright = cu.getImports().stream()
                        .map(ImportDeclaration::getNameAsString)
                        .anyMatch(imp -> imp.startsWith("com.microsoft.playwright"));
                if (!hasPlaywright) {
                    errors.add("Import sanity check failed: Page object file %s does not import Playwright (com.microsoft.playwright.*)."
                            .formatted(path));
                }
            }

            // Check targetArea consistency if present
            if (!cleanArea.isEmpty()) {
                if (isTestFile && !pkgName.contains(cleanArea) && !path.contains(cleanArea)) {
                    log.debug("Notice: Test class %s does not explicitly contain targetArea '%s'".formatted(path, cleanArea));
                }
            }

            // Check path and package alignment
            String expectedPathSuffix = pkgName.replace('.', '/') + "/" + getPrimaryClassName(cu, path) + ".java";
            if (!path.endsWith(expectedPathSuffix) && !path.endsWith(".java")) {
                errors.add("Package/path alignment failure: File %s declared package '%s' but file path does not match package structure."
                        .formatted(path, pkgName));
            }

            // Check forbidden legacy imports
            for (ImportDeclaration imp : cu.getImports()) {
                String impName = imp.getNameAsString();
                if (impName.startsWith("org.openqa.selenium")) {
                    errors.add("Import sanity check failed: Banned Selenium import '%s' found in %s.".formatted(impName, path));
                }
                if (impName.equals("org.junit.Test") || impName.startsWith("junit.framework")) {
                    errors.add("Import sanity check failed: Legacy JUnit 4 import '%s' found in %s (must use JUnit 5).".formatted(impName, path));
                }
            }
        }

        if (errors.isEmpty()) {
            return LintResult.pass();
        } else {
            return LintResult.failed(errors);
        }
    }

    private static String getPrimaryClassName(CompilationUnit cu, String fallbackPath) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .findFirst()
                .map(ClassOrInterfaceDeclaration::getNameAsString)
                .orElseGet(() -> {
                    int lastSlash = fallbackPath.lastIndexOf('/');
                    int dot = fallbackPath.lastIndexOf('.');
                    if (dot > lastSlash && lastSlash >= 0) {
                        return fallbackPath.substring(lastSlash + 1, dot);
                    }
                    return "Unknown";
                });
    }

    private static String normalizeLocatorCall(String expr) {
        if (expr == null) return "";
        return expr.replaceAll("\\s+", " ")
                .replace("this.", "")
                .trim();
    }

    private static boolean isAllowedLocatorCall(String actualNorm, Set<String> allowedNormSet) {
        if (allowedNormSet.contains(actualNorm)) {
            return true;
        }
        for (String allowed : allowedNormSet) {
            if (actualNorm.endsWith(allowed) || allowed.endsWith(actualNorm)) {
                return true;
            }
        }
        return false;
    }
}
