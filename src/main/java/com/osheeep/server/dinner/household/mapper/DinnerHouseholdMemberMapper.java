package com.osheeep.server.dinner.household.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerHouseholdMemberMapper extends BaseMapper<DinnerHouseholdMemberEntity> {
    @Select("SELECT * FROM dinner_household_members WHERE user_id = #{userId} FOR UPDATE")
    DinnerHouseholdMemberEntity selectByUserIdForUpdate(Long userId);
}
