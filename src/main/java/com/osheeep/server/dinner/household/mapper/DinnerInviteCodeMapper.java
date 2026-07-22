package com.osheeep.server.dinner.household.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DinnerInviteCodeMapper extends BaseMapper<DinnerInviteCodeEntity> {
    @Select("SELECT * FROM dinner_invite_codes "
            + "WHERE code_hash = #{codeHash} AND consumed_at IS NULL AND revoked_at IS NULL "
            + "ORDER BY id DESC LIMIT 1")
    DinnerInviteCodeEntity selectByCodeHash(@Param("codeHash") String codeHash);

    @Select("SELECT * FROM dinner_invite_codes "
            + "WHERE household_id = #{householdId} "
            + "AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > #{now} "
            + "ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE")
    DinnerInviteCodeEntity selectActiveByHouseholdIdForUpdate(
            @Param("householdId") Long householdId,
            @Param("now") LocalDateTime now);

    @Select("SELECT * FROM dinner_invite_codes "
            + "WHERE household_id = #{householdId} "
            + "AND consumed_at IS NULL AND revoked_at IS NULL "
            + "ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE")
    DinnerInviteCodeEntity selectOpenByHouseholdIdForUpdate(
            @Param("householdId") Long householdId);

    @Select("SELECT * FROM dinner_invite_codes "
            + "WHERE household_id = #{householdId} "
            + "AND consumed_at IS NULL AND revoked_at IS NULL "
            + "ORDER BY created_at DESC, id DESC LIMIT 1")
    DinnerInviteCodeEntity selectOpenByHouseholdId(
            @Param("householdId") Long householdId);

    @Select("SELECT * FROM dinner_invite_codes "
            + "WHERE household_id = #{householdId} "
            + "AND consumed_at IS NULL AND revoked_at IS NULL "
            + "ORDER BY id FOR UPDATE")
    List<DinnerInviteCodeEntity> selectAllOpenByHouseholdIdForUpdate(
            @Param("householdId") Long householdId);

    @Select("SELECT * FROM dinner_invite_codes "
            + "WHERE id = #{inviteId} AND household_id = #{householdId} "
            + "AND consumed_at IS NULL AND revoked_at IS NULL "
            + "ORDER BY id ASC LIMIT 1 FOR UPDATE")
    DinnerInviteCodeEntity selectByIdAndHouseholdIdForUpdate(
            @Param("inviteId") Long inviteId,
            @Param("householdId") Long householdId);

    @Update("UPDATE dinner_invite_codes "
            + "SET consumed_at = #{consumedAt}, consumed_by = #{consumedBy} "
            + "WHERE id = #{inviteId} AND household_id = #{householdId} "
            + "AND consumed_at IS NULL AND revoked_at IS NULL")
    int consumeOpenInvite(
            @Param("inviteId") Long inviteId,
            @Param("householdId") Long householdId,
            @Param("consumedAt") LocalDateTime consumedAt,
            @Param("consumedBy") Long consumedBy);

    @Update("UPDATE dinner_invite_codes "
            + "SET revoked_at = #{revokedAt}, revocation_reason = #{reason} "
            + "WHERE id = #{inviteId} AND household_id = #{householdId} "
            + "AND consumed_at IS NULL AND revoked_at IS NULL")
    int revokeOpenInvite(
            @Param("inviteId") Long inviteId,
            @Param("householdId") Long householdId,
            @Param("revokedAt") LocalDateTime revokedAt,
            @Param("reason") String reason);
}
