package com.osheeep.server.dinner.ingredient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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

    @Select("SELECT * FROM dinner_household_inventory "
            + "WHERE household_id = #{householdId} ORDER BY id FOR UPDATE")
    List<DinnerHouseholdInventoryEntity> selectAllByHouseholdIdForUpdate(
            @Param("householdId") Long householdId);
}
