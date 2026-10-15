package com.company.testgen.llm;

import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ParsedTestPlan;
import com.company.testgen.model.ResolvedLocator;
import com.company.testgen.model.ResolvedLocatorSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBundleTest {

    @Test
    @DisplayName("PromptBundle.load() loads v7 prompt bundle and computes valid SHA-256 hash")
    void testPromptBundleLoadAndHash() {
        PromptBundle bundle = PromptBundle.load();
        assertThat(bundle).isNotNull();
        assertThat(bundle.version()).isEqualTo("v7");

        assertThat(bundle.resolveSystem()).contains("Allowed Locator Productions");
        assertThat(bundle.resolveSystem()).contains("TEST_ID");
        assertThat(bundle.resolveSystem()).contains("ROLE_NAME");
        assertThat(bundle.resolveSystem()).contains("BANNED: XPath");
        assertThat(bundle.resolveSystem()).contains("Do NOT output rendered Java expression strings");

        assertThat(bundle.generateSystem()).contains("Target Stack");
        assertThat(bundle.generateSystem()).contains("Java 21");
        assertThat(bundle.generateSystem()).contains("Page Object Model");
        assertThat(bundle.generateSystem()).contains("ResolvedLocatorSet");
        assertThat(bundle.generateSystem()).contains("ExistingCodeContext");

        assertThat(bundle.codingConventions()).contains("Page Object Conventions");
        assertThat(bundle.codingConventions()).contains("No Assertions in Page Objects");

        // Hash must be non-empty 64-character SHA-256 hex string
        assertThat(bundle.bundleHash()).isNotNull().hasSize(64);
        assertThat(bundle.bundleHash()).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("PromptBundle helper methods generate proper task instructions")
    void testPromptBundleTaskTemplates() {
        PromptBundle bundle = PromptBundle.load();

        String resolveTask = bundle.resolveTask("{\"targetArea\": \"billing\"}");
        assertThat(resolveTask).contains("Resolve the locators for the actions specified in the following test plan");
        assertThat(resolveTask).contains("billing");

        ParsedTestPlan plan = new ParsedTestPlan("billing", "Update billing card", List.of(), List.of(), false, false);
        ResolvedLocatorSet locators = ResolvedLocatorSet.of(
                new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "save-btn")
        );
        String generateTask = bundle.generateTask(plan, locators);
        assertThat(generateTask).contains("Update billing card");
        assertThat(generateTask).contains("1 locators");
    }

    @Test
    @DisplayName("PromptBundle loads from directory accurately")
    void testLoadFromDirectory(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("resolve.system.md"), "Custom resolve prompt");
        Files.writeString(tempDir.resolve("generate.system.md"), "Custom generate prompt");
        Files.writeString(tempDir.resolve("conventions.md"), "Custom conventions");

        PromptBundle bundle = PromptBundle.loadFromDirectory(tempDir, "v-custom");
        assertThat(bundle.version()).isEqualTo("v-custom");
        assertThat(bundle.resolveSystem()).isEqualTo("Custom resolve prompt");
        assertThat(bundle.generateSystem()).isEqualTo("Custom generate prompt");
        assertThat(bundle.codingConventions()).isEqualTo("Custom conventions");
        assertThat(bundle.bundleHash()).isNotNull().hasSize(64);
    }
}
