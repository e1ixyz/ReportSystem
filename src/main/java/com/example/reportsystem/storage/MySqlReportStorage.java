package com.example.reportsystem.storage;

import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.ChatMessage;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.model.ReportStatus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL-based storage using a simple schema (reports + chat lines).
 */
public class MySqlReportStorage implements ReportStorage {

    private final PluginConfig.MySqlConfig cfg;
    private final Logger log;
    private final HikariDataSource dataSource;
    private final String tableReports;
    private final String tableChat;

    public MySqlReportStorage(PluginConfig.MySqlConfig cfg, Logger log) {
        this.cfg = cfg;
        this.log = log;
        this.tableReports = cfg.tableReports;
        this.tableChat = cfg.tableChat;
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(buildJdbcUrl(cfg));
        hc.setUsername(cfg.username);
        hc.setPassword(cfg.password);
        hc.setMaximumPoolSize(Math.max(1, cfg.connectionPoolSize));
        hc.setPoolName("ReportSystem-MySQL");
        hc.setConnectionTestQuery("SELECT 1");
        this.dataSource = new HikariDataSource(hc);
        try {
            ensureSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize MySQL schema", e);
        }
    }

    private String buildJdbcUrl(PluginConfig.MySqlConfig cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc:mysql://")
          .append(cfg.host)
          .append(":")
          .append(cfg.port)
          .append("/")
          .append(cfg.database);
        List<String> params = new ArrayList<>();
        params.add("useSSL=" + cfg.useSsl);
        params.add("allowPublicKeyRetrieval=" + cfg.allowPublicKeyRetrieval);
        if (cfg.connectionOptions != null && !cfg.connectionOptions.isBlank()) {
            params.add(cfg.connectionOptions);
        }
        sb.append("?").append(String.join("&", params));
        return sb.toString();
    }

    private void ensureSchema() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement reports = conn.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS `" + tableReports + "` (" +
                             "`id` BIGINT PRIMARY KEY," +
                             "`reporter` VARCHAR(64)," +
                             "`reported` VARCHAR(64)," +
                             "`type_id` VARCHAR(64)," +
                             "`type_display` VARCHAR(128)," +
                             "`category_id` VARCHAR(64)," +
                             "`category_display` VARCHAR(128)," +
                             "`reason` TEXT," +
                             "`report_count` INT," +
                             "`timestamp` BIGINT," +
                             "`status` VARCHAR(16)," +
                             "`assignee` VARCHAR(64)," +
                             "`source_server` VARCHAR(128)," +
                             "`last_update` BIGINT," +
                             "`closed_at` BIGINT" +
                             ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
             PreparedStatement chat = conn.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS `" + tableChat + "` (" +
                             "`report_id` BIGINT NOT NULL," +
                             "`line_index` INT NOT NULL," +
                             "`time` BIGINT," +
                             "`player` VARCHAR(64)," +
                             "`server` VARCHAR(128)," +
                             "`message` TEXT," +
                             "PRIMARY KEY (`report_id`, `line_index`)," +
                             "FOREIGN KEY (`report_id`) REFERENCES `" + tableReports + "`(`id`) ON DELETE CASCADE" +
                             ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")) {
            reports.execute();
            chat.execute();
        }
    }

    @Override
    public List<StoredReport> loadAll() throws Exception {
        List<StoredReport> out = new ArrayList<>();
        String sql = "SELECT id, reporter, reported, type_id, type_display, category_id, category_display, reason, report_count, timestamp, status, assignee, source_server, last_update, closed_at FROM `" + tableReports + "`";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Report r = new Report();
                long id = rs.getLong("id");
                r.id = id;
                r.reporter = rs.getString("reporter");
                r.reported = rs.getString("reported");
                r.typeId = rs.getString("type_id");
                r.typeDisplay = rs.getString("type_display");
                r.categoryId = rs.getString("category_id");
                r.categoryDisplay = rs.getString("category_display");
                r.reason = rs.getString("reason");
                r.count = rs.getInt("report_count");
                r.timestamp = rs.getLong("timestamp");
                String status = rs.getString("status");
                r.status = status == null ? ReportStatus.OPEN : ReportStatus.valueOf(status);
                r.assignee = rs.getString("assignee");
                r.sourceServer = rs.getString("source_server");
                loadChat(conn, r);
                long lastUpdate = rs.getLong("last_update");
                if (lastUpdate <= 0) {
                    lastUpdate = Math.max(r.timestamp, latestChatTime(r));
                }
                long closedAtVal = rs.getLong("closed_at");
                Long closedAt = rs.wasNull() ? null : closedAtVal;
                out.add(new StoredReport(r, lastUpdate, closedAt));
            }
        }
        return out;
    }

    private void loadChat(Connection conn, Report report) throws SQLException {
        report.chat.clear();
        String sql = "SELECT line_index, time, player, server, message FROM `" + tableChat + "` WHERE report_id = ? ORDER BY line_index ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, report.id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChatMessage msg = new ChatMessage(
                            rs.getLong("time"),
                            rs.getString("player"),
                            rs.getString("server"),
                            rs.getString("message")
                    );
                    report.chat.add(msg);
                }
            }
        }
    }

    private long latestChatTime(Report report) {
        return report.chat.stream().mapToLong(cm -> cm.time).max().orElse(report.timestamp);
    }

    @Override
    public void save(Report report, long lastUpdateMillis, Long closedAt) throws Exception {
        String upsert = "INSERT INTO `" + tableReports + "` (id, reporter, reported, type_id, type_display, category_id, category_display, reason, report_count, timestamp, status, assignee, source_server, last_update, closed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE reporter=VALUES(reporter), reported=VALUES(reported), type_id=VALUES(type_id), type_display=VALUES(type_display), category_id=VALUES(category_id), category_display=VALUES(category_display), reason=VALUES(reason), report_count=VALUES(report_count), timestamp=VALUES(timestamp), status=VALUES(status), assignee=VALUES(assignee), source_server=VALUES(source_server), last_update=VALUES(last_update), closed_at=VALUES(closed_at)";
        try (Connection conn = dataSource.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(upsert)) {
                    ps.setLong(1, report.id);
                    ps.setString(2, trim(report.reporter, 64));
                    ps.setString(3, trim(report.reported, 64));
                    ps.setString(4, trim(report.typeId, 64));
                    ps.setString(5, trim(report.typeDisplay, 128));
                ps.setString(6, trim(report.categoryId, 64));
                ps.setString(7, trim(report.categoryDisplay, 128));
                ps.setString(8, report.reason);
                ps.setInt(9, report.count);
                ps.setLong(10, report.timestamp);
                ps.setString(11, report.status == null ? ReportStatus.OPEN.name() : report.status.name());
                ps.setString(12, trim(report.assignee, 64));
                ps.setString(13, trim(report.sourceServer, 128));
                ps.setLong(14, lastUpdateMillis);
                if (closedAt == null) {
                    ps.setNull(15, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(15, closedAt);
                }
                    ps.executeUpdate();
                }
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM `" + tableChat + "` WHERE report_id = ?")) {
                    del.setLong(1, report.id);
                    del.executeUpdate();
                }

                if (report.chat != null && !report.chat.isEmpty()) {
                    String insertChat = "INSERT INTO `" + tableChat + "` (report_id, line_index, time, player, server, message) VALUES (?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement chatPs = conn.prepareStatement(insertChat)) {
                        int index = 0;
                        for (ChatMessage msg : report.chat) {
                            chatPs.setLong(1, report.id);
                            chatPs.setInt(2, index++);
                            chatPs.setLong(3, msg.time);
                            chatPs.setString(4, trim(msg.player, 64));
                            chatPs.setString(5, trim(msg.server, 128));
                            chatPs.setString(6, msg.message);
                            chatPs.addBatch();
                        }
                        chatPs.executeBatch();
                    }
                }

                conn.commit();
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public void close() {
        dataSource.close();
    }

    @Override
    public String backendId() {
        return "mysql";
    }
}
