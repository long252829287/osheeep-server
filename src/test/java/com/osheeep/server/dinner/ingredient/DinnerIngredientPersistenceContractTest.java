package com.osheeep.server.dinner.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DinnerIngredientPersistenceContractTest {

    @Test
    void inventoryExposesOptimisticVersionAndLockingLookup() throws Exception {
        DinnerHouseholdInventoryEntity item = new DinnerHouseholdInventoryEntity();
        item.setHouseholdId(11L);
        item.setIngredientId(3L);
        item.setQuantity(new BigDecimal("8.000"));
        item.setUnit("枚");
        item.setVersion(2L);

        assertThat(item.getVersion()).isEqualTo(2L);
        assertThat(DinnerHouseholdInventoryMapper.class.getMethod(
                "selectByHouseholdAndIngredientForUpdate", Long.class, Long.class)).isNotNull();
    }
}
