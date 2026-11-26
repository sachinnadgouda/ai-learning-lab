package dev.demo.traffic.application.model;

import java.util.List;

public record Page<T>(List<T> items, int page, int size, long total) {
    public Page {
        items = List.copyOf(items);
    }

    public int totalPages() {
        return size == 0 ? 0 : (int) Math.ceil((double) total / size);
    }
}
