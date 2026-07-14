package com.osheeep.server.dinner.household.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerInviteCodeMapper extends BaseMapper<DinnerInviteCodeEntity> {
    @Select("SELECT * FROM dinner_invite_codes WHERE code_hash = #{codeHash} LIMIT 1")
    DinnerInviteCodeEntity selectByCodeHash(@Param("codeHash") String codeHash);

    @Select("SELECT * FROM dinner_invite_codes "
            + "WHERE household_id = #{householdId} AND revoked_at IS NULL AND expires_at > #{now} "
            + "ORDER BY id LIMIT 1 FOR UPDATE")
    DinnerInviteCodeEntity selectActiveByHouseholdIdForUpdate(
            @Param("householdId") Long householdId,
            @Param("now") LocalDateTime now);

    @Select("SELECT * FROM dinner_invite_codes "
            + "WHERE id = #{inviteId} AND household_id = #{householdId} FOR UPDATE")
    DinnerInviteCodeEntity selectByIdAndHouseholdIdForUpdate(
            @Param("inviteId") Long inviteId,
            @Param("householdId") Long householdId);
}
