package com.company.testgen.phases;

import com.company.testgen.model.GeneratedFile;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Default Checkstyle validator enforcing target repository style and quality rules (§6.12).
 * Catches wildcard imports, non-standard naming, forbidden debug calls, Thread.sleep,
 * line length breaches, and missing block braces.
 */
public class DefaultCheckstyleValidator implements CheckstyleValidator {

    private static final Logger log = LoggerFactory.getLogger(DefaultCheckstyleValidator.class);

    private static final int DEFAULT_MAX_LINE_LENGTH = 140;
    private static final Pattern PASCAL_CASE = Pattern.compile("^[A-Z][a-zA-Z0-9]*$");
    private static final Pattern CAMEL_CASE = Pattern.compile("^[a-z][a-zA-Z0-9]*$");

    private final int maxLineLength;
    private final Path checkstyleXmlConfig;

    public DefaultCheckstyleValidator() {
        this(DEFAULT_MAX_LINE_LENGTH, null);
    }

    public DefaultCheckstyleValidator(int maxLineLength) {
        this(maxLineLength, null);
    }

    public DefaultCheckstyleValidator(int maxLineLength, Path checkstyleXmlConfig) {
        this.maxLineLength = maxLineLength > 0 ? maxLineLength : DEFAULT_MAX_LINE_LENGTH;
        this.checkstyleXmlConfig = checkstyleXmlConfig;
    }

    @Override
    public List<String> validate(List<GeneratedFile> files) {
        List<String> violations = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return violations;
        }

        JavaParser parser = new JavaParser();

        for (GeneratedFile file : files) {
            String path = file.path() != null ? file.path() : "<unknown>";
            String content = file.content() != null ? file.content() : "";

            // 1. Line length check
            String[] lines = content.split("\r?\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                // Ignore package and import lines for length check to avoid false positives on deep packages
                if (!line.trim().startsWith("package ") && !line.trim().startsWith("import ")
                        && line.length() > maxLineLength) {
                    violations.add("Checkstyle [LineLength]: Line %d in %s exceeds %d characters (length: %d)"
                            .formatted(i + 1, path, maxLineLength, line.length()));
                }
            }

            // 2. Text-based forbidden constructs
            if (content.contains("System.out.print") || content.contains("System.err.print")) {
                violations.add("Checkstyle [NoSystemOut]: Direct console logging found in " + path
                        + ". Use SLF4J logger instead.");
            }
            if (content.contains(".printStackTrace()")) {
                violations.add("Checkstyle [NoPrintStackTrace]: printStackTrace() call found in " + path
                        + ". Use SLF4J logger instead.");
            }
            if (content.contains("Thread.sleep(")) {
                violations.add("Checkstyle [NoThreadSleep]: Thread.sleep() found in " + path
                        + ". Playwright tests must rely strictly on auto-waiting assertions (§6.10, §6.12).");
            }

            // 3. AST-based checks
            parser.parse(content).ifSuccessful(cu -> {
                // Wildcard imports
                cu.getImports().forEach(imp -> {
                    if (imp.isAsterisk()) {
                        violations.add("Checkstyle [AvoidStarImport]: Wildcard import '" + imp.getNameAsString()
                                + ".*' in " + path + " is forbidden.");
                    }
                });

                // Class naming
                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cid -> {
                    String className = cid.getNameAsString();
                    if (!PASCAL_CASE.matcher(className).matches()) {
                        violations.add("Checkstyle [TypeName]: Class name '" + className + "' in " + path
                                + " must be in PascalCase.");
                    }
                    if (path.contains("Test.java") && !className.endsWith("Test")) {
                        violations.add("Checkstyle [TestNaming]: Test class '" + className + "' in " + path
                                + " must end with 'Test'.");
                    }
                    if (path.contains("Page.java") && !className.endsWith("Page")) {
                        violations.add("Checkstyle [PageNaming]: Page object class '" + className + "' in " + path
                                + " must end with 'Page'.");
                    }
                });

                // Method naming
                cu.findAll(MethodDeclaration.class).forEach(md -> {
                    String methodName = md.getNameAsString();
                    if (!CAMEL_CASE.matcher(methodName).matches()) {
                        violations.add("Checkstyle [MethodName]: Method '" + methodName + "' in " + path
                                + " must follow camelCase naming convention.");
                    }
                });

                // Missing braces on control blocks
                cu.findAll(IfStmt.class).forEach(is -> {
                    if (!is.getThenStmt().isBlockStmt()) {
                        violations.add("Checkstyle [NeedBraces]: 'if' statement in " + path
                                + " must use block braces {}.");
                    }
                    if (is.hasElseBranch() && is.getElseStmt().isPresent()
                            && !is.getElseStmt().get().isBlockStmt()
                            && !is.getElseStmt().get().isIfStmt()) {
                        violations.add("Checkstyle [NeedBraces]: 'else' statement in " + path
                                + " must use block braces {}.");
                    }
                });
                cu.findAll(ForStmt.class).forEach(fs -> {
                    if (!fs.getBody().isBlockStmt()) {
                        violations.add("Checkstyle [NeedBraces]: 'for' loop in " + path
                                + " must use block braces {}.");
                    }
                });
                cu.findAll(WhileStmt.class).forEach(ws -> {
                    if (!ws.getBody().isBlockStmt()) {
                        violations.add("Checkstyle [NeedBraces]: 'while' loop in " + path
                                + " must use block braces {}.");
                    }
                });
                cu.findAll(DoStmt.class).forEach(ds -> {
                    if (!ds.getBody().isBlockStmt()) {
                        violations.add("Checkstyle [NeedBraces]: 'do-while' loop in " + path
                                + " must use block braces {}.");
                    }
                });
            });
        }

        if (checkstyleXmlConfig != null && Files.isRegularFile(checkstyleXmlConfig)) {
            log.debug("Target repository checkstyle config found at {}", checkstyleXmlConfig);
        }

        return violations;
    }
}
