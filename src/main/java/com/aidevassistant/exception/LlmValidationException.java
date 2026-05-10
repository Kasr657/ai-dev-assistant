package com.aidevassistant.exception;

import java.util.Collections;
import java.util.List;

/**
 * Thrown when the LLM response fails schema validation (missing or invalid fields).
 * Maps to HTTP 502 Bad Gateway.
 */
public class LlmValidationException extends RuntimeException {

    private final List<String> missingFields;

    public LlmValidationException(String message) {
        super(message);
        this.missingFields = Collections.emptyList();
    }

    public LlmValidationException(String message, List<String> missingFields) {
        super(message);
        this.missingFields = missingFields != null ? Collections.unmodifiableList(missingFields) : Collections.emptyList();
    }

    /**
     * Returns the list of field names that were missing from the LLM response.
     * Returns an empty list if no specific fields were identified.
     */
    public List<String> getMissingFields() {
        return missingFields;
    }
}
