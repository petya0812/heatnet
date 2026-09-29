package ru.lct.heatnet.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Start-up cleanup after a restart or a crash: imports and runs that were in progress are marked failed,
 * their partial tables and files removed, temporary multipart files deleted.
 */
@Component
public class Housekeeping {

    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);
    private static final String INTERRUPTED = "Interrupted by a restart of the service";

    private final JdbcTemplate jdbc;
    private final DatasetRepository datasets;
    private final ResultStore results;
    private final AppConfig.StoragePaths paths;

    public Housekeeping(JdbcTemplate jdbc, DatasetRepository datasets, ResultStore results,
                        AppConfig.StoragePaths paths) {
        this.jdbc = jdbc;
        this.datasets = datasets;
        this.results = results;
        this.paths = paths;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        List<UUID> ds = jdbc.queryForList("SELECT id FROM dataset WHERE status IN ('UPLOADED', 'IMPORTING')",
                UUID.class);
        for (UUID id : ds) {
            datasets.dropTable(id);
            jdbc.update("UPDATE dataset SET status = 'FAILED', finished_at = now(), error = ? WHERE id = ?",
                    INTERRUPTED, id);
        }
        List<UUID> runs = jdbc.queryForList("SELECT id FROM run WHERE status IN ('QUEUED', 'RUNNING')", UUID.class);
        for (UUID id : runs) {
            results.drop(id);
            delete(paths.results.resolve(id + ".geojson"));
            jdbc.update("UPDATE run SET status = 'FAILED', finished_at = now(), error = ? WHERE id = ?",
                    INTERRUPTED, id);
        }
        int files = clear(paths.tmp) + clear(paths.uploads);
        if (!ds.isEmpty() || !runs.isEmpty() || files > 0) {
            log.info("Start-up cleanup: {} interrupted imports, {} interrupted runs, {} temporary files",
                    ds.size(), runs.size(), files);
        }
    }

    /** Nothing in these directories survives a restart: uploads are removed after import, tmp is multipart. */
    private static int clear(Path dir) {
        int n = 0;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                if (Files.isRegularFile(p) && delete(p)) {
                    n++;
                }
            }
        } catch (IOException e) {
            log.warn("Cannot clean {}: {}", dir, e.getMessage());
        }
        return n;
    }

    private static boolean delete(Path p) {
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Cannot delete {}: {}", p, e.getMessage());
            return false;
        }
    }
}
