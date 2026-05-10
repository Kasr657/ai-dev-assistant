package com.aidevassistant.config;

import com.aidevassistant.util.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configures the application's {@link AsyncTaskExecutor} with an
 * {@link MdcTaskDecorator} so that MDC context (e.g. {@code requestId}) is
 * propagated from the originating request thread into any async worker threads.
 */
@Configuration
public class AsyncConfig {

    private final MdcTaskDecorator mdcTaskDecorator;

    public AsyncConfig(MdcTaskDecorator mdcTaskDecorator) {
        this.mdcTaskDecorator = mdcTaskDecorator;
    }

    /**
     * Creates a {@link ThreadPoolTaskExecutor} with the {@link MdcTaskDecorator}
     * registered as its task decorator. Spring Boot's async support will use this
     * executor when {@code @Async} methods are invoked.
     *
     * @return a configured {@link AsyncTaskExecutor}
     */
    @Bean(name = "applicationTaskExecutor")
    public AsyncTaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        executor.setTaskDecorator(mdcTaskDecorator);
        executor.initialize();
        return executor;
    }
}
