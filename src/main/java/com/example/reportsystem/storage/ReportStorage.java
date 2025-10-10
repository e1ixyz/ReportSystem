package com.example.reportsystem.storage;

import com.example.reportsystem.model.Report;

import java.io.Closeable;
import java.util.List;

/**
 * Abstraction for persisting reports.
 */
public interface ReportStorage extends Closeable {

    /** Container for loaded report records. */
    record StoredReport(Report report, long lastUpdateMillis, Long closedAt) {}

    List<StoredReport> loadAll() throws Exception;

    void save(Report report, long lastUpdateMillis, Long closedAt) throws Exception;

    @Override
    default void close() {}

    String backendId();
}
