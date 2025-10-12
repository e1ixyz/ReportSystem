package com.example.reportsystem.storage;

import com.example.reportsystem.config.PluginConfig;
import org.slf4j.Logger;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MysqlReportStorage implements ReportStorage {

    private final PluginConfig.MysqlStorageConfig config;
    private final Logger log;
    private String jdbcUrl;
    private String table;
    private String tableRef;

    public MysqlReportStorage(PluginConfig.MysqlStorageConfig config, Logger log) {
        this.config = config;
        this.log = log;
    }

    @Override
    public void init() throws Exception {
        this.table = sanitizeTable(config.table);
        this.tableRef = "`" + this.table + "`";
        this.jdbcUrl = buildJdbcUrl();
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {
            String ddl = "CREATE TABLE IF NOT EXISTS " + tableRef + " (" +
                    "id BIGINT PRIMARY KEY," +
                    "payload LONGTEXT NOT NULL," +
                    "updated_at BIGINT NOT NULL" +
                    ")";
            st.executeUpdate(ddl);
        }
    }

    @Override
    public List<StoredReportPayload> loadAll() throws Exception {
        List<StoredReportPayload> list = new ArrayList<>();
        String sql = "SELECT id, payload FROM " + tableRef;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String payload = rs.getString("payload");
                list.add(new StoredReportPayload(id, payload));
            }
        }
        return list;
    }

    @Override
    public void save(long id, String yamlPayload) throws Exception {
        String sql = "INSERT INTO " + tableRef + " (id, payload, updated_at) VALUES (?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE payload = VALUES(payload), updated_at = VALUES(updated_at)";
        long now = Instant.now().toEpochMilli();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, yamlPayload);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    @Override
    public String backendKey() {
        return "mysql";
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, config.username, config.password);
    }

    private String buildJdbcUrl() {
        String params = config.params == null ? "" : config.params.trim();
        if (!params.isBlank() && !params.startsWith("?")) {
            params = "?" + params;
        }
        return "jdbc:mysql://" + config.host + ":" + config.port + "/" + config.database + params;
    }

    private String sanitizeTable(String input) {
        if (input == null || input.isBlank()) {
            return "rs_reports";
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : input.toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '$') {
                sb.append(ch);
            }
        }
        String out = sb.toString();
        if (out.isBlank()) {
            log.warn("Invalid MySQL table name '{}', using default 'rs_reports'.", input);
            return "rs_reports";
        }
        return out.toLowerCase(Locale.ROOT);
    }
}
