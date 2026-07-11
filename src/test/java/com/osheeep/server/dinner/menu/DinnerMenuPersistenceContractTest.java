package com.osheeep.server.dinner.menu;

import static org.assertj.core.api.Assertions.assertThat;

import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DinnerMenuPersistenceContractTest {

    @Test
    void menuEntitiesExposeVersionAndUniqueBusinessIdentity() throws Exception {
        DinnerMenuEntity menu = new DinnerMenuEntity();
        menu.setHouseholdId(11L);
        menu.setMenuDate(LocalDate.of(2026, 7, 11));
        menu.setStatus("DRAFT");
        menu.setVersion(0L);

        assertThat(menu.getVersion()).isZero();
        assertThat(DinnerMenuMapper.class.getMethod(
                "selectByHouseholdAndDateForUpdate", Long.class, LocalDate.class)).isNotNull();
    }
}
