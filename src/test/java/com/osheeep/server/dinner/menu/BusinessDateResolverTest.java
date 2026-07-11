package com.osheeep.server.dinner.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BusinessDateResolverTest {

    private final BusinessDateResolver resolver = new BusinessDateResolver();

    @ParameterizedTest
    @CsvSource({
            "2026-07-10T19:59:59Z,2026-07-10",
            "2026-07-10T20:00:00Z,2026-07-11"
    })
    void shanghaiBusinessDayChangesAtFourAm(String instant, String expected) {
        assertThat(resolver.resolve("Asia/Shanghai", Instant.parse(instant)))
                .isEqualTo(LocalDate.parse(expected));
    }
}
