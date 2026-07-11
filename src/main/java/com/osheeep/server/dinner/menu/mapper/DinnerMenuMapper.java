package com.osheeep.server.dinner.menu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerMenuMapper extends BaseMapper<DinnerMenuEntity> {
    @Select("""
            SELECT * FROM dinner_menus
            WHERE household_id = #{householdId} AND menu_date = #{menuDate}
            FOR UPDATE
            """)
    DinnerMenuEntity selectByHouseholdAndDateForUpdate(Long householdId, LocalDate menuDate);
}
