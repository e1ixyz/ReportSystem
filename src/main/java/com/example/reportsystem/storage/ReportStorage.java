package com.example.reportsystem.storage;

import java.io.IOException;
import java.util.List;

public interface ReportStorage {

    /**
     * Prepare the backend (create directories, tables, etc.).
     */
    void init() throws Exception;

    /**
     * Load every report payload as YAML text.
     */
    List<StoredReportPayload> loadAll() throws Exception;

    /**
     * Persist the YAML payload for the given id.
     */
    void save(long id, String yamlPayload) throws Exception;

    /**
     * Identify the backend (filesystem, mysql, ...).
     */
    String backendKey();
}
