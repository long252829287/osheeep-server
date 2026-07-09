package com.osheeep.server.common.api;

import java.util.List;

public record PageResult<T>(
        List<T> items,
        long total,
        long page,
        long pageSize
) {

    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
