package com.osheeep.server.dinner.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerRecipeMapper extends BaseMapper<DinnerRecipeEntity> {

    @Select("SELECT * FROM dinner_recipes WHERE id = #{id} FOR UPDATE")
    DinnerRecipeEntity selectByIdForUpdate(@Param("id") Long id);
}
