package com.company.testgen.llm;

import com.company.testgen.model.LocatorStrategy;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;

import java.util.Arrays;

/**
 * Registry of native JSON schemas for grammar-constrained LLM structured outputs (§6.10, FR-08).
 */
public final class SchemaRegistry {

    private SchemaRegistry() {}

    private static final JsonObjectSchema RESOLVED_LOCATOR_ITEM = JsonObjectSchema.builder()
            .description("A resolved locator targeting a recorded DOM element")
            .addStringProperty("elementRefId", "The reference ID of the element from recorded-elements.json")
            .addEnumProperty("strategy", Arrays.stream(LocatorStrategy.values()).map(Enum::name).toList(), "Playwright locator strategy conforming to the grammar")
            .addStringProperty("value", "The locator selector value")
            .addStringProperty("role", "The ARIA role, populated only when strategy is ROLE_NAME")
            .addStringProperty("expression", "The exact rendered Playwright-Java locator call, e.g. page.getByTestId(\"...\")")
            .required("elementRefId", "strategy", "value", "expression")
            .build();

    public static final JsonSchema RESOLVED_LOCATOR_SET = JsonSchema.builder()
            .name("ResolvedLocatorSet")
            .rootElement(JsonObjectSchema.builder()
                    .description("Set of resolved locators for recorded DOM elements")
                    .addProperty("locators", JsonArraySchema.builder()
                            .description("List of resolved locators")
                            .items(RESOLVED_LOCATOR_ITEM)
                            .build())
                    .required("locators")
                    .build())
            .build();

    private static final JsonObjectSchema GENERATED_FILE_ITEM = JsonObjectSchema.builder()
            .description("A generated or updated source file")
            .addStringProperty("path", "Relative file path from project root")
            .addStringProperty("content", "Complete Java source code")
            .addBooleanProperty("isNew", "True if newly created, false if updating existing page object")
            .required("path", "content", "isNew")
            .build();

    public static final JsonSchema GENERATED_FILE_SET = JsonSchema.builder()
            .name("GeneratedFileSet")
            .rootElement(JsonObjectSchema.builder()
                    .description("Collection of generated Java source files")
                    .addProperty("files", JsonArraySchema.builder()
                            .description("Generated test classes and page objects")
                            .items(GENERATED_FILE_ITEM)
                            .build())
                    .required("files")
                    .build())
            .build();
}
