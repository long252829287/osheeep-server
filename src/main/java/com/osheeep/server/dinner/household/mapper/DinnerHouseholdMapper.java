package com.osheeep.server.dinner.household.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerHouseholdMapper extends BaseMapper<DinnerHouseholdEntity> {
    @Select("SELECT * FROM dinner_households WHERE id = #{id} FOR UPDATE")
    DinnerHouseholdEntity selectByIdForUpdate(Long id);
}
