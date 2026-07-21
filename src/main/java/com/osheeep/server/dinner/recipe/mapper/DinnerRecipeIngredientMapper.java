package com.osheeep.server.dinner.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeIngredientEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerRecipeIngredientMapper extends BaseMapper<DinnerRecipeIngredientEntity> {

    @Select("""
            <script>
            SELECT ri.recipe_id AS recipeId,
                   ri.ingredient_id AS ingredientId,
                   i.name AS name,
                   ri.quantity AS quantity,
                   ri.unit AS unit,
                   ri.is_required AS required,
                   ri.sort_order AS sortOrder,
                   i.scope AS ingredientScope,
                   i.household_id AS ingredientHouseholdId,
                   i.status AS ingredientStatus
            FROM dinner_recipe_ingredients ri
            JOIN dinner_ingredients i ON i.id = ri.ingredient_id
            WHERE ri.recipe_id IN
            <foreach collection="recipeIds" item="recipeId" open="(" separator="," close=")">
                #{recipeId}
            </foreach>
            ORDER BY ri.recipe_id, ri.sort_order
            </script>
            """)
    List<DinnerRecipeIngredientRow> selectWithIngredientNames(
            @Param("recipeIds") List<Long> recipeIds);
}
