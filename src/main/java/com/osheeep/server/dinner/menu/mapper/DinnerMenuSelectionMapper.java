package com.osheeep.server.dinner.menu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DinnerMenuSelectionMapper extends BaseMapper<DinnerMenuSelectionEntity> {

    @Select({
        "<script>",
        "SELECT * FROM dinner_menu_selections WHERE menu_id IN",
        "<foreach collection=\"menuIds\" item=\"menuId\" open=\"(\" separator=\",\" close=\")\">",
        "#{menuId}",
        "</foreach>",
        "ORDER BY menu_id, id FOR UPDATE",
        "</script>"
    })
    List<DinnerMenuSelectionEntity> selectByMenuIdsForUpdate(
            @Param("menuIds") List<Long> menuIds);

    @Delete({
        "<script>",
        "DELETE FROM dinner_menu_selections WHERE user_id = #{userId} AND menu_id IN",
        "<foreach collection=\"menuIds\" item=\"menuId\" open=\"(\" separator=\",\" close=\")\">",
        "#{menuId}",
        "</foreach>",
        "</script>"
    })
    int deleteByMenuIdsAndUserId(
            @Param("menuIds") List<Long> menuIds,
            @Param("userId") Long userId);
}
