package com.osheeep.server.dinner.household.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DinnerHouseholdMapper extends BaseMapper<DinnerHouseholdEntity> {
    @Select("SELECT * FROM dinner_households WHERE id = #{id} FOR UPDATE")
    DinnerHouseholdEntity selectByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE dinner_households SET name = #{name}, version = version + 1 "
            + "WHERE id = #{householdId} AND status = 'ACTIVE' AND version = #{expectedVersion}")
    int renameActiveHousehold(
            @Param("householdId") Long householdId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("name") String name);

    @Update("UPDATE dinner_households "
            + "SET version = version + 1, invite_revision = invite_revision + 1 "
            + "WHERE id = #{householdId} AND status = 'ACTIVE' "
            + "AND version = #{expectedVersion} "
            + "AND invite_revision = #{expectedInviteRevision}")
    int advanceMembershipAndInviteRevision(
            @Param("householdId") Long householdId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("expectedInviteRevision") Long expectedInviteRevision);

    @Update("UPDATE dinner_households SET invite_revision = invite_revision + 1 "
            + "WHERE id = #{householdId} AND status = 'ACTIVE' "
            + "AND version = #{expectedVersion} "
            + "AND invite_revision = #{expectedInviteRevision}")
    int advanceInviteRevision(
            @Param("householdId") Long householdId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("expectedInviteRevision") Long expectedInviteRevision);
}
