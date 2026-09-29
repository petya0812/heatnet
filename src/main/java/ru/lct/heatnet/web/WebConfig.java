package ru.lct.heatnet.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Streamed responses (variant layers, features) are written by a bounded pool instead of a new thread per request;
 * the timeout covers a slow download of a large response.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Bean(name = "streamingExecutor")
    public ThreadPoolTaskExecutor streamingExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("stream-");
        ex.setCorePoolSize(16);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(500);
        ex.initialize();
        return ex;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(streamingExecutor());
        configurer.setDefaultTimeout(30L * 60 * 1000);
    }
}
