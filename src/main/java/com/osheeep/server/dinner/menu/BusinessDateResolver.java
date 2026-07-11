package com.osheeep.server.dinner.menu;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

@Component
public class BusinessDateResolver {

    private static final LocalTime BUSINESS_DAY_START = LocalTime.of(4, 0);

    public LocalDate resolve(String timezone, Instant now) {
        ZonedDateTime local = now.atZone(ZoneId.of(timezone));
        LocalDate date = local.toLocalDate();
        return local.toLocalTime().isBefore(BUSINESS_DAY_START) ? date.minusDays(1) : date;
    }
}
