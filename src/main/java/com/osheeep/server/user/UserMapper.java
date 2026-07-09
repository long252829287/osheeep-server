package com.osheeep.server.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.user.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
