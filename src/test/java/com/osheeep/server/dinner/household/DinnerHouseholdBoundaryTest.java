package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdSnapshot;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerInviteCodeMapper;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyGateway;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyResult;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringJUnitConfig(DinnerHouseholdBoundaryTest.Config.class)
class DinnerHouseholdBoundaryTest {

    @Autowired private DinnerHouseholdService householdService;
    @Autowired private DinnerHouseholdWriteService writeService;
    @Autowired private RecordingTransactionManager transactionManager;
    @Autowired private DinnerHouseholdAccessService accessService;
    @Autowired private UserMapper userMapper;
    @Autowired private DinnerHouseholdMapper householdMapper;
    @Autowired private DinnerHouseholdMemberMapper memberMapper;
    @Autowired private DinnerInviteCodeMapper inviteMapper;
    @Autowired private DinnerRecipeMapper recipeMapper;
    @Autowired private InviteCodeGenerator inviteCodeGenerator;
    @Autowired private InviteCodeHasher inviteCodeHasher;
    @Autowired private WechatUserIdentityMapper identityMapper;
    @Autowired private DinnerTextSafetyGateway textSafetyGateway;

    @BeforeEach
    void resetCollaborators() {
        reset(accessService, userMapper, householdMapper, memberMapper, inviteMapper,
                recipeMapper, inviteCodeGenerator, identityMapper, textSafetyGateway);
        transactionManager.reset();
    }

    @Test
    void customNameModerationRunsOutsideButCreateWritesInsideOneTransaction() {
        prepareValidCreate();

        assertThat(AopUtils.isAopProxy(writeService)).isTrue();
        var result = householdService.create(7L, "新家庭");

        assertThat(result.household().name()).isEqualTo("新家庭");
        assertThat(result.inviteCode()).isEqualTo("DINNER 0123 4567");
        assertThat(transactionManager.commits()).isEqualTo(1);
        assertThat(transactionManager.rollbacks()).isZero();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void rejectedNameNeverStartsTheWriteTransaction() {
        prepareIdentity();
        when(textSafetyGateway.check("openid-7", "被拒绝", "被拒绝"))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return DinnerTextSafetyResult.REJECT;
                });

        assertThatThrownBy(() -> householdService.create(7L, "被拒绝"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_HOUSEHOLD_NAME_REJECTED));

        verifyNoInteractions(userMapper, householdMapper, memberMapper, inviteMapper,
                recipeMapper, inviteCodeGenerator);
        assertThat(transactionManager.commits()).isZero();
        assertThat(transactionManager.rollbacks()).isZero();
    }

    @Test
    void managementReadsHouseholdMembersAndInviteInOneRepeatableReadOnlyTransaction() {
        DinnerHouseholdEntity household = activeHousehold();
        DinnerHouseholdMemberEntity actor = activeOwner();
        when(accessService.findActiveSnapshot(7L)).thenAnswer(invocation -> {
            assertReadSnapshotTransaction();
            return new ActiveHouseholdSnapshot(actor, household);
        });
        when(memberMapper.selectActiveByHouseholdId(11L)).thenAnswer(invocation -> {
            assertReadSnapshotTransaction();
            return List.of(actor);
        });
        when(inviteMapper.selectOpenByHouseholdId(11L)).thenAnswer(invocation -> {
            assertReadSnapshotTransaction();
            return null;
        });

        assertThat(AopUtils.isAopProxy(householdService)).isTrue();
        var result = householdService.management(7L);

        assertThat(result.household().id()).isEqualTo(11L);
        assertThat(result.members()).hasSize(1);
        assertThat(result.invite().state()).isEqualTo("NONE");
        assertThat(transactionManager.commits()).isEqualTo(1);
        assertThat(transactionManager.rollbacks()).isZero();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void draftRebindFailureRollsBackTheWholeCreateTransaction() {
        prepareValidCreate();
        reset(recipeMapper);
        when(recipeMapper.selectUnboundDraftsByCreatorForUpdate(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT));

        assertThatThrownBy(() -> householdService.create(7L, "新家庭"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT));

        assertThat(transactionManager.commits()).isZero();
        assertThat(transactionManager.rollbacks()).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void joinDraftRebindFailureRollsBackInviteMembershipAndRevisions() {
        DinnerHouseholdEntity household = activeHousehold();
        household.setVersion(7L);
        household.setInviteRevision(4L);
        DinnerHouseholdMemberEntity owner = activeOwner();
        DinnerInviteCodeEntity invite = new DinnerInviteCodeEntity();
        invite.setId(21L);
        invite.setHouseholdId(11L);
        invite.setCodeHash(inviteCodeHasher.hash("DINNER 0123 4567"));
        invite.setExpiresAt(LocalDateTime.of(2099, 1, 1, 0, 0));
        invite.setCreatedBy(7L);

        when(userMapper.selectByIdForUpdate(8L)).thenReturn(activeUser(8L));
        when(inviteMapper.selectByCodeHash(invite.getCodeHash())).thenReturn(invite);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household);
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L)).thenReturn(List.of(owner));
        when(inviteMapper.selectByIdAndHouseholdIdForUpdate(21L, 11L)).thenReturn(invite);
        when(inviteMapper.consumeOpenInvite(any(), any(), any(), any())).thenReturn(1);
        when(memberMapper.insert(any(DinnerHouseholdMemberEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerHouseholdMemberEntity>getArgument(0).setId(32L);
            return 1;
        });
        when(householdMapper.advanceMembershipAndInviteRevision(11L, 7L, 4L)).thenReturn(1);
        when(recipeMapper.selectUnboundDraftsByCreatorForUpdate(8L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT));

        assertThatThrownBy(() -> householdService.join(8L, "DINNER 0123 4567"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT));

        verify(inviteMapper).consumeOpenInvite(any(), any(), any(), any());
        verify(memberMapper).insert(any(DinnerHouseholdMemberEntity.class));
        verify(householdMapper).advanceMembershipAndInviteRevision(11L, 7L, 4L);
        assertThat(transactionManager.commits()).isZero();
        assertThat(transactionManager.rollbacks()).isEqualTo(1);
    }

    private void prepareValidCreate() {
        prepareIdentity();
        when(textSafetyGateway.check("openid-7", "新家庭", "新家庭"))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return DinnerTextSafetyResult.PASS;
                });
        when(userMapper.selectByIdForUpdate(7L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return activeUser();
        });
        when(householdMapper.insert(any(DinnerHouseholdEntity.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            invocation.<DinnerHouseholdEntity>getArgument(0).setId(11L);
            return 1;
        });
        when(memberMapper.insert(any(DinnerHouseholdMemberEntity.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            invocation.<DinnerHouseholdMemberEntity>getArgument(0).setId(31L);
            return 1;
        });
        when(inviteCodeGenerator.generate()).thenReturn("DINNER 0123 4567");
        when(inviteMapper.insert(any(DinnerInviteCodeEntity.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            invocation.<DinnerInviteCodeEntity>getArgument(0).setId(21L);
            return 1;
        });
        when(recipeMapper.selectUnboundDraftsByCreatorForUpdate(7L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return List.of();
        });
    }

    private void prepareIdentity() {
        WechatUserIdentityEntity identity = new WechatUserIdentityEntity();
        identity.setUserId(7L);
        identity.setOpenid("openid-7");
        when(identityMapper.selectOne(any())).thenReturn(identity);
    }

    private UserEntity activeUser() {
        return activeUser(7L);
    }

    private UserEntity activeUser(Long id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setStatus("ACTIVE");
        return user;
    }

    private DinnerHouseholdEntity activeHousehold() {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(11L);
        household.setName("我们的小家");
        household.setTimezone("Asia/Shanghai");
        household.setStatus("ACTIVE");
        household.setVersion(1L);
        household.setInviteRevision(1L);
        return household;
    }

    private DinnerHouseholdMemberEntity activeOwner() {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setId(31L);
        member.setHouseholdId(11L);
        member.setUserId(7L);
        member.setRole("OWNER");
        member.setStatus("ACTIVE");
        member.setSeatNo(1);
        member.setVersion(1L);
        member.setJoinedAt(LocalDateTime.of(2026, 7, 22, 5, 0));
        member.setHistoryVisibleFrom(LocalDateTime.of(2026, 7, 22, 5, 0));
        return member;
    }

    private void assertReadSnapshotTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        DinnerHouseholdService householdService(
                DinnerHouseholdAccessService accessService,
                DinnerHouseholdMemberMapper memberMapper,
                DinnerInviteCodeMapper inviteMapper,
                DinnerHouseholdNameService nameService,
                DinnerHouseholdWriteService writeService
        ) {
            return new DinnerHouseholdService(
                    accessService, memberMapper, inviteMapper, nameService, writeService);
        }

        @Bean
        DinnerHouseholdWriteService writeService(
                UserMapper userMapper,
                DinnerHouseholdMapper householdMapper,
                DinnerHouseholdMemberMapper memberMapper,
                DinnerInviteCodeMapper inviteMapper,
                DinnerHouseholdAccessService accessService,
                DinnerHouseholdNameService nameService,
                DinnerRecipeMapper recipeMapper,
                InviteCodeGenerator inviteCodeGenerator,
                InviteCodeHasher inviteCodeHasher
        ) {
            return new DinnerHouseholdWriteService(
                    userMapper,
                    householdMapper,
                    memberMapper,
                    inviteMapper,
                    accessService,
                    nameService,
                    new DinnerHouseholdDraftLifecycleService(recipeMapper),
                    inviteCodeGenerator,
                    inviteCodeHasher);
        }

        @Bean
        DinnerHouseholdNameService nameService(
                WechatUserIdentityMapper identityMapper,
                DinnerTextSafetyGateway textSafetyGateway
        ) {
            return new DinnerHouseholdNameService(identityMapper, textSafetyGateway);
        }

        @Bean
        InviteCodeHasher inviteCodeHasher() {
            return new InviteCodeHasher("test-secret-at-least-32-characters");
        }

        @Bean DinnerHouseholdAccessService accessService() {
            return org.mockito.Mockito.mock(DinnerHouseholdAccessService.class);
        }

        @Bean UserMapper userMapper() {
            return org.mockito.Mockito.mock(UserMapper.class);
        }

        @Bean DinnerHouseholdMapper householdMapper() {
            return org.mockito.Mockito.mock(DinnerHouseholdMapper.class);
        }

        @Bean DinnerHouseholdMemberMapper memberMapper() {
            return org.mockito.Mockito.mock(DinnerHouseholdMemberMapper.class);
        }

        @Bean DinnerInviteCodeMapper inviteMapper() {
            return org.mockito.Mockito.mock(DinnerInviteCodeMapper.class);
        }

        @Bean DinnerRecipeMapper recipeMapper() {
            return org.mockito.Mockito.mock(DinnerRecipeMapper.class);
        }

        @Bean InviteCodeGenerator inviteCodeGenerator() {
            return org.mockito.Mockito.mock(InviteCodeGenerator.class);
        }

        @Bean WechatUserIdentityMapper identityMapper() {
            return org.mockito.Mockito.mock(WechatUserIdentityMapper.class);
        }

        @Bean DinnerTextSafetyGateway textSafetyGateway() {
            return org.mockito.Mockito.mock(DinnerTextSafetyGateway.class);
        }
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
        }

        void reset() {
            commits.set(0);
            rollbacks.set(0);
        }

        int commits() {
            return commits.get();
        }

        int rollbacks() {
            return rollbacks.get();
        }
    }
}
