package com.aidevassistant.llm;

import com.aidevassistant.dto.AnalysisResponse;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the OpenAI {@code response_format} block for the {@link AnalysisResponse} schema.
 *
 * <h3>Single source of truth</h3>
 * <p>The schema is derived at startup by introspecting the generated {@link AnalysisResponse}
 * DTO class using Jackson's {@link BeanDescription} API. This means {@code openapi.yaml} is
 * the single source of truth: any field added to or removed from the {@code AnalysisResponse}
 * schema in {@code openapi.yaml} is automatically reflected here without any manual changes.
 *
 * <h3>Type mapping</h3>
 * <p>Jackson property types are mapped to JSON Schema types:
 * <ul>
 *   <li>{@code String} → {@code {"type": "string"}}</li>
 *   <li>{@code List<String>} → {@code {"type": "array", "items": {"type": "string"}}}</li>
 * </ul>
 *
 * <h3>Performance</h3>
 * <p>The schema is built and serialized to a JSON string once in the constructor.
 * {@link #addResponseFormat(ObjectNode)} parses the cached string — one {@code readTree()}
 * call per request instead of rebuilding the node tree.
 *
 * <h3>Strict mode</h3>
 * <p>The produced schema uses {@code json_schema} strict mode:
 * <ul>
 *   <li>{@code strict: true} — the model must conform exactly to the schema</li>
 *   <li>{@code additionalProperties: false} — no extra fields are allowed</li>
 *   <li>All fields from the DTO are declared as {@code required}</li>
 * </ul>
 */
@Component
public class AnalysisResponseSchemaFactory {

    /** Schema name sent to OpenAI — used for identification in the API response. */
    static final String SCHEMA_NAME = "AnalysisResponse";

    private final ObjectMapper objectMapper;

    /**
     * The serialized {@code response_format} JSON, built once at construction time
     * by introspecting {@link AnalysisResponse}.
     */
    private final String cachedResponseFormatJson;

    public AnalysisResponseSchemaFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.cachedResponseFormatJson = buildResponseFormatJson(objectMapper);
    }

    /**
     * Adds the pre-built {@code response_format} block to the given request root node in-place.
     *
     * @param root the Jackson {@link ObjectNode} representing the Chat Completions request body
     */
    public void addResponseFormat(ObjectNode root) {
        try {
            root.set("response_format", objectMapper.readTree(cachedResponseFormatJson));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse cached response_format JSON", e);
        }
    }

    // -------------------------------------------------------------------------
    // Private — called once at construction time
    // -------------------------------------------------------------------------

    /**
     * Introspects {@link AnalysisResponse} using Jackson's {@link BeanDescription} to
     * derive field names and types, then builds the {@code response_format.json_schema}
     * JSON object.
     *
     * <p>This method is the key to eliminating schema duplication: it reads the same
     * {@code @JsonProperty} annotations that the openapi-generator placed on the DTO,
     * so the schema is always in sync with {@code openapi.yaml}.
     */
    private static String buildResponseFormatJson(ObjectMapper mapper) {
        try {
            SerializationConfig config = mapper.getSerializationConfig();
            JavaType type = mapper.constructType(AnalysisResponse.class);
            BeanDescription beanDesc = config.introspect(type);
            List<BeanPropertyDefinition> properties = beanDesc.findProperties();

            ObjectNode responseFormat = mapper.createObjectNode();
            responseFormat.set("type", new TextNode("json_schema"));

            ObjectNode jsonSchema = responseFormat.putObject("json_schema");
            jsonSchema.set("name", new TextNode(SCHEMA_NAME));
            jsonSchema.set("strict", BooleanNode.TRUE);

            ObjectNode schema = jsonSchema.putObject("schema");
            schema.set("type", new TextNode("object"));

            ObjectNode propertiesNode = schema.putObject("properties");
            ArrayNode required = schema.putArray("required");

            for (BeanPropertyDefinition prop : properties) {
                String fieldName = prop.getName();
                JavaType fieldType = prop.getPrimaryType();

                required.add(fieldName);
                propertiesNode.set(fieldName, buildPropertySchema(mapper, fieldType));
            }

            // Disallow any fields not declared in the DTO
            schema.set("additionalProperties", BooleanNode.FALSE);

            return mapper.writeValueAsString(responseFormat);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to build response_format JSON schema from AnalysisResponse DTO", e);
        }
    }

    /**
     * Maps a Jackson {@link JavaType} to a JSON Schema property node.
     *
     * <p>Supported mappings:
     * <ul>
     *   <li>{@code String} → {@code {"type": "string"}}</li>
     *   <li>{@code List<String>} (or any {@code Collection<String>})
     *       → {@code {"type": "array", "items": {"type": "string"}}}</li>
     * </ul>
     *
     * <p>Other types default to {@code {"type": "string"}} as a safe fallback.
     */
    private static ObjectNode buildPropertySchema(ObjectMapper mapper, JavaType fieldType) {
        ObjectNode propSchema = mapper.createObjectNode();

        if (fieldType.isCollectionLikeType()) {
            propSchema.set("type", new TextNode("array"));
            JavaType elementType = fieldType.getContentType();
            ObjectNode items = propSchema.putObject("items");
            items.set("type", new TextNode(jsonSchemaType(elementType)));
        } else {
            propSchema.set("type", new TextNode(jsonSchemaType(fieldType)));
        }

        return propSchema;
    }

    /**
     * Returns the JSON Schema primitive type name for the given {@link JavaType}.
     */
    private static String jsonSchemaType(JavaType type) {
        Class<?> raw = type.getRawClass();
        if (raw == String.class)                                    return "string";
        if (raw == Boolean.class || raw == boolean.class)          return "boolean";
        if (raw == Integer.class || raw == int.class
                || raw == Long.class || raw == long.class)         return "integer";
        if (Number.class.isAssignableFrom(raw))                    return "number";
        return "string"; // safe default
    }
}
