package com.osheeep.server;

import com.osheeep.server.job.JobMapper;
import com.osheeep.server.thought.cluster.ThoughtClusterFragmentMapper;
import com.osheeep.server.thought.cluster.ThoughtClusterMapper;
import com.osheeep.server.thought.fragment.ThoughtFragmentMapper;
import com.osheeep.server.thought.outline.ThoughtOutlineMapper;
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

    @Bean
    public ThoughtClusterMapper thoughtClusterMapper() {
        return Mockito.mock(ThoughtClusterMapper.class);
    }

    @Bean
    public ThoughtClusterFragmentMapper thoughtClusterFragmentMapper() {
        return Mockito.mock(ThoughtClusterFragmentMapper.class);
    }

    @Bean
    public ThoughtFragmentMapper thoughtFragmentMapper() {
        return Mockito.mock(ThoughtFragmentMapper.class);
    }

    @Bean
    public ThoughtOutlineMapper thoughtOutlineMapper() {
        return Mockito.mock(ThoughtOutlineMapper.class);
    }

    @Bean
    public JobMapper jobMapper() {
        return Mockito.mock(JobMapper.class);
    }
}
