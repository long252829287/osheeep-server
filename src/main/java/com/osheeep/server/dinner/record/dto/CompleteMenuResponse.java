package com.osheeep.server.dinner.record.dto;

import com.osheeep.server.dinner.menu.dto.TodayMenuResponse;

public record CompleteMenuResponse(Long recordId, TodayMenuResponse menu) {
}
