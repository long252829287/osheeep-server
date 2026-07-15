package com.osheeep.server.dinner.ingredient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerHouseholdInventoryMapper
        extends BaseMapper<DinnerHouseholdInventoryEntity> {
    @Select("""
            SELECT * FROM dinner_household_inventory
            WHERE household_id = #{householdId} AND ingredient_id = #{ingredientId}
            FOR UPDATE
            """)
    DinnerHouseholdInventoryEntity selectByHouseholdAndIngredientForUpdate(
            Long householdId, Long ingredientId);
}
