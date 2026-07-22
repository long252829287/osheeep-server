package com.osheeep.server.dinner.record;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.LockedHouseholdContext;
import com.osheeep.server.dinner.menu.BusinessDateResolver;
import com.osheeep.server.dinner.menu.DinnerMenuService;
import com.osheeep.server.dinner.menu.entity.DinnerMenuActionEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuActionMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.record.dto.CompleteMenuResponse;
import com.osheeep.server.dinner.record.dto.RecordDetailResponse;
import com.osheeep.server.dinner.record.dto.RecordDishResponse;
import com.osheeep.server.dinner.record.dto.RecordIngredientSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordMethodSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordMethodStepSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordSummaryResponse;
import com.osheeep.server.dinner.record.entity.DinnerCookingRecordEntity;
import com.osheeep.server.dinner.record.entity.DinnerRecordDishSnapshotEntity;
import com.osheeep.server.dinner.record.mapper.DinnerCookingRecordMapper;
import com.osheeep.server.dinner.record.mapper.DinnerRecordDishSnapshotMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DinnerRecordService {

    private static final String INCOMPLETE_SNAPSHOT =
            "Incomplete dinner record dish snapshot";
    private static final int MAX_SNAPSHOT_STEPS = 12;

    private final DinnerHouseholdAccessService householdAccessService;
    private final DinnerMenuMapper menuMapper;
    private final DinnerMenuSelectionMapper selectionMapper;
    private final DinnerMenuActionMapper actionMapper;
    private final DinnerCookingRecordMapper recordMapper;
    private final DinnerRecordDishSnapshotMapper snapshotMapper;
    private final DinnerMenuService menuService;
    private final DinnerRecordSnapshotAssembler snapshotAssembler;
    private final DinnerRecordSnapshotJsonCodec snapshotJsonCodec;
    private final BusinessDateResolver businessDateResolver;
    private final Clock clock;

    @Autowired
    public DinnerRecordService(
            DinnerHouseholdAccessService householdAccessService,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerMenuActionMapper actionMapper,
            DinnerCookingRecordMapper recordMapper,
            DinnerRecordDishSnapshotMapper snapshotMapper,
            DinnerMenuService menuService,
            DinnerRecordSnapshotAssembler snapshotAssembler,
            DinnerRecordSnapshotJsonCodec snapshotJsonCodec,
            BusinessDateResolver businessDateResolver
    ) {
        this(householdAccessService, menuMapper, selectionMapper, actionMapper,
                recordMapper, snapshotMapper, menuService, snapshotAssembler,
                snapshotJsonCodec, businessDateResolver, Clock.systemUTC());
    }

    DinnerRecordService(
            DinnerHouseholdAccessService householdAccessService,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerMenuActionMapper actionMapper,
            DinnerCookingRecordMapper recordMapper,
            DinnerRecordDishSnapshotMapper snapshotMapper,
            DinnerMenuService menuService,
            DinnerRecordSnapshotAssembler snapshotAssembler,
            DinnerRecordSnapshotJsonCodec snapshotJsonCodec,
            BusinessDateResolver businessDateResolver,
            Clock clock
    ) {
        this.householdAccessService = householdAccessService;
        this.menuMapper = menuMapper;
        this.selectionMapper = selectionMapper;
        this.actionMapper = actionMapper;
        this.recordMapper = recordMapper;
        this.snapshotMapper = snapshotMapper;
        this.menuService = menuService;
        this.snapshotAssembler = snapshotAssembler;
        this.snapshotJsonCodec = snapshotJsonCodec;
        this.businessDateResolver = businessDateResolver;
        this.clock = clock;
    }

    @Transactional
    public CompleteMenuResponse complete(Long userId, long expectedVersion, String idempotencyKey) {
        LockedHouseholdContext lockedContext =
                householdAccessService.lockActiveHouseholdContext(userId);
        ActiveHouseholdAccess access = lockedContext.access();
        LocalDate menuDate = businessDateResolver.resolve(access.timezone(), clock.instant());
        DinnerMenuEntity menu = lockMenuForUpdate(access.householdId(), menuDate);
        if (menu == null) {
            throw new BusinessException(ErrorCode.DINNER_MENU_NOT_CONFIRMED);
        }
        if (isPreMembershipCompletedMenu(menu, access.historyVisibleFrom())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        DinnerCookingRecordEntity existing = findRecord(menu.getId());
        if (existing != null) {
            requireVisibleRecord(access, existing);
            return new CompleteMenuResponse(
                    existing.getId(),
                    menuService.responseForLockedContext(userId, lockedContext, menu));
        }
        if (!Objects.equals(menu.getVersion(), expectedVersion)) {
            throw new BusinessException(ErrorCode.DINNER_MENU_VERSION_CONFLICT);
        }
        if (!"CONFIRMED".equals(menu.getStatus())) {
            throw new BusinessException(ErrorCode.DINNER_MENU_NOT_CONFIRMED);
        }

        List<DinnerMenuSelectionEntity> selections = selectionMapper.selectList(
                Wrappers.<DinnerMenuSelectionEntity>lambdaQuery()
                        .eq(DinnerMenuSelectionEntity::getMenuId, menu.getId()));
        List<DinnerRecordSnapshotAssembler.SnapshotDraft> snapshotDrafts =
                snapshotAssembler.assemble(access.householdId(), selections);
        List<EncodedSnapshotDraft> encodedSnapshotDrafts = snapshotDrafts.stream()
                .map(this::encodeSnapshotDraft)
                .toList();

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        DinnerCookingRecordEntity record = new DinnerCookingRecordEntity();
        record.setHouseholdId(access.householdId());
        record.setMenuId(menu.getId());
        record.setRecordDate(menu.getMenuDate());
        record.setCompletedBy(userId);
        record.setCompletedAt(now);
        requireVisibleRecord(access, record);
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException exception) {
            DinnerCookingRecordEntity winner = findRecord(menu.getId());
            if (winner == null) {
                throw exception;
            }
            requireVisibleRecord(access, winner);
            return new CompleteMenuResponse(
                    winner.getId(),
                    menuService.responseForLockedContext(userId, lockedContext, menu));
        }

        int sortOrder = 0;
        for (EncodedSnapshotDraft encoded : encodedSnapshotDrafts) {
            DinnerRecordSnapshotAssembler.SnapshotDraft draft = encoded.draft();
            DinnerRecordDishSnapshotEntity snapshot = new DinnerRecordDishSnapshotEntity();
            snapshot.setRecordId(record.getId());
            snapshot.setRecipeId(draft.recipeId());
            snapshot.setRecipeScope(draft.scope());
            snapshot.setRecipeVersion(draft.recipeVersion());
            snapshot.setName(draft.name());
            snapshot.setImagePath(draft.imagePath());
            snapshot.setCategory(draft.category());
            snapshot.setFlavor(draft.flavor());
            snapshot.setEstimatedMinutes(draft.estimatedMinutes());
            snapshot.setServings(draft.servings());
            snapshot.setMethodId(draft.methodId());
            snapshot.setMethodName(draft.methodName());
            snapshot.setCookingStyle(draft.cookingStyle());
            snapshot.setMethodStepsJson(encoded.methodStepsJson());
            snapshot.setIngredientsJson(encoded.ingredientsJson());
            snapshot.setSelectedByUserIds(encoded.selectedByUserIdsJson());
            snapshot.setSortOrder(sortOrder++);
            snapshotMapper.insert(snapshot);
        }

        menu.setStatus("COMPLETED");
        menu.setCompletedBy(userId);
        menu.setCompletedAt(now);
        menu.setVersion(menu.getVersion() + 1);
        menuMapper.updateById(menu);

        DinnerMenuActionEntity action = new DinnerMenuActionEntity();
        action.setMenuId(menu.getId());
        action.setActorId(userId);
        action.setActionType("COMPLETE");
        action.setIdempotencyKey(idempotencyKey);
        actionMapper.insert(action);
        return new CompleteMenuResponse(
                record.getId(),
                menuService.responseForLockedContext(userId, lockedContext, menu));
    }

    public List<RecordSummaryResponse> list(Long userId) {
        ActiveHouseholdAccess access = householdAccessService.requireActiveHousehold(userId);
        return recordMapper.selectList(Wrappers.<DinnerCookingRecordEntity>lambdaQuery()
                        .eq(DinnerCookingRecordEntity::getHouseholdId, access.householdId())
                        .ge(DinnerCookingRecordEntity::getCompletedAt,
                                access.historyVisibleFrom())
                        .orderByDesc(DinnerCookingRecordEntity::getCompletedAt)
                        .orderByDesc(DinnerCookingRecordEntity::getId))
                .stream()
                .map(record -> new RecordSummaryResponse(
                        record.getId(), record.getRecordDate(), record.getCompletedBy(),
                        instant(record.getCompletedAt()),
                        Math.toIntExact(snapshotMapper.selectCount(
                                Wrappers.<DinnerRecordDishSnapshotEntity>lambdaQuery()
                                        .eq(DinnerRecordDishSnapshotEntity::getRecordId, record.getId())))))
                .toList();
    }

    public RecordDetailResponse detail(Long userId, Long recordId) {
        ActiveHouseholdAccess access = householdAccessService.requireActiveHousehold(userId);
        DinnerCookingRecordEntity record = recordMapper.selectById(recordId);
        requireVisibleRecord(access, record);
        List<RecordDishResponse> dishes = snapshotMapper.selectList(
                        Wrappers.<DinnerRecordDishSnapshotEntity>lambdaQuery()
                                .eq(DinnerRecordDishSnapshotEntity::getRecordId, recordId)
                                .orderByAsc(DinnerRecordDishSnapshotEntity::getSortOrder))
                .stream()
                .map(snapshot -> recordDish(snapshot, userId))
                .toList();
        return new RecordDetailResponse(
                record.getId(), record.getRecordDate(), record.getCompletedBy(),
                instant(record.getCompletedAt()), dishes);
    }

    private DinnerCookingRecordEntity findRecord(Long menuId) {
        return recordMapper.selectOne(Wrappers.<DinnerCookingRecordEntity>lambdaQuery()
                .eq(DinnerCookingRecordEntity::getMenuId, menuId)
                .last("LIMIT 1"));
    }

    private DinnerMenuEntity lockMenuForUpdate(Long householdId, LocalDate menuDate) {
        try {
            return menuMapper.selectByHouseholdAndDateForUpdate(householdId, menuDate);
        } catch (PessimisticLockingFailureException exception) {
            throw new BusinessException(ErrorCode.DINNER_MENU_VERSION_CONFLICT);
        }
    }

    private void requireVisibleRecord(
            ActiveHouseholdAccess access,
            DinnerCookingRecordEntity record
    ) {
        if (record == null
                || !Objects.equals(access.householdId(), record.getHouseholdId())
                || record.getCompletedAt() == null
                || record.getCompletedAt().isBefore(access.historyVisibleFrom())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private boolean isPreMembershipCompletedMenu(
            DinnerMenuEntity menu,
            LocalDateTime historyVisibleFrom
    ) {
        return "COMPLETED".equals(menu.getStatus())
                && (menu.getCompletedAt() == null
                        || menu.getCompletedAt().isBefore(historyVisibleFrom));
    }

    private String toJsonArray(Set<Long> userIds) {
        return userIds.stream().sorted().map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private EncodedSnapshotDraft encodeSnapshotDraft(
            DinnerRecordSnapshotAssembler.SnapshotDraft draft
    ) {
        return new EncodedSnapshotDraft(
                draft,
                snapshotJsonCodec.writeSteps(draft.steps()),
                snapshotJsonCodec.writeIngredients(draft.ingredients()),
                toJsonArray(draft.selectedByUserIds()));
    }

    private String source(String selectedByUserIds, Long currentUserId) {
        String content = selectedByUserIds.substring(1, selectedByUserIds.length() - 1).trim();
        Set<Long> selectors = content.isEmpty()
                ? Set.of()
                : Arrays.stream(content.split(","))
                        .map(String::trim)
                        .map(Long::valueOf)
                        .collect(Collectors.toSet());
        if (selectors.size() > 1) {
            return "BOTH";
        }
        return selectors.contains(currentUserId) ? "ME" : "PARTNER";
    }

    private RecordDishResponse recordDish(
            DinnerRecordDishSnapshotEntity snapshot,
            Long currentUserId
    ) {
        List<RecordMethodStepSnapshotResponse> steps =
                snapshotJsonCodec.readSteps(snapshot.getMethodStepsJson());
        List<RecordIngredientSnapshotResponse> ingredients =
                snapshotJsonCodec.readIngredients(snapshot.getIngredientsJson());
        String scope;
        Long recipeVersion;
        Integer servings;
        RecordMethodSnapshotResponse method;

        if (isLegacySnapshot(snapshot)) {
            scope = "SYSTEM";
            recipeVersion = 1L;
            servings = null;
            method = null;
            ingredients = List.of();
        } else if ("HOUSEHOLD".equals(snapshot.getRecipeScope())) {
            validateHouseholdSnapshot(snapshot, steps, ingredients);
            scope = "HOUSEHOLD";
            recipeVersion = snapshot.getRecipeVersion();
            servings = snapshot.getServings();
            method = new RecordMethodSnapshotResponse(
                    snapshot.getMethodId(), snapshot.getMethodName(),
                    snapshot.getCookingStyle(), steps);
        } else if ("SYSTEM".equals(snapshot.getRecipeScope())) {
            validateSystemSnapshot(snapshot, steps, ingredients);
            scope = "SYSTEM";
            recipeVersion = 1L;
            servings = snapshot.getServings();
            method = null;
        } else {
            throw incompleteSnapshot();
        }
        return new RecordDishResponse(
                snapshot.getRecipeId(), snapshot.getName(), snapshot.getImagePath(),
                snapshot.getCategory(), snapshot.getFlavor(), snapshot.getEstimatedMinutes(),
                source(snapshot.getSelectedByUserIds(), currentUserId), scope, recipeVersion,
                servings, method, ingredients);
    }

    private boolean isLegacySnapshot(DinnerRecordDishSnapshotEntity snapshot) {
        return snapshot.getRecipeScope() == null
                && snapshot.getRecipeVersion() == null
                && snapshot.getServings() == null
                && snapshot.getMethodId() == null
                && !StringUtils.hasText(snapshot.getMethodName())
                && !StringUtils.hasText(snapshot.getCookingStyle())
                && !StringUtils.hasText(snapshot.getMethodStepsJson())
                && !StringUtils.hasText(snapshot.getIngredientsJson());
    }

    private void validateHouseholdSnapshot(
            DinnerRecordDishSnapshotEntity snapshot,
            List<RecordMethodStepSnapshotResponse> steps,
            List<RecordIngredientSnapshotResponse> ingredients
    ) {
        if (snapshot.getRecipeVersion() == null
                || snapshot.getRecipeVersion() <= 0
                || snapshot.getServings() == null
                || snapshot.getServings() < 1
                || snapshot.getServings() > 20
                || snapshot.getMethodId() == null
                || !StringUtils.hasText(snapshot.getMethodName())
                || !StringUtils.hasText(snapshot.getCookingStyle())
                || !validSteps(steps)
                || !validIngredients(ingredients)) {
            throw incompleteSnapshot();
        }
    }

    private void validateSystemSnapshot(
            DinnerRecordDishSnapshotEntity snapshot,
            List<RecordMethodStepSnapshotResponse> steps,
            List<RecordIngredientSnapshotResponse> ingredients
    ) {
        boolean methodAbsent = snapshot.getMethodId() == null
                && !StringUtils.hasText(snapshot.getMethodName())
                && !StringUtils.hasText(snapshot.getCookingStyle())
                && steps.isEmpty();
        if (!Objects.equals(snapshot.getRecipeVersion(), 1L)
                || !methodAbsent
                || !validIngredients(ingredients)) {
            throw incompleteSnapshot();
        }
    }

    private boolean validSteps(List<RecordMethodStepSnapshotResponse> steps) {
        return !steps.isEmpty()
                && steps.size() <= MAX_SNAPSHOT_STEPS
                && steps.stream().allMatch(step ->
                        StringUtils.hasText(step.instruction()));
    }

    private boolean validIngredients(List<RecordIngredientSnapshotResponse> ingredients) {
        return !ingredients.isEmpty()
                && ingredients.stream().anyMatch(
                        RecordIngredientSnapshotResponse::required);
    }

    private IllegalStateException incompleteSnapshot() {
        return new IllegalStateException(INCOMPLETE_SNAPSHOT);
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private record EncodedSnapshotDraft(
            DinnerRecordSnapshotAssembler.SnapshotDraft draft,
            String methodStepsJson,
            String ingredientsJson,
            String selectedByUserIdsJson
    ) {
    }
}
