package com.osheeep.server;

import com.osheeep.server.job.JobMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerInviteCodeMapper;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuActionMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.record.mapper.DinnerCookingRecordMapper;
import com.osheeep.server.dinner.record.mapper.DinnerRecordDishSnapshotMapper;
import com.osheeep.server.auth.wechat.WechatCode2SessionClient;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.thought.cluster.ThoughtClusterFragmentMapper;
import com.osheeep.server.thought.cluster.ThoughtClusterMapper;
import com.osheeep.server.thought.fragment.ThoughtFragmentMapper;
import com.osheeep.server.thought.outline.ThoughtOutlineMapper;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestUserMapperConfig {

    @Bean
    public UserMapper userMapper() {
        return Mockito.mock(UserMapper.class, invocation -> {
            if ("selectById".equals(invocation.getMethod().getName())) {
                Object id = invocation.getArgument(0);
                UserEntity user = new UserEntity();
                user.setId(Long.valueOf(id.toString()));
                user.setUsername("test_user_" + id);
                user.setStatus("ACTIVE");
                return user;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
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

    @Bean
    public DinnerHouseholdMapper dinnerHouseholdMapper() {
        return Mockito.mock(DinnerHouseholdMapper.class);
    }

    @Bean
    public DinnerHouseholdMemberMapper dinnerHouseholdMemberMapper() {
        return Mockito.mock(DinnerHouseholdMemberMapper.class);
    }

    @Bean
    public DinnerInviteCodeMapper dinnerInviteCodeMapper() {
        return Mockito.mock(DinnerInviteCodeMapper.class);
    }

    @Bean
    public DinnerIngredientMapper dinnerIngredientMapper() {
        return Mockito.mock(DinnerIngredientMapper.class);
    }

    @Bean
    public DinnerHouseholdInventoryMapper dinnerHouseholdInventoryMapper() {
        return Mockito.mock(DinnerHouseholdInventoryMapper.class);
    }

    @Bean
    public DinnerMenuMapper dinnerMenuMapper() {
        return Mockito.mock(DinnerMenuMapper.class);
    }

    @Bean
    public DinnerMenuSelectionMapper dinnerMenuSelectionMapper() {
        return Mockito.mock(DinnerMenuSelectionMapper.class);
    }

    @Bean
    public DinnerMenuActionMapper dinnerMenuActionMapper() {
        return Mockito.mock(DinnerMenuActionMapper.class);
    }

    @Bean
    public DinnerRecipeMapper dinnerRecipeMapper() {
        return Mockito.mock(DinnerRecipeMapper.class);
    }

    @Bean
    public DinnerCookingRecordMapper dinnerCookingRecordMapper() {
        return Mockito.mock(DinnerCookingRecordMapper.class);
    }

    @Bean
    public DinnerRecordDishSnapshotMapper dinnerRecordDishSnapshotMapper() {
        return Mockito.mock(DinnerRecordDishSnapshotMapper.class);
    }

    @Bean
    public WechatUserIdentityMapper wechatUserIdentityMapper() {
        return Mockito.mock(WechatUserIdentityMapper.class);
    }

    @Bean
    @Primary
    public WechatCode2SessionClient wechatCode2SessionClient() {
        return Mockito.mock(WechatCode2SessionClient.class);
    }
}
