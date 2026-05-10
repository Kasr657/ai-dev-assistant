package com.aidevassistant.validator;

import com.aidevassistant.dto.AnalysisResponse;
import com.aidevassistant.exception.LlmValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates raw LLM response strings and maps them to {@link AnalysisResponse} DTOs.
 *
 * <h3>Single source of truth</h3>
 * <p>{@code openapi.yaml} is the single source of truth for the {@code AnalysisResponse}
 * schema. The openapi-generator produces the DTO with {@code @NotNull} and {@code @Size}
 * constraints. This validator deserializes the LLM response directly into
 * {@link AnalysisResponse} and then runs Bean Validation — so any constraint change in
 * {@code openapi.yaml} is automatically enforced here without any manual code change.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Reject blank/empty input.</li>
 *   <li>Attempt direct {@link ObjectMapper#readTree} parse (fast path — expected when
 *       {@code json_schema} strict mode is enforced at the OpenAI API level).</li>
 *   <li>If direct parse fails: strip markdown code fences, extract outermost {@code {...}}
 *       span, parse again (defensive path for unexpected LLM formatting).</li>
 *   <li>Deserialize the parsed JSON into {@link AnalysisResponse} via
 *       {@link ObjectMapper#treeToValue}.</li>
 *   <li>Run Bean Validation ({@code @NotNull}, {@code @Size}) on the deserialized object.
 *       Any constraint violation is surfaced as a {@link LlmValidationException} listing
 *       the failing field paths.</li>
 * </ol>
 */
@Component
public class LlmResponseValidator {

    private final ObjectMapper objectMapper;
    private final Validator beanValidator;

    /**
     * @param objectMapper  shared Jackson mapper injected by Spring
     * @param beanValidator Jakarta Bean Validation validator injected by Spring
     */
    public LlmResponseValidator(ObjectMapper objectMapper, Validator beanValidator) {
        this.objectMapper = objectMapper;
        this.beanValidator = beanValidator;
    }

    /**
     * Validates {@code rawResponse} and returns a populated {@link AnalysisResponse}.
     *
     * @param rawResponse the raw string returned by the LLM
     * @return a validated {@link AnalysisResponse}
     * @throws LlmValidationException if the input is blank, cannot be parsed as JSON,
     *                                or fails Bean Validation constraints
     */
    public AnalysisResponse validate(String rawResponse) {
        // Step 1: reject blank input
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new LlmValidationException("Empty LLM response");
        }

        // Step 2: try direct parse first (fast path — expected when json_schema is enforced)
        JsonNode root = tryDirectParse(rawResponse.trim());

        // Step 3: fall back to fence-stripping + outermost-object extraction (defensive path)
        if (root == null) {
            String stripped = stripMarkdownFences(rawResponse);
            String jsonCandidate = extractOutermostObject(stripped);
            try {
                root = objectMapper.readTree(jsonCandidate);
            } catch (Exception e) {
                throw new LlmValidationException(
                        "LLM response could not be parsed as JSON: " + e.getMessage());
            }
        }

        // Step 4: deserialize into AnalysisResponse DTO
        AnalysisResponse response;
        try {
            response = objectMapper.treeToValue(root, AnalysisResponse.class);
        } catch (com.fasterxml.jackson.databind.exc.MismatchedInputException e) {
            // Jackson throws MismatchedInputException when a field has the wrong type
            // (e.g. "issues": "not an array"). Extract the field path and surface it
            // as a missing field so the error message is consistent with other validation failures.
            String fieldPath = e.getPath().isEmpty() ? "unknown"
                    : e.getPath().get(e.getPath().size() - 1).getFieldName();
            List<String> badFields = fieldPath != null ? List.of(fieldPath) : List.of();
            throw new LlmValidationException(
                    "LLM response field has wrong type — " + fieldPath + ": " + e.getOriginalMessage(),
                    badFields);
        } catch (Exception e) {
            throw new LlmValidationException(
                    "LLM response could not be mapped to AnalysisResponse: " + e.getMessage());
        }

        // Step 4b: explicit type checks — treeToValue silently coerces wrong types,
        // so we verify raw JSON node types before Bean Validation.
        List<String> typeErrors = checkNodeTypes(root);
        if (!typeErrors.isEmpty()) {
            throw new LlmValidationException(
                    "LLM response is missing or has wrong-type fields: " + typeErrors,
                    typeErrors);
        }

        // Step 5: run Bean Validation — constraints come from openapi.yaml via the generated DTO
        Set<ConstraintViolation<AnalysisResponse>> violations = beanValidator.validate(response);
        if (!violations.isEmpty()) {
            List<String> failingFields = violations.stream()
                    .map(v -> v.getPropertyPath().toString())
                    .distinct()
                    .sorted()
                    .toList();
            throw new LlmValidationException(
                    "LLM response failed validation — fields: " + failingFields,
                    failingFields);
        }

        return response;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Checks that the required JSON fields have the correct types.
     *
     * <p>{@code treeToValue} silently coerces wrong types (e.g. a string value for
     * {@code issues} becomes an empty list), so {@code @NotNull} alone is insufficient.
     * This method verifies the raw JSON node types before Bean Validation runs.
     *
     * @return a list of field names that are missing or have the wrong type; empty if all are valid
     */
    private static List<String> checkNodeTypes(JsonNode root) {
        List<String> errors = new ArrayList<>();
        JsonNode summaryNode = root.get("summary");
        if (summaryNode == null || summaryNode.isNull() || !summaryNode.isTextual()) {
            errors.add("summary");
        }
        JsonNode issuesNode = root.get("issues");
        if (issuesNode == null || issuesNode.isNull() || !issuesNode.isArray()) {
            errors.add("issues");
        }
        JsonNode improvementsNode = root.get("improvements");
        if (improvementsNode == null || improvementsNode.isNull() || !improvementsNode.isArray()) {
            errors.add("improvements");
        }
        return errors;
    }

    /**
     * Attempts to parse {@code input} directly as a JSON object.
     * Returns the parsed {@link JsonNode} on success, or {@code null} if parsing fails.
     */
    private JsonNode tryDirectParse(String input) {
        try {
            JsonNode node = objectMapper.readTree(input);
            return node.isObject() ? node : null;
        } catch (Exception _) {
            return null;
        }
    }

    /**
     * Removes leading/trailing markdown code fences (``` json ... ``` or ``` ... ```).
     */
    private String stripMarkdownFences(String input) {
        String trimmed = input.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline == -1) return trimmed;
            String afterOpenFence = trimmed.substring(firstNewline + 1);
            int lastFenceIndex = afterOpenFence.lastIndexOf("```");
            if (lastFenceIndex != -1) {
                afterOpenFence = afterOpenFence.substring(0, lastFenceIndex);
            }
            return afterOpenFence.trim();
        }
        return trimmed;
    }

    /**
     * Extracts the outermost JSON object by scanning for the first '{' and last '}'.
     * Used as a fallback when direct parsing fails.
     */
    private String extractOutermostObject(String input) {
        int start = input.indexOf('{');
        int end = input.lastIndexOf('}');
        if (start == -1 || end == -1 || end < start) {
            throw new LlmValidationException("LLM response does not contain a JSON object");
        }
        return input.substring(start, end + 1);
    }
}
