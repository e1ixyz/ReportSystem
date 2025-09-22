package com.example.reportsystem.model;

import java.util.Objects;

public class ReportType {
    public final String typeId;       // e.g., "player", "bug"
    public final String typeDisplay;  // e.g., "Player", "Bug"
    public final String categoryId;   // e.g., "chat", "cheating"
    public final String categoryDisplay;

    public ReportType(String typeId, String typeDisplay, String categoryId, String categoryDisplay) {
        this.typeId = typeId;
        this.typeDisplay = typeDisplay;
        this.categoryId = categoryId;
        this.categoryDisplay = categoryDisplay;
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeId, categoryId);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ReportType r)) return false;
        return typeId.equals(r.typeId) && categoryId.equals(r.categoryId);
    }
}
