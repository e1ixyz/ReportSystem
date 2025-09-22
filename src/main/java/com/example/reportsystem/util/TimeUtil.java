package com.example.reportsystem.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TimeUtil {
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    public static String formatDateTime(long epochMs) {
        return DATE_TIME.format(Instant.ofEpochMilli(epochMs));
    }

    public static String formatTime(long epochMs) {
        return TIME.format(Instant.ofEpochMilli(epochMs));
    }
}
