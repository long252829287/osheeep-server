package com.osheeep.server.dinner.ingredient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DinnerIngredientMapper extends BaseMapper<DinnerIngredientEntity> {
}
