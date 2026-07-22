package com.osheeep.server.dinner.household.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerHouseholdMemberMapper extends BaseMapper<DinnerHouseholdMemberEntity> {
    @Select("SELECT * FROM dinner_household_members "
            + "WHERE user_id = #{userId} AND status = 'ACTIVE' "
            + "ORDER BY id DESC LIMIT 1")
    DinnerHouseholdMemberEntity selectActiveByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM dinner_household_members "
            + "WHERE user_id = #{userId} AND status = 'ACTIVE' "
            + "ORDER BY id DESC LIMIT 1 FOR UPDATE")
    DinnerHouseholdMemberEntity selectByUserIdForUpdate(@Param("userId") Long userId);

    @Select("SELECT * FROM dinner_household_members "
            + "WHERE household_id = #{householdId} AND status = 'ACTIVE' "
            + "ORDER BY id FOR UPDATE")
    List<DinnerHouseholdMemberEntity> selectActiveByHouseholdIdForUpdate(
            @Param("householdId") Long householdId);

    @Select({
        "<script>",
        "SELECT * FROM dinner_household_members WHERE household_id = #{householdId}",
        "<choose>",
        "<when test='userIds != null and userIds.size() > 0'>",
        "AND user_id IN",
        "<foreach collection=\"userIds\" item=\"userId\" open=\"(\" separator=\",\" close=\")\">",
        "#{userId}",
        "</foreach>",
        "</when>",
        "<otherwise>AND 1 = 0</otherwise>",
        "</choose>",
        "ORDER BY user_id, joined_at, id",
        "</script>"
    })
    List<DinnerHouseholdMemberEntity> selectHistoryByHouseholdAndUserIds(
            @Param("householdId") Long householdId,
            @Param("userIds") List<Long> userIds);
}
