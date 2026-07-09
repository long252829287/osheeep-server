package com.osheeep.server;

import com.osheeep.server.user.UserMapper;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestUserMapperConfig {

    @Bean
    public UserMapper userMapper() {
        return Mockito.mock(UserMapper.class);
    }
}
