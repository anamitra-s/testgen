package com.company.testgen.llm;

import com.company.testgen.model.LocatorStrategy;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaRegistryTest {

    @Test
    @DisplayName("RESOLVED_LOCATOR_SET schema strictly enforces LocatorStrategy enum and requires elementRefId, strategy, value with no expression field")
    void testResolvedLocatorSetSchemaConstraints() {
        JsonSchema schema = SchemaRegistry.RESOLVED_LOCATOR_SET;
        assertThat(schema.name()).isEqualTo("ResolvedLocatorSet");
        assertThat(schema.rootElement()).isInstanceOf(JsonObjectSchema.class);

        JsonObjectSchema root = (JsonObjectSchema) schema.rootElement();
        assertThat(root.required()).containsExactly("locators");
        assertThat(root.properties()).containsKey("locators");

        JsonArraySchema locatorsArray = (JsonArraySchema) root.properties().get("locators");
        assertThat(locatorsArray.items()).isInstanceOf(JsonObjectSchema.class);

        JsonObjectSchema locatorItem = (JsonObjectSchema) locatorsArray.items();

        // Must require elementRefId, strategy, value
        assertThat(locatorItem.required()).containsExactlyInAnyOrder("elementRefId", "strategy", "value");

        // Must NOT contain free-text "expression" field
        assertThat(locatorItem.properties()).doesNotContainKey("expression");

        // Must contain elementRefId, strategy, value, role
        assertThat(locatorItem.properties()).containsKeys("elementRefId", "strategy", "value", "role");

        // Strategy must be constrained to the LocatorStrategy enum
        assertThat(locatorItem.properties().get("strategy")).isInstanceOf(JsonEnumSchema.class);
        JsonEnumSchema strategyEnum = (JsonEnumSchema) locatorItem.properties().get("strategy");
        List<String> expectedStrategies = Arrays.stream(LocatorStrategy.values()).map(Enum::name).toList();
        assertThat(strategyEnum.enumValues()).containsExactlyElementsOf(expectedStrategies);
    }

    @Test
    @DisplayName("GENERATED_FILE_SET schema requires path, content, isNew for each generated file")
    void testGeneratedFileSetSchemaConstraints() {
        JsonSchema schema = SchemaRegistry.GENERATED_FILE_SET;
        assertThat(schema.name()).isEqualTo("GeneratedFileSet");
        assertThat(schema.rootElement()).isInstanceOf(JsonObjectSchema.class);

        JsonObjectSchema root = (JsonObjectSchema) schema.rootElement();
        assertThat(root.required()).containsExactly("files");
        assertThat(root.properties()).containsKey("files");

        JsonArraySchema filesArray = (JsonArraySchema) root.properties().get("files");
        JsonObjectSchema fileItem = (JsonObjectSchema) filesArray.items();

        assertThat(fileItem.required()).containsExactlyInAnyOrder("path", "content", "isNew");
        assertThat(fileItem.properties()).containsKeys("path", "content", "isNew");
    }
}
