package com.osheeep.server.dinner.household;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeIngredientEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerHouseholdDraftLifecycleService {

    private static final Set<String> HOUSEHOLD_RECIPE_STATUSES =
            Set.of("DRAFT", "PUBLISHED", "ARCHIVED");

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeIngredientMapper recipeIngredientMapper;

    @Autowired
    public DinnerHouseholdDraftLifecycleService(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeIngredientMapper recipeIngredientMapper
    ) {
        this.recipeMapper = recipeMapper;
        this.recipeIngredientMapper = recipeIngredientMapper;
    }

    DinnerHouseholdDraftLifecycleService(DinnerRecipeMapper recipeMapper) {
        this(recipeMapper, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rebindUnboundDrafts(Long actorUserId, Long householdId) {
        try {
            List<DinnerRecipeEntity> drafts =
                    recipeMapper.selectUnboundDraftsByCreatorForUpdate(actorUserId);
            Long previousId = null;
            for (DinnerRecipeEntity draft : drafts) {
                if (!isRebindable(draft, actorUserId)
                        || (previousId != null && draft.getId() <= previousId)) {
                    throw recipeVersionConflict();
                }
                draft.setHouseholdId(householdId);
                draft.setLastModifiedBy(actorUserId);
                draft.setVersion(draft.getVersion() + 1L);
                if (recipeMapper.updateById(draft) != 1) {
                    throw recipeVersionConflict();
                }
                previousId = draft.getId();
            }
        } catch (PessimisticLockingFailureException exception) {
            throw recipeVersionConflict();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LockedTerminationRecipes lockTerminationRecipes(
            Long targetUserId,
            Long householdId
    ) {
        List<DinnerRecipeEntity> householdSnapshot =
                recipeMapper.selectByHouseholdId(householdId);
        validateHouseholdRecipes(householdId, householdSnapshot);
        Set<Long> householdRecipeIds = recipeIds(householdSnapshot);
        List<Long> retainedSourceRecipeIds = externalSourceRecipeIds(
                personalDrafts(targetUserId, householdId, householdSnapshot),
                householdRecipeIds);
        List<Long> recipeIdsToLock = new ArrayList<>(
                householdRecipeIds.size() + retainedSourceRecipeIds.size());
        recipeIdsToLock.addAll(householdRecipeIds);
        recipeIdsToLock.addAll(retainedSourceRecipeIds);
        recipeIdsToLock.sort(Long::compareTo);
        if (recipeIdsToLock.isEmpty()) {
            return new LockedTerminationRecipes(List.of(), List.of());
        }

        List<DinnerRecipeEntity> lockedRecipes =
                recipeMapper.selectByIdsForUpdate(recipeIdsToLock);
        if (lockedRecipes == null || lockedRecipes.size() != recipeIdsToLock.size()) {
            throw terminationConflict();
        }
        Map<Long, DinnerRecipeEntity> snapshotById = new HashMap<>();
        for (DinnerRecipeEntity snapshot : householdSnapshot) {
            snapshotById.put(snapshot.getId(), snapshot);
        }
        Set<Long> retainedSourceRecipeIdSet = Set.copyOf(retainedSourceRecipeIds);
        List<DinnerRecipeEntity> lockedHouseholdRecipes = new ArrayList<>();
        List<DinnerRecipeEntity> lockedSystemSources = new ArrayList<>();
        for (int index = 0; index < recipeIdsToLock.size(); index++) {
            Long expectedId = recipeIdsToLock.get(index);
            DinnerRecipeEntity locked = lockedRecipes.get(index);
            if (locked == null || !Objects.equals(expectedId, locked.getId())) {
                throw terminationConflict();
            }
            DinnerRecipeEntity snapshot = snapshotById.get(expectedId);
            if (snapshot != null) {
                if (!isValidHouseholdRecipe(locked, householdId)
                        || !sameTerminationSnapshot(snapshot, locked)) {
                    throw terminationConflict();
                }
                lockedHouseholdRecipes.add(locked);
            } else {
                if (!retainedSourceRecipeIdSet.contains(expectedId)
                        || !isValidSystemSource(locked)) {
                    throw terminationConflict();
                }
                lockedSystemSources.add(locked);
            }
        }
        LockedTerminationRecipes result = new LockedTerminationRecipes(
                lockedHouseholdRecipes, lockedSystemSources);
        validateLockedTerminationRecipes(targetUserId, householdId, result);
        return result;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<DinnerRecipeIngredientEntity> lockPersonalDraftIngredients(
            Long targetUserId,
            Long householdId,
            LockedTerminationRecipes lockedRecipes
    ) {
        List<Long> draftIds = validateLockedTerminationRecipes(
                        targetUserId, householdId, lockedRecipes)
                .personalDrafts()
                .stream()
                .map(DinnerRecipeEntity::getId)
                .toList();
        if (draftIds.isEmpty()) {
            return List.of();
        }
        List<DinnerRecipeIngredientEntity> rows =
                recipeIngredientMapper.selectByRecipeIdsForUpdate(draftIds);
        Long previousRecipeId = null;
        Long previousId = null;
        Set<Long> allowedDraftIds = Set.copyOf(draftIds);
        for (DinnerRecipeIngredientEntity row : rows) {
            if (row == null
                    || row.getId() == null
                    || row.getRecipeId() == null
                    || row.getIngredientId() == null
                    || !allowedDraftIds.contains(row.getRecipeId())
                    || (previousRecipeId != null
                    && (row.getRecipeId() < previousRecipeId
                    || (row.getRecipeId().equals(previousRecipeId)
                    && row.getId() <= previousId)))) {
                throw terminationConflict();
            }
            previousRecipeId = row.getRecipeId();
            previousId = row.getId();
        }
        return List.copyOf(rows);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void detachPersonalDrafts(
            Long targetUserId,
            Long householdId,
            LockedTerminationRecipes lockedRecipes,
            List<DinnerRecipeIngredientEntity> lockedRecipeIngredients,
            List<DinnerIngredientEntity> lockedHouseholdIngredients
    ) {
        LockedTerminationRecipeView lockedRecipeView =
                validateLockedTerminationRecipes(
                        targetUserId, householdId, lockedRecipes);
        List<DinnerRecipeEntity> drafts = lockedRecipeView.personalDrafts();
        Set<Long> householdRecipeIds = lockedRecipeView.householdRecipeIds();
        Set<Long> retainedSystemSourceRecipeIds =
                lockedRecipeView.retainedSystemSourceRecipeIds();

        Set<Long> draftIds = new HashSet<>();
        for (DinnerRecipeEntity draft : drafts) {
            draftIds.add(draft.getId());
            Long retainedSourceRecipeId = retainedSourceRecipeId(
                    draft.getSourceRecipeId(),
                    householdRecipeIds,
                    retainedSystemSourceRecipeIds);
            if (recipeMapper.detachOwnedDraft(
                    draft.getId(),
                    householdId,
                    targetUserId,
                    draft.getVersion(),
                    retainedSourceRecipeId) != 1) {
                throw terminationConflict();
            }
        }

        Set<Long> householdIngredientIds = validateHouseholdIngredients(
                householdId, lockedHouseholdIngredients);
        List<Long> removedIngredientRowIds = lockedRecipeIngredients.stream()
                .filter(row -> draftIds.contains(row.getRecipeId()))
                .filter(row -> householdIngredientIds.contains(row.getIngredientId()))
                .map(DinnerRecipeIngredientEntity::getId)
                .toList();
        if (!removedIngredientRowIds.isEmpty()
                && recipeIngredientMapper.deleteByIds(removedIngredientRowIds)
                != removedIngredientRowIds.size()) {
            throw terminationConflict();
        }
    }

    private LockedTerminationRecipeView validateLockedTerminationRecipes(
            Long targetUserId,
            Long householdId,
            LockedTerminationRecipes lockedRecipes
    ) {
        if (lockedRecipes == null) {
            throw terminationConflict();
        }
        List<DinnerRecipeEntity> householdRecipes = lockedRecipes.householdRecipes();
        validateHouseholdRecipes(householdId, householdRecipes);
        Set<Long> householdRecipeIds = recipeIds(householdRecipes);
        List<DinnerRecipeEntity> personalDrafts = personalDrafts(
                targetUserId, householdId, householdRecipes);

        Set<Long> retainedSystemSourceRecipeIds = new HashSet<>();
        Long previousSourceId = null;
        for (DinnerRecipeEntity source : lockedRecipes.retainedSystemSourceRecipes()) {
            if (!isValidSystemSource(source)
                    || (previousSourceId != null && source.getId() <= previousSourceId)
                    || !retainedSystemSourceRecipeIds.add(source.getId())) {
                throw terminationConflict();
            }
            previousSourceId = source.getId();
        }
        if (!new HashSet<>(externalSourceRecipeIds(
                personalDrafts, householdRecipeIds))
                .equals(retainedSystemSourceRecipeIds)) {
            throw terminationConflict();
        }
        return new LockedTerminationRecipeView(
                personalDrafts,
                householdRecipeIds,
                Set.copyOf(retainedSystemSourceRecipeIds));
    }

    private void validateHouseholdRecipes(
            Long householdId,
            List<DinnerRecipeEntity> recipes
    ) {
        if (recipes == null) {
            throw terminationConflict();
        }
        Long previousId = null;
        for (DinnerRecipeEntity recipe : recipes) {
            if (!isValidHouseholdRecipe(recipe, householdId)
                    || (previousId != null && recipe.getId() <= previousId)) {
                throw terminationConflict();
            }
            previousId = recipe.getId();
        }
    }

    private boolean isValidHouseholdRecipe(
            DinnerRecipeEntity recipe,
            Long householdId
    ) {
        return recipe != null
                && recipe.getId() != null
                && Objects.equals(householdId, recipe.getHouseholdId())
                && "HOUSEHOLD".equals(recipe.getScope())
                && HOUSEHOLD_RECIPE_STATUSES.contains(recipe.getStatus())
                && recipe.getVersion() != null
                && recipe.getVersion() >= 1;
    }

    private boolean isValidSystemSource(DinnerRecipeEntity recipe) {
        return recipe != null
                && recipe.getId() != null
                && recipe.getId() >= 1
                && "SYSTEM".equals(recipe.getScope())
                && recipe.getHouseholdId() == null;
    }

    private boolean sameTerminationSnapshot(
            DinnerRecipeEntity snapshot,
            DinnerRecipeEntity locked
    ) {
        return Objects.equals(snapshot.getId(), locked.getId())
                && Objects.equals(snapshot.getScope(), locked.getScope())
                && Objects.equals(snapshot.getHouseholdId(), locked.getHouseholdId())
                && Objects.equals(snapshot.getCreatorId(), locked.getCreatorId())
                && Objects.equals(snapshot.getSourceRecipeId(), locked.getSourceRecipeId())
                && Objects.equals(snapshot.getRevisionOfRecipeId(),
                locked.getRevisionOfRecipeId())
                && Objects.equals(snapshot.getBasePublishedVersion(),
                locked.getBasePublishedVersion())
                && Objects.equals(snapshot.getStatus(), locked.getStatus())
                && Objects.equals(snapshot.getVersion(), locked.getVersion());
    }

    private List<DinnerRecipeEntity> personalDrafts(
            Long targetUserId,
            Long householdId,
            List<DinnerRecipeEntity> householdRecipes
    ) {
        return householdRecipes.stream()
                .filter(recipe -> "HOUSEHOLD".equals(recipe.getScope()))
                .filter(recipe -> "DRAFT".equals(recipe.getStatus()))
                .filter(recipe -> Objects.equals(targetUserId, recipe.getCreatorId()))
                .filter(recipe -> Objects.equals(householdId, recipe.getHouseholdId()))
                .peek(recipe -> {
                    if (recipe.getVersion() == null || recipe.getVersion() < 1) {
                        throw terminationConflict();
                    }
                })
                .toList();
    }

    private Set<Long> recipeIds(List<DinnerRecipeEntity> recipes) {
        Set<Long> recipeIds = new HashSet<>();
        for (DinnerRecipeEntity recipe : recipes) {
            if (recipe == null
                    || recipe.getId() == null
                    || !recipeIds.add(recipe.getId())) {
                throw terminationConflict();
            }
        }
        return recipeIds;
    }

    private List<Long> externalSourceRecipeIds(
            List<DinnerRecipeEntity> drafts,
            Set<Long> householdRecipeIds
    ) {
        return drafts.stream()
                .map(DinnerRecipeEntity::getSourceRecipeId)
                .filter(Objects::nonNull)
                .filter(sourceRecipeId -> !householdRecipeIds.contains(sourceRecipeId))
                .peek(sourceRecipeId -> {
                    if (sourceRecipeId < 1) {
                        throw terminationConflict();
                    }
                })
                .distinct()
                .sorted()
                .toList();
    }

    private Long retainedSourceRecipeId(
            Long sourceRecipeId,
            Set<Long> householdRecipeIds,
            Set<Long> retainedSystemSourceRecipeIds
    ) {
        if (sourceRecipeId == null || householdRecipeIds.contains(sourceRecipeId)) {
            return null;
        }
        if (!retainedSystemSourceRecipeIds.contains(sourceRecipeId)) {
            throw terminationConflict();
        }
        return sourceRecipeId;
    }

    private Set<Long> validateHouseholdIngredients(
            Long householdId,
            List<DinnerIngredientEntity> ingredients
    ) {
        Set<Long> ids = new HashSet<>();
        Long previousId = null;
        for (DinnerIngredientEntity ingredient : ingredients) {
            if (ingredient == null
                    || ingredient.getId() == null
                    || !"HOUSEHOLD".equals(ingredient.getScope())
                    || !Objects.equals(householdId, ingredient.getHouseholdId())
                    || (previousId != null && ingredient.getId() <= previousId)
                    || !ids.add(ingredient.getId())) {
                throw terminationConflict();
            }
            previousId = ingredient.getId();
        }
        return ids;
    }

    private boolean isRebindable(DinnerRecipeEntity draft, Long actorUserId) {
        return draft != null
                && draft.getId() != null
                && "HOUSEHOLD".equals(draft.getScope())
                && "DRAFT".equals(draft.getStatus())
                && actorUserId.equals(draft.getCreatorId())
                && draft.getHouseholdId() == null
                && draft.getVersion() != null
                && draft.getVersion() >= 1;
    }

    private BusinessException recipeVersionConflict() {
        return new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
    }

    private BusinessException terminationConflict() {
        return new BusinessException(ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);
    }

    public record LockedTerminationRecipes(
            List<DinnerRecipeEntity> householdRecipes,
            List<DinnerRecipeEntity> retainedSystemSourceRecipes
    ) {
        public LockedTerminationRecipes {
            householdRecipes = List.copyOf(householdRecipes);
            retainedSystemSourceRecipes = List.copyOf(retainedSystemSourceRecipes);
        }
    }

    private record LockedTerminationRecipeView(
            List<DinnerRecipeEntity> personalDrafts,
            Set<Long> householdRecipeIds,
            Set<Long> retainedSystemSourceRecipeIds
    ) {}
}
