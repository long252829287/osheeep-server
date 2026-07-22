package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.moderation.RecipeModerationTextBuilder;
import com.osheeep.server.dinner.recipe.moderation.RecipeTextSafetyGateway;
import com.osheeep.server.dinner.recipe.moderation.RecipeTextSafetyResult;
import java.math.BigDecimal;
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
import org.springframework.dao.DuplicateKeyException;

@SpringJUnitConfig(DinnerRecipePublicationBoundaryTest.Config.class)
class DinnerRecipePublicationBoundaryTest {

    @Autowired private DinnerRecipePublicationService publicationService;
    @Autowired private DinnerRecipePublishTransaction transaction;
    @Autowired private RecordingTransactionManager transactionManager;
    @Autowired private DinnerRecipeAuthorizer authorizer;
    @Autowired private DinnerRecipeMapper recipeMapper;
    @Autowired private DinnerRecipeQueryService queryService;
    @Autowired private DinnerImageAssetService imageAssetService;
    @Autowired private WechatUserIdentityMapper identityMapper;
    @Autowired private RecipeTextSafetyGateway gateway;

    @BeforeEach
    void resetCollaborators() {
        org.mockito.Mockito.reset(authorizer, recipeMapper, queryService, imageAssetService,
                identityMapper, gateway);
        transactionManager.reset();
    }

    @Test
    void moderationRunsOutsideButTheLockedPublicationCommitsInsideATransaction() {
        prepareValidPublish();
        when(gateway.check(any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return RecipeTextSafetyResult.PASS;
        });
        when(recipeMapper.selectByIdForUpdate(101L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return draft();
        });
        when(authorizer.requireMembershipForUpdate(7L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return new RecipeAccess(7L, 70L);
        });
        AtomicInteger imageChecks = new AtomicInteger();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isEqualTo(imageChecks.incrementAndGet() == 2);
            return null;
        }).when(imageAssetService).requireApproved(9L);
        when(recipeMapper.updateById(any(DinnerRecipeEntity.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return 1;
        });

        assertThat(AopUtils.isAopProxy(transaction)).isTrue();
        assertThat(publicationService.publish(7L, 101L, 4L).status()).isEqualTo("PUBLISHED");

        assertThat(transactionManager.commits()).isEqualTo(1);
        assertThat(transactionManager.rollbacks()).isZero();
        assertThat(imageChecks).hasValue(2);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void duplicateKeyFromTheTransactionRollsBackWithoutCommit() {
        prepareValidPublish();
        when(gateway.check(any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return RecipeTextSafetyResult.PASS;
        });
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(draft());
        when(authorizer.requireMembershipForUpdate(7L)).thenReturn(new RecipeAccess(7L, 70L));
        when(recipeMapper.updateById(any(DinnerRecipeEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> publicationService.publish(7L, 101L, 4L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT));

        assertThat(transactionManager.commits()).isZero();
        assertThat(transactionManager.rollbacks()).isEqualTo(1);
    }

    @Test
    void membershipSwitchAfterModerationIsRejectedInsideTheTransactionWithoutMutation() {
        prepareValidPublish();
        when(gateway.check(any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return RecipeTextSafetyResult.PASS;
        });
        when(recipeMapper.selectByIdForUpdate(101L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return draft();
        });
        when(authorizer.requireMembershipForUpdate(7L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return new RecipeAccess(7L, 71L);
        });

        assertThatThrownBy(() -> publicationService.publish(7L, 101L, 4L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
        assertThat(transactionManager.commits()).isZero();
        assertThat(transactionManager.rollbacks()).isEqualTo(1);
    }

    private void prepareValidPublish() {
        RecipeAccess readAccess = new RecipeAccess(7L, 70L);
        when(authorizer.requireMembership(7L)).thenReturn(readAccess);
        when(authorizer.requireOwnedDraft(readAccess, 101L)).thenReturn(draft());
        when(queryService.detail(readAccess, 101L)).thenReturn(
                response("DRAFT", 4L), response("DRAFT", 4L), response("PUBLISHED", 5L));
        WechatUserIdentityEntity identity = new WechatUserIdentityEntity();
        identity.setUserId(7L);
        identity.setOpenid("openid-7");
        when(identityMapper.selectOne(any())).thenReturn(identity);
    }

    private DinnerRecipeEntity draft() {
        DinnerRecipeEntity draft = new DinnerRecipeEntity();
        draft.setId(101L);
        draft.setCreatorId(7L);
        draft.setHouseholdId(70L);
        draft.setVersion(4L);
        draft.setStatus("DRAFT");
        draft.setImageAssetId(9L);
        return draft;
    }

    private RecipeDraftResponse response(String status, long version) {
        return new RecipeDraftResponse(101L, status, version, "番茄炒蛋", "家常菜", "酸甜", 2, 15,
                List.of(new RecipeIngredientResponse(1L, "番茄", BigDecimal.ONE, "个", true, 0)),
                new RecipeMethodResponse(201L, "家常炒", "炒",
                        List.of(new RecipeMethodStepResponse("切番茄", 0))),
                null, List.of(), null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean DinnerRecipePublicationService publicationService(
                DinnerRecipePublishSnapshotLoader loader,
                WechatUserIdentityMapper identityMapper,
                RecipeTextSafetyGateway gateway,
                DinnerRecipePublishTransaction transaction
        ) {
            return new DinnerRecipePublicationService(loader, identityMapper, gateway, transaction);
        }

        @Bean DinnerRecipePublishSnapshotLoader snapshotLoader(
                DinnerRecipeAuthorizer authorizer,
                DinnerRecipeQueryService queryService,
                RecipeDraftValidator validator,
                RecipeModerationTextBuilder textBuilder,
                DinnerImageAssetService imageAssetService
        ) {
            return new DinnerRecipePublishSnapshotLoader(
                    authorizer, queryService, validator, textBuilder, imageAssetService);
        }

        @Bean DinnerRecipePublishTransaction transaction(
                DinnerRecipeMapper recipeMapper,
                DinnerRecipeAuthorizer authorizer,
                DinnerRecipeQueryService queryService,
                DinnerImageAssetService imageAssetService,
                RecipeDraftValidator validator
        ) {
            return new DinnerRecipePublishTransaction(
                    recipeMapper, authorizer, queryService, imageAssetService, validator);
        }

        @Bean RecipeDraftValidator validator() {
            return new RecipeDraftValidator();
        }

        @Bean RecipeModerationTextBuilder textBuilder() {
            return new RecipeModerationTextBuilder();
        }

        @Bean DinnerRecipeAuthorizer authorizer() { return org.mockito.Mockito.mock(DinnerRecipeAuthorizer.class); }
        @Bean DinnerRecipeMapper recipeMapper() { return org.mockito.Mockito.mock(DinnerRecipeMapper.class); }
        @Bean DinnerRecipeQueryService queryService() { return org.mockito.Mockito.mock(DinnerRecipeQueryService.class); }
        @Bean DinnerImageAssetService imageAssetService() { return org.mockito.Mockito.mock(DinnerImageAssetService.class); }
        @Bean WechatUserIdentityMapper identityMapper() { return org.mockito.Mockito.mock(WechatUserIdentityMapper.class); }
        @Bean RecipeTextSafetyGateway gateway() { return org.mockito.Mockito.mock(RecipeTextSafetyGateway.class); }
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
