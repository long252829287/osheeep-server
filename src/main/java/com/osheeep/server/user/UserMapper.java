package com.osheeep.server.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    @Select("SELECT * FROM users WHERE id = #{id} FOR UPDATE")
    UserEntity selectByIdForUpdate(@Param("id") Long id);

    @Update("""
            UPDATE users
            SET username = #{username},
                email = NULL,
                password_hash = NULL,
                display_name = NULL,
                avatar_url = NULL,
                status = 'DELETED',
                deleted_at = #{deletedAt}
            WHERE id = #{id}
              AND status = 'ACTIVE'
              AND deleted_at IS NULL
            """)
    int anonymizeActiveUser(
            @Param("id") Long id,
            @Param("username") String username,
            @Param("deletedAt") LocalDateTime deletedAt
    );
}
