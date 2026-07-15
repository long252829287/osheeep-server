package com.osheeep.server.dinner.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeIngredientEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DinnerRecipeIngredientMapper extends BaseMapper<DinnerRecipeIngredientEntity> {
}
