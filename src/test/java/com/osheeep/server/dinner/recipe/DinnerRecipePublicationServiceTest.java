package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.moderation.RecipeTextSafetyGateway;
import com.osheeep.server.dinner.recipe.moderation.RecipeTextSafetyResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipePublicationServiceTest {

    @Mock private DinnerRecipePublishSnapshotLoader snapshotLoader;
    @Mock private WechatUserIdentityMapper identityMapper;
    @Mock private RecipeTextSafetyGateway gateway;
    @Mock private DinnerRecipePublishTransaction transaction;

    @Test
    void checksTextBeforeEnteringPublishTransaction() {
        DinnerRecipePublicationService service = new DinnerRecipePublicationService(
                snapshotLoader, identityMapper, gateway, transaction);
        RecipePublishSnapshot snapshot = completeSnapshot();
        when(snapshotLoader.loadForModeration(7L, 101L, 4L)).thenReturn(snapshot);
        when(identityMapper.selectOne(any())).thenReturn(identity());
        when(gateway.check("openid-7", snapshot.name(), snapshot.moderationText()))
                .thenReturn(RecipeTextSafetyResult.PASS);
        when(transaction.publishChecked(7L, 101L, 4L)).thenReturn(publishedResponse());

        assertThat(service.publish(7L, 101L, 4L).status()).isEqualTo("PUBLISHED");

        InOrder order = inOrder(snapshotLoader, gateway, transaction);
        order.verify(snapshotLoader).loadForModeration(7L, 101L, 4L);
        order.verify(gateway).check("openid-7", snapshot.name(), snapshot.moderationText());
        order.verify(transaction).publishChecked(7L, 101L, 4L);
    }

    @Test
    void rejectedContentNeverStartsPublishTransaction() {
        DinnerRecipePublicationService service = new DinnerRecipePublicationService(
                snapshotLoader, identityMapper, gateway, transaction);
        RecipePublishSnapshot snapshot = completeSnapshot();
        when(snapshotLoader.loadForModeration(7L, 101L, 4L)).thenReturn(snapshot);
        when(identityMapper.selectOne(any())).thenReturn(identity());
        when(gateway.check(any(), any(), any())).thenReturn(RecipeTextSafetyResult.REJECT);

        assertThatThrownBy(() -> service.publish(7L, 101L, 4L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_CONTENT_REJECTED));
        verifyNoInteractions(transaction);
    }

    @Test
    void missingWechatIdentityLeavesGatewayAndTransactionUntouched() {
        DinnerRecipePublicationService service = new DinnerRecipePublicationService(
                snapshotLoader, identityMapper, gateway, transaction);
        when(snapshotLoader.loadForModeration(7L, 101L, 4L)).thenReturn(completeSnapshot());
        when(identityMapper.selectOne(any())).thenReturn(null);

        assertModerationUnavailable(() -> service.publish(7L, 101L, 4L));

        verifyNoInteractions(gateway, transaction);
    }

    @Test
    void identityWithoutOpenidLeavesGatewayAndTransactionUntouched() {
        DinnerRecipePublicationService service = new DinnerRecipePublicationService(
                snapshotLoader, identityMapper, gateway, transaction);
        when(snapshotLoader.loadForModeration(7L, 101L, 4L)).thenReturn(completeSnapshot());
        WechatUserIdentityEntity identity = identity();
        identity.setOpenid(null);
        when(identityMapper.selectOne(any())).thenReturn(identity);

        assertModerationUnavailable(() -> service.publish(7L, 101L, 4L));

        verifyNoInteractions(gateway, transaction);
    }

    @Test
    void blankOpenidLeavesGatewayAndTransactionUntouched() {
        DinnerRecipePublicationService service = new DinnerRecipePublicationService(
                snapshotLoader, identityMapper, gateway, transaction);
        when(snapshotLoader.loadForModeration(7L, 101L, 4L)).thenReturn(completeSnapshot());
        WechatUserIdentityEntity identity = identity();
        identity.setOpenid("  ");
        when(identityMapper.selectOne(any())).thenReturn(identity);

        assertModerationUnavailable(() -> service.publish(7L, 101L, 4L));

        verifyNoInteractions(gateway, transaction);
    }

    private void assertModerationUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE));
    }

    private RecipePublishSnapshot completeSnapshot() {
        return new RecipePublishSnapshot(101L, 7L, 70L, 4L, "番茄炒蛋", "家常菜", "酸甜",
                2, 15, 9L, List.of(), null, "审核文本");
    }

    private WechatUserIdentityEntity identity() {
        WechatUserIdentityEntity identity = new WechatUserIdentityEntity();
        identity.setUserId(7L);
        identity.setOpenid("openid-7");
        return identity;
    }

    private RecipeDraftResponse publishedResponse() {
        return new RecipeDraftResponse(101L, "PUBLISHED", 5L, "番茄炒蛋", "家常菜", "酸甜",
                2, 15, List.of(), null, null, List.of(), null);
    }
}
