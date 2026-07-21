package com.osheeep.server.dinner.record;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DinnerRecordService {

    private final DinnerHouseholdMapper householdMapper;
    private final DinnerHouseholdMemberMapper memberMapper;
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
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
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
        this(householdMapper, memberMapper, menuMapper, selectionMapper, actionMapper,
                recordMapper, snapshotMapper, menuService, snapshotAssembler,
                snapshotJsonCodec, businessDateResolver, Clock.systemUTC());
    }

    DinnerRecordService(
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
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
        this.householdMapper = householdMapper;
        this.memberMapper = memberMapper;
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
        DinnerHouseholdMemberEntity membership = memberMapper.selectOne(
                Wrappers.<DinnerHouseholdMemberEntity>lambdaQuery()
                        .eq(DinnerHouseholdMemberEntity::getUserId, userId)
                        .last("LIMIT 1"));
        if (membership == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        DinnerHouseholdEntity household = householdMapper.selectById(membership.getHouseholdId());
        if (household == null || !"ACTIVE".equals(household.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        LocalDate menuDate = businessDateResolver.resolve(household.getTimezone(), clock.instant());
        DinnerMenuEntity menu = menuMapper.selectByHouseholdAndDateForUpdate(household.getId(), menuDate);
        if (menu == null) {
            throw new BusinessException(ErrorCode.DINNER_MENU_NOT_CONFIRMED);
        }

        DinnerCookingRecordEntity existing = findRecord(menu.getId());
        if (existing != null) {
            return new CompleteMenuResponse(existing.getId(), menuService.today(userId));
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
                snapshotAssembler.assemble(household.getId(), selections);

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        DinnerCookingRecordEntity record = new DinnerCookingRecordEntity();
        record.setHouseholdId(household.getId());
        record.setMenuId(menu.getId());
        record.setRecordDate(menu.getMenuDate());
        record.setCompletedBy(userId);
        record.setCompletedAt(now);
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException exception) {
            DinnerCookingRecordEntity winner = findRecord(menu.getId());
            if (winner == null) {
                throw exception;
            }
            return new CompleteMenuResponse(winner.getId(), menuService.today(userId));
        }

        int sortOrder = 0;
        for (DinnerRecordSnapshotAssembler.SnapshotDraft draft : snapshotDrafts) {
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
            snapshot.setMethodStepsJson(snapshotJsonCodec.writeSteps(draft.steps()));
            snapshot.setIngredientsJson(snapshotJsonCodec.writeIngredients(draft.ingredients()));
            snapshot.setSelectedByUserIds(toJsonArray(draft.selectedByUserIds()));
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
        return new CompleteMenuResponse(record.getId(), menuService.today(userId));
    }

    public List<RecordSummaryResponse> list(Long userId) {
        DinnerHouseholdMemberEntity membership = requireMembership(userId);
        return recordMapper.selectList(Wrappers.<DinnerCookingRecordEntity>lambdaQuery()
                        .eq(DinnerCookingRecordEntity::getHouseholdId, membership.getHouseholdId())
                        .orderByDesc(DinnerCookingRecordEntity::getCompletedAt))
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
        DinnerHouseholdMemberEntity membership = requireMembership(userId);
        DinnerCookingRecordEntity record = recordMapper.selectById(recordId);
        if (record == null || !membership.getHouseholdId().equals(record.getHouseholdId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
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

    private String toJsonArray(Set<Long> userIds) {
        return userIds.stream().sorted().map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private DinnerHouseholdMemberEntity requireMembership(Long userId) {
        DinnerHouseholdMemberEntity membership = memberMapper.selectOne(
                Wrappers.<DinnerHouseholdMemberEntity>lambdaQuery()
                        .eq(DinnerHouseholdMemberEntity::getUserId, userId)
                        .last("LIMIT 1"));
        if (membership == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return membership;
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
        String scope = snapshot.getRecipeScope() == null
                ? "SYSTEM" : snapshot.getRecipeScope();
        Long recipeVersion = snapshot.getRecipeVersion() == null
                ? 1L : snapshot.getRecipeVersion();
        List<RecordMethodStepSnapshotResponse> steps =
                snapshotJsonCodec.readSteps(snapshot.getMethodStepsJson());
        boolean methodAbsent = snapshot.getMethodId() == null
                && snapshot.getMethodName() == null
                && snapshot.getCookingStyle() == null
                && steps.isEmpty();
        if (!methodAbsent && (snapshot.getMethodId() == null
                || !StringUtils.hasText(snapshot.getMethodName())
                || !StringUtils.hasText(snapshot.getCookingStyle())
                || steps.isEmpty())) {
            throw new IllegalStateException("Incomplete dinner record method snapshot");
        }
        RecordMethodSnapshotResponse method = methodAbsent ? null
                : new RecordMethodSnapshotResponse(
                        snapshot.getMethodId(), snapshot.getMethodName(),
                        snapshot.getCookingStyle(), steps);
        List<RecordIngredientSnapshotResponse> ingredients =
                snapshotJsonCodec.readIngredients(snapshot.getIngredientsJson());
        return new RecordDishResponse(
                snapshot.getRecipeId(), snapshot.getName(), snapshot.getImagePath(),
                snapshot.getCategory(), snapshot.getFlavor(), snapshot.getEstimatedMinutes(),
                source(snapshot.getSelectedByUserIds(), currentUserId), scope, recipeVersion,
                snapshot.getServings(), method, ingredients);
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
