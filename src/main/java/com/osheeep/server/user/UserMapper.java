package com.osheeep.server.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.user.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    @Select("SELECT * FROM users WHERE id = #{id} FOR UPDATE")
    UserEntity selectByIdForUpdate(Long id);
}
