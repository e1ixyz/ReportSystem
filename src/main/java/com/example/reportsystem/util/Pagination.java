package com.example.reportsystem.util;

import java.util.List;

public class Pagination {
    public static <T> List<T> paginate(List<T> list, int perPage, int page) {
        int from = Math.max(0, (page - 1) * perPage);
        int to = Math.min(list.size(), from + perPage);
        if (from >= to) return List.of();
        return list.subList(from, to);
    }
}
