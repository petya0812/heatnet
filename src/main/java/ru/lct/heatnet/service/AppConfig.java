package ru.lct.heatnet.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import ru.lct.heatnet.plan.PlanParams;
import ru.lct.heatnet.reference.ReferenceData;
import ru.lct.heatnet.reference.RuleOptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@EnableConfigurationProperties(HeatnetProperties.class)
public class AppConfig {

    @Bean
    public ReferenceData referenceData() {
        return ReferenceData.load();
    }

    @Bean
    public PlanParams planParams(HeatnetProperties props) {
        PlanParams p = new PlanParams();
        p.rules.existingFlow = RuleOptions.ExistingFlow.valueOf(props.getExistingFlow().toUpperCase());
        p.rules.existingFlowShare = props.getExistingFlowShare();
        return p;
    }

    @Bean
    public StoragePaths storagePaths(HeatnetProperties props) {
        Path root = Paths.get(props.getStorageDir());
        StoragePaths paths = new StoragePaths(root.resolve("uploads"), root.resolve("tmp"), root.resolve("results"));
        try {
            Files.createDirectories(paths.uploads);
            Files.createDirectories(paths.tmp);
            Files.createDirectories(paths.results);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage directories under " + root, e);
        }
        return paths;
    }

    @Bean(name = "importExecutor")
    public ThreadPoolTaskExecutor importExecutor(HeatnetProperties props) {
        return executor("import-", props.getImportThreads(), props.getQueueCapacity());
    }

    @Bean(name = "runExecutor")
    public ThreadPoolTaskExecutor runExecutor(HeatnetProperties props) {
        return executor("run-", props.getRunThreads(), props.getQueueCapacity());
    }

    private static ThreadPoolTaskExecutor executor(String prefix, int threads, int queue) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix(prefix);
        ex.setCorePoolSize(threads);
        ex.setMaxPoolSize(threads);
        ex.setQueueCapacity(queue);
        ex.initialize();
        return ex;
    }

    /** On-disk locations (docker volume). */
    public static final class StoragePaths {
        public final Path uploads;
        public final Path tmp;
        public final Path results;

        StoragePaths(Path uploads, Path tmp, Path results) {
            this.uploads = uploads;
            this.tmp = tmp;
            this.results = results;
        }
    }
}
