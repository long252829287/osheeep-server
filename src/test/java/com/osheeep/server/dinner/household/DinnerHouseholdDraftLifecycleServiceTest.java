package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdDraftLifecycleService.LockedTerminationRecipes;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeIngredientEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
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
    @Mock private DinnerRecipeIngredientMapper recipeIngredientMapper;

    private DinnerHouseholdDraftLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new DinnerHouseholdDraftLifecycleService(
                recipeMapper, recipeIngredientMapper);
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

    @Test
    void locksOnlyTargetPersonalDraftIngredientAggregatesInStableOrder() {
        DinnerRecipeEntity targetDraft = boundRecipe(
                101L, ACTOR_USER_ID, "DRAFT", 3L);
        DinnerRecipeEntity partnerDraft = boundRecipe(102L, 8L, "DRAFT", 2L);
        DinnerRecipeEntity published = boundRecipe(
                103L, ACTOR_USER_ID, "PUBLISHED", 4L);
        DinnerRecipeIngredientEntity row = recipeIngredient(301L, 101L, 501L);
        when(recipeIngredientMapper.selectByRecipeIdsForUpdate(List.of(101L)))
                .thenReturn(List.of(row));

        List<DinnerRecipeIngredientEntity> result = service.lockPersonalDraftIngredients(
                ACTOR_USER_ID,
                HOUSEHOLD_ID,
                new LockedTerminationRecipes(
                        List.of(targetDraft, partnerDraft, published), List.of()));

        assertThat(result).containsExactly(row);
        verify(recipeIngredientMapper).selectByRecipeIdsForUpdate(List.of(101L));
    }

    @Test
    void detachesPersonalDraftsClearsHouseholdLineageAndRemovesOnlyCustomIngredients() {
        DinnerRecipeEntity householdSource = boundRecipe(
                201L, 8L, "PUBLISHED", 4L);
        DinnerRecipeEntity newDraft = boundRecipe(
                101L, ACTOR_USER_ID, "DRAFT", 3L);
        newDraft.setSourceRecipeId(201L);
        DinnerRecipeEntity systemBasedDraft = boundRecipe(
                102L, ACTOR_USER_ID, "DRAFT", 5L);
        systemBasedDraft.setSourceRecipeId(1L);
        systemBasedDraft.setRevisionOfRecipeId(201L);
        systemBasedDraft.setBasePublishedVersion(4L);
        DinnerRecipeEntity partnerDraft = boundRecipe(103L, 8L, "DRAFT", 2L);
        DinnerRecipeEntity systemSource = new DinnerRecipeEntity();
        systemSource.setId(1L);
        systemSource.setScope("SYSTEM");
        systemSource.setHouseholdId(null);
        List<DinnerRecipeEntity> recipes =
                List.of(newDraft, systemBasedDraft, partnerDraft, householdSource);
        when(recipeMapper.selectByHouseholdId(HOUSEHOLD_ID)).thenReturn(recipes);
        when(recipeMapper.selectByIdsForUpdate(List.of(1L, 101L, 102L, 103L, 201L)))
                .thenReturn(List.of(
                        systemSource,
                        newDraft,
                        systemBasedDraft,
                        partnerDraft,
                        householdSource));
        when(recipeMapper.detachOwnedDraft(
                101L, HOUSEHOLD_ID, ACTOR_USER_ID, 3L, null)).thenReturn(1);
        when(recipeMapper.detachOwnedDraft(
                102L, HOUSEHOLD_ID, ACTOR_USER_ID, 5L, 1L)).thenReturn(1);
        when(recipeIngredientMapper.deleteByIds(List.of(301L, 303L))).thenReturn(2);

        List<DinnerRecipeIngredientEntity> ingredientRows = List.of(
                recipeIngredient(301L, 101L, 501L),
                recipeIngredient(302L, 101L, 1L),
                recipeIngredient(303L, 102L, 502L));
        List<DinnerIngredientEntity> customIngredients = List.of(
                householdIngredient(501L),
                householdIngredient(502L));

        LockedTerminationRecipes lockedRecipes = service.lockTerminationRecipes(
                ACTOR_USER_ID, HOUSEHOLD_ID);
        service.detachPersonalDrafts(
                ACTOR_USER_ID,
                HOUSEHOLD_ID,
                lockedRecipes,
                ingredientRows,
                customIngredients);

        InOrder order = inOrder(recipeMapper, recipeIngredientMapper);
        order.verify(recipeMapper).selectByHouseholdId(HOUSEHOLD_ID);
        order.verify(recipeMapper).selectByIdsForUpdate(
                List.of(1L, 101L, 102L, 103L, 201L));
        order.verify(recipeMapper).detachOwnedDraft(
                101L, HOUSEHOLD_ID, ACTOR_USER_ID, 3L, null);
        order.verify(recipeMapper).detachOwnedDraft(
                102L, HOUSEHOLD_ID, ACTOR_USER_ID, 5L, 1L);
        order.verify(recipeIngredientMapper).deleteByIds(List.of(301L, 303L));
        verify(recipeMapper, never()).detachOwnedDraft(
                103L, HOUSEHOLD_ID, 8L, 2L, null);
        verify(recipeMapper, never()).detachOwnedDraft(
                201L, HOUSEHOLD_ID, 8L, 4L, null);
        verify(recipeMapper, never()).selectById(any());
        verify(recipeMapper, never()).selectByIdForUpdate(any());
    }

    @Test
    void locksHouseholdAndDistinctSystemSourcesInOneGloballyOrderedBatch() {
        DinnerRecipeEntity firstDraft = boundRecipe(
                101L, ACTOR_USER_ID, "DRAFT", 3L);
        firstDraft.setSourceRecipeId(9L);
        DinnerRecipeEntity secondDraft = boundRecipe(
                102L, ACTOR_USER_ID, "DRAFT", 4L);
        secondDraft.setSourceRecipeId(1L);
        DinnerRecipeEntity duplicateSourceDraft = boundRecipe(
                103L, ACTOR_USER_ID, "DRAFT", 5L);
        duplicateSourceDraft.setSourceRecipeId(9L);
        DinnerRecipeEntity sourceOne = systemRecipe(1L);
        DinnerRecipeEntity sourceNine = systemRecipe(9L);
        List<DinnerRecipeEntity> householdRecipes =
                List.of(firstDraft, secondDraft, duplicateSourceDraft);
        List<Long> globallyOrderedIds = List.of(1L, 9L, 101L, 102L, 103L);
        when(recipeMapper.selectByHouseholdId(HOUSEHOLD_ID))
                .thenReturn(householdRecipes);
        when(recipeMapper.selectByIdsForUpdate(globallyOrderedIds))
                .thenReturn(List.of(
                        sourceOne,
                        sourceNine,
                        firstDraft,
                        secondDraft,
                        duplicateSourceDraft));

        LockedTerminationRecipes result = service.lockTerminationRecipes(
                ACTOR_USER_ID, HOUSEHOLD_ID);

        assertThat(result.householdRecipes()).containsExactlyElementsOf(householdRecipes);
        assertThat(result.retainedSystemSourceRecipes())
                .containsExactly(sourceOne, sourceNine);
        InOrder order = inOrder(recipeMapper);
        order.verify(recipeMapper).selectByHouseholdId(HOUSEHOLD_ID);
        order.verify(recipeMapper).selectByIdsForUpdate(globallyOrderedIds);
        verify(recipeMapper, never()).selectByIdForUpdate(any());
    }

    @Test
    void emptyRecipeSetSkipsTheBatchQueryInsteadOfGeneratingAnEmptyInClause() {
        when(recipeMapper.selectByHouseholdId(HOUSEHOLD_ID)).thenReturn(List.of());

        LockedTerminationRecipes result = service.lockTerminationRecipes(
                ACTOR_USER_ID, HOUSEHOLD_ID);

        assertThat(result.householdRecipes()).isEmpty();
        assertThat(result.retainedSystemSourceRecipes()).isEmpty();
        verify(recipeMapper, never()).selectByIdsForUpdate(any());
    }

    @Test
    void rejectsCrossHouseholdDraftSourceBeforeAnyDetachWrite() {
        DinnerRecipeEntity draft = boundRecipe(101L, ACTOR_USER_ID, "DRAFT", 3L);
        draft.setSourceRecipeId(999L);
        DinnerRecipeEntity foreignSource = boundRecipe(999L, 9L, "PUBLISHED", 1L);
        foreignSource.setHouseholdId(22L);
        when(recipeMapper.selectByHouseholdId(HOUSEHOLD_ID))
                .thenReturn(List.of(draft));
        when(recipeMapper.selectByIdsForUpdate(List.of(101L, 999L)))
                .thenReturn(List.of(draft, foreignSource));

        assertHouseholdVersionConflict(() -> service.lockTerminationRecipes(
                ACTOR_USER_ID, HOUSEHOLD_ID));

        verify(recipeMapper, never()).detachOwnedDraft(
                any(), any(), any(), any(), isNull());
    }

    @Test
    void rejectsAHouseholdRecipeWhoseTerminationSnapshotChangedBeforeTheBatchLock() {
        DinnerRecipeEntity snapshot = boundRecipe(101L, ACTOR_USER_ID, "DRAFT", 3L);
        snapshot.setSourceRecipeId(1L);
        DinnerRecipeEntity changed = boundRecipe(101L, ACTOR_USER_ID, "DRAFT", 4L);
        changed.setSourceRecipeId(1L);
        DinnerRecipeEntity systemSource = systemRecipe(1L);
        when(recipeMapper.selectByHouseholdId(HOUSEHOLD_ID))
                .thenReturn(List.of(snapshot));
        when(recipeMapper.selectByIdsForUpdate(List.of(1L, 101L)))
                .thenReturn(List.of(systemSource, changed));

        assertHouseholdVersionConflict(() -> service.lockTerminationRecipes(
                ACTOR_USER_ID, HOUSEHOLD_ID));

        verify(recipeMapper, never()).detachOwnedDraft(
                any(), any(), any(), any(), isNull());
    }

    @Test
    void terminationDraftMethodsRequireTheExistingHouseholdTransaction()
            throws NoSuchMethodException {
        for (Method method : List.of(
                DinnerHouseholdDraftLifecycleService.class.getMethod(
                        "lockTerminationRecipes", Long.class, Long.class),
                DinnerHouseholdDraftLifecycleService.class.getMethod(
                        "lockPersonalDraftIngredients",
                        Long.class, Long.class, LockedTerminationRecipes.class),
                DinnerHouseholdDraftLifecycleService.class.getMethod(
                        "detachPersonalDrafts",
                        Long.class, Long.class, LockedTerminationRecipes.class,
                        List.class, List.class))) {
            Transactional transaction = method.getAnnotation(Transactional.class);
            assertThat(transaction).isNotNull();
            assertThat(transaction.propagation()).isEqualTo(Propagation.MANDATORY);
        }
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

    private void assertHouseholdVersionConflict(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call
    ) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT));
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

    private DinnerRecipeEntity boundRecipe(
            Long id,
            Long creatorId,
            String status,
            Long version
    ) {
        DinnerRecipeEntity recipe = draft(id, creatorId, version);
        recipe.setHouseholdId(HOUSEHOLD_ID);
        recipe.setStatus(status);
        return recipe;
    }

    private DinnerRecipeEntity systemRecipe(Long id) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(id);
        recipe.setScope("SYSTEM");
        return recipe;
    }

    private DinnerRecipeIngredientEntity recipeIngredient(
            Long id,
            Long recipeId,
            Long ingredientId
    ) {
        DinnerRecipeIngredientEntity row = new DinnerRecipeIngredientEntity();
        row.setId(id);
        row.setRecipeId(recipeId);
        row.setIngredientId(ingredientId);
        return row;
    }

    private DinnerIngredientEntity householdIngredient(Long id) {
        DinnerIngredientEntity ingredient = new DinnerIngredientEntity();
        ingredient.setId(id);
        ingredient.setScope("HOUSEHOLD");
        ingredient.setHouseholdId(HOUSEHOLD_ID);
        return ingredient;
    }
}
