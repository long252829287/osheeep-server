package com.osheeep.server.dinner.household.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
            + "ORDER BY id")
    List<DinnerHouseholdMemberEntity> selectActiveByHouseholdId(
            @Param("householdId") Long householdId);

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

    @Update("UPDATE dinner_household_members "
            + "SET status = #{status}, ended_at = #{endedAt}, ended_by = #{endedBy}, "
            + "end_reason = #{endReason}, version = version + 1 "
            + "WHERE id = #{membershipId} AND household_id = #{householdId} "
            + "AND user_id = #{userId} AND role = 'MEMBER' AND status = 'ACTIVE' "
            + "AND version = #{expectedVersion}")
    int endActiveMember(
            @Param("membershipId") Long membershipId,
            @Param("householdId") Long householdId,
            @Param("userId") Long userId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("status") String status,
            @Param("endedAt") LocalDateTime endedAt,
            @Param("endedBy") Long endedBy,
            @Param("endReason") String endReason);
}
