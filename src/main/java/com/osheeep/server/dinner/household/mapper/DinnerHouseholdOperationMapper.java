package com.osheeep.server.dinner.household.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerHouseholdOperationMapper extends BaseMapper<DinnerHouseholdOperationEntity> {
    @Select("SELECT * FROM dinner_household_operations "
            + "WHERE actor_id = #{actorId} AND idempotency_key = #{idempotencyKey} "
            + "ORDER BY id DESC LIMIT 1")
    DinnerHouseholdOperationEntity selectByActorAndIdempotencyKey(
            @Param("actorId") Long actorId,
            @Param("idempotencyKey") String idempotencyKey);
}
