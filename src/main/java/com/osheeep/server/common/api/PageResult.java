package com.osheeep.server.common.api;

import java.util.List;

public record PageResult<T>(
        List<T> records,
        long total,
        long page,
        long size
) {

    public PageResult {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
