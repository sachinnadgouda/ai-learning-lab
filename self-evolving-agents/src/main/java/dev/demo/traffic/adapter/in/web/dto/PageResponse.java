package dev.demo.traffic.adapter.in.web.dto;

import dev.demo.traffic.application.model.Page;
import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(page.items().stream().map(mapper).toList(), page.page(), page.size(), page.total(),
                page.totalPages());
    }
}
