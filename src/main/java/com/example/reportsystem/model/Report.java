package com.example.reportsystem.model;

import java.util.ArrayList;
import java.util.List;

public class Report {
    public long id;                  // unique id
    public String reporter;          // player name
    public String reported;          // target (player or string for bug)
    public String typeId;
    public String typeDisplay;
    public String categoryId;
    public String categoryDisplay;
    public String reason;            // initial reason
    public long timestamp;           // created
    public int count;                // stacked count
    public ReportStatus status = ReportStatus.OPEN;

    // Collected chat messages (only for relevant types, e.g., chat report)
    public List<ChatMessage> chat = new ArrayList<>();

    public Report() {}

    public Report(long id, String reporter, String reported, ReportType rt, String reason, long timestamp) {
        this.id = id;
        this.reporter = reporter;
        this.reported = reported;
        this.typeId = rt.typeId;
        this.typeDisplay = rt.typeDisplay;
        this.categoryId = rt.categoryId;
        this.categoryDisplay = rt.categoryDisplay;
        this.reason = reason;
        this.timestamp = timestamp;
        this.count = 1;
    }

    public boolean isOpen() { return status == ReportStatus.OPEN; }
}
