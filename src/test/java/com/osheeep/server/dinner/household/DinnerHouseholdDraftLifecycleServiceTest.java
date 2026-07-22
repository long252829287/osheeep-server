package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class DinnerHouseholdDraftLifecycleServiceTest {

    private static final Long ACTOR_USER_ID = 7L;
    private static final Long HOUSEHOLD_ID = 11L;

    @Mock private DinnerRecipeMapper recipeMapper;

    private DinnerHouseholdDraftLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new DinnerHouseholdDraftLifecycleService(recipeMapper);
    }

    @Test
    void doesNotWriteWhenThereAreNoUnboundDrafts() {
        when(recipeMapper.selectUnboundDraftsByCreatorForUpdate(ACTOR_USER_ID))
                .thenReturn(List.of());

        service.rebindUnboundDrafts(ACTOR_USER_ID, HOUSEHOLD_ID);

        verify(recipeMapper).selectUnboundDraftsByCreatorForUpdate(ACTOR_USER_ID);
        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
    }

    @Test
    void rebindsDraftsInAscendingIdOrderAndIncrementsEachVersionOnce() {
        DinnerRecipeEntity first = draft(101L, ACTOR_USER_ID, 3L);
        DinnerRecipeEntity second = draft(105L, ACTOR_USER_ID, 8L);
        when(recipeMapper.selectUnboundDraftsByCreatorForUpdate(ACTOR_USER_ID))
                .thenReturn(List.of(first, second));
        when(recipeMapper.updateById(any(DinnerRecipeEntity.class))).thenReturn(1);

        service.rebindUnboundDrafts(ACTOR_USER_ID, HOUSEHOLD_ID);

        InOrder order = inOrder(recipeMapper);
        order.verify(recipeMapper).selectUnboundDraftsByCreatorForUpdate(ACTOR_USER_ID);
        order.verify(recipeMapper).updateById(first);
        order.verify(recipeMapper).updateById(second);
        assertThat(first.getHouseholdId()).isEqualTo(HOUSEHOLD_ID);
        assertThat(first.getLastModifiedBy()).isEqualTo(ACTOR_USER_ID);
        assertThat(first.getVersion()).isEqualTo(4L);
        assertThat(second.getHouseholdId()).isEqualTo(HOUSEHOLD_ID);
        assertThat(second.getLastModifiedBy()).isEqualTo(ACTOR_USER_ID);
        assertThat(second.getVersion()).isEqualTo(9L);
    }

    @Test
    void rejectsDraftOwnedByAnotherCreatorBeforeWriting() {
        DinnerRecipeEntity invalid = draft(101L, 8L, 3L);

        assertInvalidDraft(invalid);
    }

    @Test
    void rejectsDraftOutsideHouseholdScopeBeforeWriting() {
        DinnerRecipeEntity invalid = draft(101L, ACTOR_USER_ID, 3L);
        invalid.setScope("SYSTEM");

        assertInvalidDraft(invalid);
    }

    @Test
    void rejectsNonDraftRecipeBeforeWriting() {
        DinnerRecipeEntity invalid = draft(101L, ACTOR_USER_ID, 3L);
        invalid.setStatus("PUBLISHED");

        assertInvalidDraft(invalid);
    }

    @Test
    void rejectsRecipeAlreadyBoundToAHouseholdBeforeWriting() {
        DinnerRecipeEntity invalid = draft(101L, ACTOR_USER_ID, 3L);
        invalid.setHouseholdId(99L);

        assertInvalidDraft(invalid);
    }

    @Test
    void rejectsDraftWithoutVersionBeforeWriting() {
        DinnerRecipeEntity invalid = draft(101L, ACTOR_USER_ID, null);

        assertInvalidDraft(invalid);
    }

    @Test
    void rejectsRowsThatAreNotLockedInAscendingIdOrder() {
        DinnerRecipeEntity higherId = draft(105L, ACTOR_USER_ID, 8L);
        DinnerRecipeEntity lowerId = draft(101L, ACTOR_USER_ID, 3L);
        when(recipeMapper.selectUnboundDraftsByCreatorForUpdate(ACTOR_USER_ID))
                .thenReturn(List.of(higherId, lowerId));
        when(recipeMapper.updateById(higherId)).thenReturn(1);

        assertRecipeVersionConflict(
                () -> service.rebindUnboundDrafts(ACTOR_USER_ID, HOUSEHOLD_ID));
    }

    @Test
    void mapsLostUpdateToRecipeVersionConflict() {
        DinnerRecipeEntity draft = draft(101L, ACTOR_USER_ID, 3L);
        when(recipeMapper.selectUnboundDraftsByCreatorForUpdate(ACTOR_USER_ID))
                .thenReturn(List.of(draft));
        when(recipeMapper.updateById(draft)).thenReturn(0);

        assertRecipeVersionConflict(
                () -> service.rebindUnboundDrafts(ACTOR_USER_ID, HOUSEHOLD_ID));
    }

    @Test
    void mapsLockFailureWhileSelectingToRecipeVersionConflict() {
        when(recipeMapper.selectUnboundDraftsByCreatorForUpdate(ACTOR_USER_ID))
                .thenThrow(new CannotAcquireLockException("lock wait timeout"));

        assertRecipeVersionConflict(
                () -> service.rebindUnboundDrafts(ACTOR_USER_ID, HOUSEHOLD_ID));

        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
    }

    @Test
    void mapsLockFailureWhileUpdatingToRecipeVersionConflict() {
        DinnerRecipeEntity draft = draft(101L, ACTOR_USER_ID, 3L);
        when(recipeMapper.selectUnboundDraftsByCreatorForUpdate(ACTOR_USER_ID))
                .thenReturn(List.of(draft));
        when(recipeMapper.updateById(draft))
                .thenThrow(new CannotAcquireLockException("deadlock victim"));

        assertRecipeVersionConflict(
                () -> service.rebindUnboundDrafts(ACTOR_USER_ID, HOUSEHOLD_ID));
    }

    @Test
    void rebindRequiresAnExistingTransaction() throws NoSuchMethodException {
        Method method = DinnerHouseholdDraftLifecycleService.class.getMethod(
                "rebindUnboundDrafts", Long.class, Long.class);
        Transactional transaction = method.getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private void assertInvalidDraft(DinnerRecipeEntity invalid) {
        when(recipeMapper.selectUnboundDraftsByCreatorForUpdate(ACTOR_USER_ID))
                .thenReturn(List.of(invalid));

        assertRecipeVersionConflict(
                () -> service.rebindUnboundDrafts(ACTOR_USER_ID, HOUSEHOLD_ID));

        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
    }

    private void assertRecipeVersionConflict(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call
    ) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT));
    }

    private DinnerRecipeEntity draft(Long id, Long creatorId, Long version) {
        DinnerRecipeEntity draft = new DinnerRecipeEntity();
        draft.setId(id);
        draft.setScope("HOUSEHOLD");
        draft.setHouseholdId(null);
        draft.setCreatorId(creatorId);
        draft.setLastModifiedBy(null);
        draft.setStatus("DRAFT");
        draft.setVersion(version);
        return draft;
    }
}
