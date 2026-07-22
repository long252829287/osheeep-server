package com.osheeep.server.dinner.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DinnerRecipeMapper extends BaseMapper<DinnerRecipeEntity> {

    @Select("SELECT * FROM dinner_recipes WHERE id = #{id} FOR UPDATE")
    DinnerRecipeEntity selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM dinner_recipes "
            + "WHERE creator_id = #{creatorId} AND scope = 'HOUSEHOLD' "
            + "AND status = 'DRAFT' AND household_id IS NULL "
            + "ORDER BY id FOR UPDATE")
    List<DinnerRecipeEntity> selectUnboundDraftsByCreatorForUpdate(
            @Param("creatorId") Long creatorId);

    @Select("SELECT * FROM dinner_recipes WHERE household_id = #{householdId} ORDER BY id")
    List<DinnerRecipeEntity> selectByHouseholdId(
            @Param("householdId") Long householdId);

    @Select({
        "<script>",
        "SELECT * FROM dinner_recipes WHERE id IN",
        "<foreach collection=\"recipeIds\" item=\"recipeId\" open=\"(\" separator=\",\" close=\")\">",
        "#{recipeId}",
        "</foreach>",
        "ORDER BY id FOR UPDATE",
        "</script>"
    })
    List<DinnerRecipeEntity> selectByIdsForUpdate(
            @Param("recipeIds") List<Long> recipeIds);

    @Update("UPDATE dinner_recipes "
            + "SET household_id = NULL, "
            + "source_recipe_id = #{retainedSourceRecipeId,jdbcType=BIGINT}, "
            + "revision_of_recipe_id = NULL, base_published_version = NULL, "
            + "last_modified_by = #{creatorId}, version = version + 1 "
            + "WHERE id = #{recipeId} AND household_id = #{householdId} "
            + "AND creator_id = #{creatorId} AND scope = 'HOUSEHOLD' "
            + "AND status = 'DRAFT' AND version = #{expectedVersion}")
    int detachOwnedDraft(
            @Param("recipeId") Long recipeId,
            @Param("householdId") Long householdId,
            @Param("creatorId") Long creatorId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("retainedSourceRecipeId") Long retainedSourceRecipeId);
}
