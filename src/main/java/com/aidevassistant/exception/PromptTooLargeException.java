package com.aidevassistant.exception;

/**
 * Thrown when the constructed prompt exceeds the configured maximum character limit.
 *
 * <p>This is a client-facing error — the submitted code snippet is too large to
 * process within the configured token budget. Maps to HTTP 400 Bad Request.
 */
public class PromptTooLargeException extends RuntimeException {

    private final int promptLength;
    private final int maxLength;

    public PromptTooLargeException(int promptLength, int maxLength) {
        super(String.format(
                "Prompt length %d characters exceeds the maximum allowed %d characters. "
                        + "Submit a smaller code snippet.",
                promptLength, maxLength));
        this.promptLength = promptLength;
        this.maxLength = maxLength;
    }

    /** The actual length of the prompt that was rejected. */
    public int getPromptLength() {
        return promptLength;
    }

    /** The configured maximum allowed prompt length. */
    public int getMaxLength() {
        return maxLength;
    }
}
