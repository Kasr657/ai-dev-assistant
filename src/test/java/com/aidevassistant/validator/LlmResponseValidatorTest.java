package com.aidevassistant.validator;

import com.aidevassistant.dto.AnalysisResponse;
import com.aidevassistant.exception.LlmValidationException;
import net.jqwik.api.*;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LlmResponseValidator}.
 *
 * <p>Covers both property-based tests (via jqwik) and targeted unit tests:
 * <ul>
 *   <li>Acceptance of valid JSON with all required fields</li>
 *   <li>Rejection of JSON missing one or more required fields</li>
 *   <li>Rejection of blank, empty, and null input</li>
 *   <li>Extraction from markdown code fences and surrounding text</li>
 *   <li>Round-trip property: valid JSON in → equivalent AnalysisResponse out</li>
 * </ul>
 */
class LlmResponseValidatorTest {

    // Initialized inline so jqwik @Property tests (which don't call @BeforeEach)
    // also get a non-null instance.
    private final LlmResponseValidator validator = new LlmResponseValidator(
            new com.fasterxml.jackson.databind.ObjectMapper(),
            jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());

    // =========================================================================
    // Unit tests: acceptance of valid JSON
    // =========================================================================

    @Test
    void acceptsValidJsonWithAllRequiredFields() {
        String json = """
                {
                  "summary": "The code looks fine.",
                  "issues": ["No null checks"],
                  "improvements": ["Add Javadoc"]
                }
                """;

        AnalysisResponse response = validator.validate(json);

        assertEquals("The code looks fine.", response.getSummary());
        assertEquals(List.of("No null checks"), response.getIssues());
        assertEquals(List.of("Add Javadoc"), response.getImprovements());
    }

    @Test
    void acceptsValidJsonWithEmptyArrays() {
        String json = """
                {
                  "summary": "No issues found.",
                  "issues": [],
                  "improvements": []
                }
                """;

        AnalysisResponse response = validator.validate(json);

        assertEquals("No issues found.", response.getSummary());
        assertTrue(response.getIssues().isEmpty());
        assertTrue(response.getImprovements().isEmpty());
    }

    @Test
    void acceptsValidJsonWithMultipleArrayItems() {
        String json = """
                {
                  "summary": "Several issues detected.",
                  "issues": ["Missing null check", "Unused import"],
                  "improvements": ["Use streams", "Add unit tests", "Refactor method"]
                }
                """;

        AnalysisResponse response = validator.validate(json);

        assertEquals(2, response.getIssues().size());
        assertEquals(3, response.getImprovements().size());
    }

    // =========================================================================
    // Unit tests: rejection of JSON missing required fields
    // =========================================================================

    @ParameterizedTest(name = "rejects JSON missing field: {0}")
    @CsvSource({
        "summary,   '{\"issues\":[\"Some issue\"],\"improvements\":[\"Some improvement\"]}'",
        "issues,    '{\"summary\":\"Looks good.\",\"improvements\":[\"Some improvement\"]}'",
        "improvements, '{\"summary\":\"Looks good.\",\"issues\":[\"Some issue\"]}'"
    })
    void rejectsMissingRequiredField(String missingField, String json) {
        LlmValidationException ex = assertThrows(LlmValidationException.class,
                () -> validator.validate(json));
        assertTrue(ex.getMissingFields().contains(missingField),
                "Missing fields should include '" + missingField + "'");
    }

    @Test
    void rejectsAllFieldsMissing() {
        String json = "{}";

        LlmValidationException ex = assertThrows(LlmValidationException.class,
                () -> validator.validate(json));
        List<String> missing = ex.getMissingFields();
        assertTrue(missing.contains("summary"));
        assertTrue(missing.contains("issues"));
        assertTrue(missing.contains("improvements"));
    }

    @ParameterizedTest(name = "rejects invalid field value for: {0}")
    @MethodSource("invalidFieldValueCases")
    void rejectsInvalidFieldValue(String expectedMissingField, String json) {
        LlmValidationException ex = assertThrows(LlmValidationException.class,
                () -> validator.validate(json));
        assertTrue(ex.getMissingFields().contains(expectedMissingField),
                "Missing fields should include '" + expectedMissingField + "'");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidFieldValueCases() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("summary",
                """
                {
                  "summary": null,
                  "issues": [],
                  "improvements": []
                }
                """),
            org.junit.jupiter.params.provider.Arguments.of("issues",
                """
                {
                  "summary": "OK",
                  "issues": "not an array",
                  "improvements": []
                }
                """),
            org.junit.jupiter.params.provider.Arguments.of("improvements",
                """
                {
                  "summary": "OK",
                  "issues": [],
                  "improvements": 42
                }
                """)
        );
    }

    // =========================================================================
    // Unit tests: rejection of blank and empty input
    // =========================================================================

    @Test
    void rejectsNullInput() {
        assertThrows(LlmValidationException.class, () -> validator.validate(null));
    }

    @Test
    void rejectsEmptyString() {
        assertThrows(LlmValidationException.class, () -> validator.validate(""));
    }

    @Test
    void rejectsWhitespaceOnlyString() {
        assertThrows(LlmValidationException.class, () -> validator.validate("   \t\n  "));
    }

    // =========================================================================
    // Unit tests: extraction from markdown fences and surrounding text
    // =========================================================================

    @Test
    void extractsJsonFromJsonMarkdownFence() {
        String fenced = """
                ```json
                {
                  "summary": "Fenced response.",
                  "issues": ["Issue 1"],
                  "improvements": ["Improvement 1"]
                }
                ```""";

        AnalysisResponse response = validator.validate(fenced);

        assertEquals("Fenced response.", response.getSummary());
        assertEquals(List.of("Issue 1"), response.getIssues());
        assertEquals(List.of("Improvement 1"), response.getImprovements());
    }

    @Test
    void extractsJsonFromPlainMarkdownFence() {
        String fenced = """
                ```
                {
                  "summary": "Plain fence.",
                  "issues": [],
                  "improvements": ["Use constants"]
                }
                ```""";

        AnalysisResponse response = validator.validate(fenced);

        assertEquals("Plain fence.", response.getSummary());
        assertEquals(List.of("Use constants"), response.getImprovements());
    }

    @Test
    void extractsJsonWhenSurroundedByExtraText() {
        String withPreamble = """
                Here is the analysis:
                {
                  "summary": "Extra text around JSON.",
                  "issues": [],
                  "improvements": []
                }
                Hope that helps!""";

        AnalysisResponse response = validator.validate(withPreamble);

        assertEquals("Extra text around JSON.", response.getSummary());
    }

    // =========================================================================
    // Property: round-trip for valid JSON
    // =========================================================================

    /**
     * For all JSON strings conforming to the output schema, {@code validate()} must
     * return an {@link AnalysisResponse} whose fields equal the generated values.
     */
    @Property(tries = 50)
    void validatorRoundTripForValidJson(
            @ForAll @NotBlank String summary,
            @ForAll @Size(max = 5) List<@NotBlank String> issues,
            @ForAll @Size(max = 5) List<@NotBlank String> improvements) {

        String json = buildValidJson(summary, issues, improvements);

        AnalysisResponse response = validator.validate(json);

        assertEquals(summary, response.getSummary(),
                "Round-trip summary must match");
        assertEquals(issues, response.getIssues(),
                "Round-trip issues must match");
        assertEquals(improvements, response.getImprovements(),
                "Round-trip improvements must match");
    }

    // =========================================================================
    // Property: rejects JSON missing required fields
    // =========================================================================

    /**
     * For all JSON objects missing the {@code summary} field, {@code validate()} must
     * throw {@link LlmValidationException}.
     */
    @Property(tries = 50)
    void validatorRejectsJsonMissingSummary(
            @ForAll @Size(max = 3) List<@NotBlank String> issues,
            @ForAll @Size(max = 3) List<@NotBlank String> improvements) {

        String json = buildJsonWithoutSummary(issues, improvements);

        assertThrows(LlmValidationException.class, () -> validator.validate(json),
                "Validator must reject JSON missing 'summary'");
    }

    /**
     * For all JSON objects missing the {@code issues} field, {@code validate()} must
     * throw {@link LlmValidationException}.
     */
    @Property(tries = 50)
    void validatorRejectsJsonMissingIssues(
            @ForAll @NotBlank String summary,
            @ForAll @Size(max = 3) List<@NotBlank String> improvements) {

        String json = buildJsonWithoutIssues(summary, improvements);

        assertThrows(LlmValidationException.class, () -> validator.validate(json),
                "Validator must reject JSON missing 'issues'");
    }

    /**
     * For all JSON objects missing the {@code improvements} field, {@code validate()} must
     * throw {@link LlmValidationException}.
     */
    @Property(tries = 50)
    void validatorRejectsJsonMissingImprovements(
            @ForAll @NotBlank String summary,
            @ForAll @Size(max = 3) List<@NotBlank String> issues) {

        String json = buildJsonWithoutImprovements(summary, issues);

        assertThrows(LlmValidationException.class, () -> validator.validate(json),
                "Validator must reject JSON missing 'improvements'");
    }

    // =========================================================================
    // Property: rejects blank/empty input
    // =========================================================================

    /**
     * For all blank or empty strings, {@code validate()} must throw
     * {@link LlmValidationException}.
     */
    @Property(tries = 50)
    void validatorRejectsBlankInput(@ForAll("blankStrings") String blank) {
        assertThrows(LlmValidationException.class, () -> validator.validate(blank),
                "Validator must reject blank input: '" + blank + "'");
    }

    @Provide
    Arbitrary<String> blankStrings() {
        // Generate empty string and whitespace-only strings
        Arbitrary<String> empty = Arbitraries.just("");
        Arbitrary<String> whitespace = Arbitraries.strings()
                .withChars(' ', '\t', '\n', '\r')
                .ofMinLength(1)
                .ofMaxLength(20);
        return Arbitraries.oneOf(empty, whitespace);
    }

    // =========================================================================
    // Private helpers for building JSON strings
    // =========================================================================

    private String buildValidJson(String summary, List<String> issues, List<String> improvements) {
        return "{"
                + "\"summary\":" + quoteJson(summary) + ","
                + "\"issues\":" + toJsonArray(issues) + ","
                + "\"improvements\":" + toJsonArray(improvements)
                + "}";
    }

    private String buildJsonWithoutSummary(List<String> issues, List<String> improvements) {
        return "{"
                + "\"issues\":" + toJsonArray(issues) + ","
                + "\"improvements\":" + toJsonArray(improvements)
                + "}";
    }

    private String buildJsonWithoutIssues(String summary, List<String> improvements) {
        return "{"
                + "\"summary\":" + quoteJson(summary) + ","
                + "\"improvements\":" + toJsonArray(improvements)
                + "}";
    }

    private String buildJsonWithoutImprovements(String summary, List<String> issues) {
        return "{"
                + "\"summary\":" + quoteJson(summary) + ","
                + "\"issues\":" + toJsonArray(issues)
                + "}";
    }

    private String quoteJson(String value) {
        // Escape all characters that are illegal or special in JSON strings
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        // Escape all other control characters as unicode escape sequences
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private String toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(quoteJson(items.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }
}
