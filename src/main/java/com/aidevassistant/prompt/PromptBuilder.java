package com.aidevassistant.prompt;

import com.aidevassistant.config.LlmProperties;
import com.aidevassistant.dto.AnalysisRequest;
import com.aidevassistant.dto.AnalysisResponse;
import com.aidevassistant.exception.PromptTooLargeException;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Constructs deterministic, structured prompts for LLM analysis requests.
 *
 * <h3>Single source of truth</h3>
 * <p>The JSON schema example embedded in the prompt is derived at startup by introspecting
 * the generated {@link AnalysisResponse} DTO using Jackson's {@link BeanDescription} API.
 * This means {@code openapi.yaml} is the single source of truth: any field added to or
 * removed from the {@code AnalysisResponse} schema in {@code openapi.yaml} is automatically
 * reflected in the prompt without any manual changes.
 *
 * <h3>Code delimiters</h3>
 * <p>User-supplied code is wrapped between {@code --- BEGIN CODE ---} and
 * {@code --- END CODE ---} delimiters to:
 * <ul>
 *   <li>Make the boundary between instructions and user content explicit, reducing
 *       the risk of code comments being interpreted as prompt instructions.</li>
 *   <li>Allow {@code CodeAnalysisService} to redact the code block from logs.</li>
 * </ul>
 *
 * <h3>Max prompt length</h3>
 * <p>The built prompt is validated against {@link LlmProperties#getMaxPromptChars()}.
 * Requests that exceed the limit are rejected with {@link PromptTooLargeException} (HTTP 400).
 */
@Component
public class PromptBuilder {

    private static final String PROMPT_TEMPLATE = """
            You are a senior backend engineer.

            Analyze the following %%s code and return ONLY a strict JSON object.

            Required JSON schema:
            %s

            Rules:
            - Output ONLY the JSON object. No markdown, no explanation, no text outside the JSON.
            - Be concise and accurate.

            --- BEGIN CODE ---
            %%s
            --- END CODE ---""";

    private final LlmProperties llmProperties;

    /**
     * The fully formatted prompt template with the JSON schema example already embedded.
     * Built once at construction time from the {@link AnalysisResponse} DTO.
     * Uses {@code %s} placeholders for {@code language} and {@code code}.
     */
    private final String resolvedTemplate;

    public PromptBuilder(LlmProperties llmProperties, ObjectMapper objectMapper) {
        this.llmProperties = llmProperties;
        this.resolvedTemplate = String.format(PROMPT_TEMPLATE,
                buildSchemaExample(objectMapper));
    }

    /**
     * Builds a structured prompt from the given {@link AnalysisRequest}.
     *
     * @param request the analysis request containing {@code code} and {@code language}
     * @return a non-blank prompt string ready to be sent to the LLM
     * @throws PromptTooLargeException if the resulting prompt exceeds
     *                                 {@link LlmProperties#getMaxPromptChars()} characters
     */
    public String build(AnalysisRequest request) {
        String prompt = String.format(resolvedTemplate, request.getLanguage(), request.getCode());

        int maxChars = llmProperties.getMaxPromptChars();
        if (prompt.length() > maxChars) {
            throw new PromptTooLargeException(prompt.length(), maxChars);
        }

        return prompt;
    }

    // -------------------------------------------------------------------------
    // Private — called once at construction time
    // -------------------------------------------------------------------------

    /**
     * Introspects {@link AnalysisResponse} using Jackson's {@link BeanDescription} to
     * derive field names and build a human-readable JSON schema example for the prompt.
     *
     * <p>Example output for the current {@code AnalysisResponse} schema:
     * <pre>
     * {
     *   "summary": "&lt;string: one-sentence summary&gt;",
     *   "issues": ["&lt;string&gt;", ...],
     *   "improvements": ["&lt;string&gt;", ...]
     * }
     * </pre>
     */
    private static String buildSchemaExample(ObjectMapper mapper) {
        SerializationConfig config = mapper.getSerializationConfig();
        JavaType type = mapper.constructType(AnalysisResponse.class);
        BeanDescription beanDesc = config.introspect(type);
        List<BeanPropertyDefinition> properties = beanDesc.findProperties();

        StringBuilder sb = new StringBuilder("{\n");
        for (int i = 0; i < properties.size(); i++) {
            BeanPropertyDefinition prop = properties.get(i);
            String fieldName = prop.getName();
            JavaType fieldType = prop.getPrimaryType();
            String comma = (i < properties.size() - 1) ? "," : "";

            if (fieldType.isCollectionLikeType()) {
                sb.append("  \"").append(fieldName).append("\": [\"<string>\", ...]").append(comma).append("\n");
            } else {
                sb.append("  \"").append(fieldName).append("\": \"<string: one-sentence summary>\"").append(comma).append("\n");
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
