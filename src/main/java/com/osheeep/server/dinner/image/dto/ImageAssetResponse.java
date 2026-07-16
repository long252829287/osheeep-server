package com.osheeep.server.dinner.image.dto;

import java.time.LocalDate;

public record ImageAssetResponse(
        Long id,
        String displayName,
        String listUrl,
        String detailUrl,
        String sourcePageUrl,
        String author,
        String licenseName,
        String licenseUrl,
        LocalDate acquiredOn,
        int width,
        int height
) {
}
