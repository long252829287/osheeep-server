package com.osheeep.server.dinner.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DinnerRecipeMapper extends BaseMapper<DinnerRecipeEntity> {
}
