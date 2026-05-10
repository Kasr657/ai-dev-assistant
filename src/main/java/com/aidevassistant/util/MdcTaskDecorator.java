package com.aidevassistant.util;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * A {@link TaskDecorator} that propagates the current thread's MDC context map
 * into async threads. This ensures that the {@code requestId} and any other MDC
 * values set by {@code RequestIdFilter} are visible in log entries emitted from
 * async tasks.
 *
 * <p>Register this decorator on the application's
 * {@link org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor} via
 * {@link org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor#setTaskDecorator}.
 * See {@code AsyncConfig} for the wiring.
 */
@Component
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Capture the MDC map from the submitting (caller) thread.
        Map<String, String> callerMdcContext = MDC.getCopyOfContextMap();

        return () -> {
            try {
                if (callerMdcContext != null) {
                    MDC.setContextMap(callerMdcContext);
                } else {
                    MDC.clear();
                }
                runnable.run();
            } finally {
                // Always clear MDC to avoid leaking context into pooled threads.
                MDC.clear();
            }
        };
    }
}
