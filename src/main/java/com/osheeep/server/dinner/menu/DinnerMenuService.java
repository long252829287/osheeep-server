package com.osheeep.server.dinner.menu;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.menu.dto.MenuDishResponse;
import com.osheeep.server.dinner.menu.dto.TodayMenuResponse;
import com.osheeep.server.dinner.menu.entity.DinnerMenuActionEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuActionMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.recipe.DinnerRecipeCatalogAssembler;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodSummaryResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DinnerMenuService {

    private final DinnerHouseholdMapper householdMapper;
    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerMenuMapper menuMapper;
    private final DinnerMenuSelectionMapper selectionMapper;
    private final DinnerMenuActionMapper actionMapper;
    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeMethodMapper methodMapper;
    private final DinnerImageAssetService imageAssetService;
    private final DinnerRecipeCatalogAssembler catalogAssembler;
    private final BusinessDateResolver businessDateResolver;
    private final Clock clock;

    @Autowired
    public DinnerMenuService(
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerMenuActionMapper actionMapper,
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerImageAssetService imageAssetService,
            DinnerRecipeCatalogAssembler catalogAssembler,
            BusinessDateResolver businessDateResolver
    ) {
        this(householdMapper, memberMapper, menuMapper, selectionMapper, actionMapper,
                recipeMapper, methodMapper, imageAssetService, catalogAssembler,
                businessDateResolver, Clock.systemUTC());
    }

    DinnerMenuService(
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerMenuActionMapper actionMapper,
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerImageAssetService imageAssetService,
            DinnerRecipeCatalogAssembler catalogAssembler,
            BusinessDateResolver businessDateResolver,
            Clock clock
    ) {
        this.householdMapper = householdMapper;
        this.memberMapper = memberMapper;
        this.menuMapper = menuMapper;
        this.selectionMapper = selectionMapper;
        this.actionMapper = actionMapper;
        this.recipeMapper = recipeMapper;
        this.methodMapper = methodMapper;
        this.imageAssetService = imageAssetService;
        this.catalogAssembler = catalogAssembler;
        this.businessDateResolver = businessDateResolver;
        this.clock = clock;
    }

    public TodayMenuResponse today(Long userId) {
        DinnerHouseholdMemberEntity membership = findMembership(userId);
        if (membership == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        DinnerHouseholdEntity household = householdMapper.selectById(membership.getHouseholdId());
        if (household == null
                || household.getStatus() != null && !"ACTIVE".equals(household.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        LocalDate menuDate = businessDateResolver.resolve(household.getTimezone(), clock.instant());
        DinnerMenuEntity menu = findMenu(household.getId(), menuDate);
        if (menu == null) {
            menu = createDraft(household.getId(), menuDate);
        }
        return response(menu, userId);
    }

    @Transactional
    public TodayMenuResponse updateSelections(
            Long userId,
            List<Long> requestedRecipeIds,
            long expectedVersion
    ) {
        MenuContext context = lockToday(userId);
        DinnerMenuEntity menu = context.menu();
        requireVersion(menu, expectedVersion);
        requireMutable(menu);

        List<Long> recipeIds = requestedRecipeIds == null
                ? List.of()
                : requestedRecipeIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        Map<Long, ValidatedRecipe> recipesById =
                validateRecipes(recipeIds, context.household().getId());

        List<DinnerMenuSelectionEntity> currentSelections = selections(menu.getId());
        List<Long> currentUserRecipeIds = currentSelections.stream()
                .filter(selection -> userId.equals(selection.getUserId()))
                .map(DinnerMenuSelectionEntity::getRecipeId)
                .distinct()
                .sorted()
                .toList();
        if (currentUserRecipeIds.equals(recipeIds)) {
            return response(menu, userId);
        }

        selectionMapper.delete(Wrappers.<DinnerMenuSelectionEntity>lambdaQuery()
                .eq(DinnerMenuSelectionEntity::getMenuId, menu.getId())
                .eq(DinnerMenuSelectionEntity::getUserId, userId));
        for (Long recipeId : recipeIds) {
            DinnerMenuSelectionEntity selection = new DinnerMenuSelectionEntity();
            selection.setMenuId(menu.getId());
            selection.setUserId(userId);
            selection.setRecipeId(recipeId);
            ValidatedRecipe validated = recipesById.get(recipeId);
            selection.setRecipeVersion(validated.selectedVersion());
            selection.setMethodId(
                    validated.method() == null ? null : validated.method().id());
            selectionMapper.insert(selection);
        }
        if ("CONFIRMED".equals(menu.getStatus())) {
            menu.setStatus("DRAFT");
            menu.setConfirmedBy(null);
            menu.setConfirmedAt(null);
        }
        menu.setVersion(menu.getVersion() + 1);
        menuMapper.updateById(menu);
        return response(menu, userId);
    }

    @Transactional
    public TodayMenuResponse confirm(Long userId, long expectedVersion, String idempotencyKey) {
        MenuContext context = lockToday(userId);
        DinnerMenuEntity menu = context.menu();
        DinnerMenuActionEntity previousAction = actionMapper.selectOne(
                Wrappers.<DinnerMenuActionEntity>lambdaQuery()
                        .eq(DinnerMenuActionEntity::getIdempotencyKey, idempotencyKey)
                        .last("LIMIT 1"));
        if (previousAction != null) {
            return response(menu, userId);
        }
        requireVersion(menu, expectedVersion);
        requireMutable(menu);
        if (selections(menu.getId()).isEmpty()) {
            throw new BusinessException(ErrorCode.DINNER_MENU_EMPTY);
        }
        if ("CONFIRMED".equals(menu.getStatus())) {
            return response(menu, userId);
        }
        menu.setStatus("CONFIRMED");
        menu.setConfirmedBy(userId);
        menu.setConfirmedAt(now());
        menu.setVersion(menu.getVersion() + 1);
        menuMapper.updateById(menu);

        DinnerMenuActionEntity action = new DinnerMenuActionEntity();
        action.setMenuId(menu.getId());
        action.setActorId(userId);
        action.setActionType("CONFIRM");
        action.setIdempotencyKey(idempotencyKey);
        actionMapper.insert(action);
        return response(menu, userId);
    }

    private DinnerMenuEntity findMenu(Long householdId, LocalDate menuDate) {
        return menuMapper.selectOne(Wrappers.<DinnerMenuEntity>lambdaQuery()
                .eq(DinnerMenuEntity::getHouseholdId, householdId)
                .eq(DinnerMenuEntity::getMenuDate, menuDate)
                .last("LIMIT 1"));
    }

    private DinnerMenuEntity createDraft(Long householdId, LocalDate menuDate) {
        DinnerMenuEntity menu = new DinnerMenuEntity();
        menu.setHouseholdId(householdId);
        menu.setMenuDate(menuDate);
        menu.setStatus("DRAFT");
        menu.setVersion(0L);
        try {
            menuMapper.insert(menu);
            return menu;
        } catch (DuplicateKeyException exception) {
            return findMenu(householdId, menuDate);
        }
    }

    private TodayMenuResponse response(DinnerMenuEntity menu, Long currentUserId) {
        List<DinnerMenuSelectionEntity> selections = selections(menu.getId());
        Map<Long, Set<Long>> selectorsByRecipe = new LinkedHashMap<>();
        Map<Long, SelectionIdentity> identitiesByRecipe = new LinkedHashMap<>();
        for (DinnerMenuSelectionEntity selection : selections) {
            if (selection.getRecipeId() == null || selection.getUserId() == null) {
                throw invalidRecipe();
            }
            SelectionIdentity identity = new SelectionIdentity(
                    selection.getRecipeVersion(), selection.getMethodId());
            SelectionIdentity previous = identitiesByRecipe.putIfAbsent(
                    selection.getRecipeId(), identity);
            if (previous != null && !previous.equals(identity)) {
                throw invalidRecipe();
            }
            selectorsByRecipe.computeIfAbsent(selection.getRecipeId(), ignored -> new LinkedHashSet<>())
                    .add(selection.getUserId());
        }

        List<Long> recipeIds = selectorsByRecipe.keySet().stream().sorted().toList();
        Map<Long, DinnerRecipeEntity> recipesById = loadRecipes(recipeIds);
        List<Long> methodIds = new ArrayList<>();
        List<Long> imageAssetIds = new ArrayList<>();
        for (Long recipeId : recipeIds) {
            DinnerRecipeEntity recipe = recipesById.get(recipeId);
            SelectionIdentity identity = identitiesByRecipe.get(recipeId);
            if ("SYSTEM".equals(recipe.getScope())) {
                if (!"PUBLISHED".equals(recipe.getStatus())
                        || !Objects.equals(identity.recipeVersion(), 1L)
                        || identity.methodId() != null) {
                    throw invalidRecipe();
                }
                continue;
            }
            if (!"HOUSEHOLD".equals(recipe.getScope())
                    || !"PUBLISHED".equals(recipe.getStatus())
                    || !Objects.equals(recipe.getHouseholdId(), menu.getHouseholdId())
                    || identity.recipeVersion() == null
                    || identity.recipeVersion() <= 0
                    || identity.methodId() == null
                    || recipe.getImageAssetId() == null) {
                throw invalidRecipe();
            }
            methodIds.add(identity.methodId());
            imageAssetIds.add(recipe.getImageAssetId());
        }

        List<Long> distinctMethodIds = methodIds.stream().distinct().sorted().toList();
        Map<Long, DinnerRecipeMethodEntity> methodsById = loadMethods(distinctMethodIds);
        List<Long> distinctImageAssetIds = imageAssetIds.stream().distinct().sorted().toList();
        Map<Long, ImageAssetResponse> imagesById = distinctImageAssetIds.isEmpty()
                ? Map.of()
                : imageAssetService.findApprovedByIds(distinctImageAssetIds);
        if (!imagesById.keySet().equals(new LinkedHashSet<>(distinctImageAssetIds))) {
            throw invalidRecipe();
        }

        List<MenuDishResponse> dishes = new ArrayList<>();
        int consensusCount = 0;
        for (Long recipeId : recipeIds) {
            DinnerRecipeEntity recipe = recipesById.get(recipeId);
            SelectionIdentity identity = identitiesByRecipe.get(recipeId);
            Set<Long> selectors = selectorsByRecipe.get(recipeId);
            String source;
            if (selectors.size() > 1) {
                source = "BOTH";
                consensusCount++;
            } else if (selectors.contains(currentUserId)) {
                source = "ME";
            } else {
                source = "PARTNER";
            }
            RecipeMethodSummaryResponse method = null;
            String imagePath = recipe.getImagePath();
            if ("HOUSEHOLD".equals(recipe.getScope())) {
                DinnerRecipeMethodEntity savedMethod = methodsById.get(identity.methodId());
                if (savedMethod == null
                        || !Objects.equals(savedMethod.getRecipeId(), recipeId)
                        || !"ACTIVE".equals(savedMethod.getStatus())
                        || !StringUtils.hasText(savedMethod.getName())
                        || !StringUtils.hasText(savedMethod.getCookingStyle())) {
                    throw invalidRecipe();
                }
                ImageAssetResponse image = imagesById.get(recipe.getImageAssetId());
                if (image == null || !StringUtils.hasText(image.listUrl())) {
                    throw invalidRecipe();
                }
                method = new RecipeMethodSummaryResponse(
                        savedMethod.getId(), savedMethod.getName(), savedMethod.getCookingStyle());
                imagePath = image.listUrl();
            }
            dishes.add(new MenuDishResponse(
                    recipe.getId(), recipe.getName(), imagePath, recipe.getCategory(),
                    recipe.getFlavor(), recipe.getEstimatedMinutes(), source, recipe.getScope(),
                    identity.recipeVersion(), method));
        }

        List<Long> selectedRecipeIds = selections.stream()
                .filter(selection -> currentUserId.equals(selection.getUserId()))
                .map(DinnerMenuSelectionEntity::getRecipeId)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        int partnerSelectionCount = Math.toIntExact(selections.stream()
                .filter(selection -> !currentUserId.equals(selection.getUserId()))
                .map(DinnerMenuSelectionEntity::getRecipeId)
                .distinct()
                .count());

        return new TodayMenuResponse(
                menu.getId(), menu.getMenuDate(), menu.getStatus(), menu.getVersion(),
                selectedRecipeIds.size(), partnerSelectionCount, consensusCount,
                selectedRecipeIds, dishes, menu.getConfirmedBy(), instant(menu.getConfirmedAt()),
                menu.getCompletedBy(), instant(menu.getCompletedAt()), null);
    }

    private DinnerHouseholdMemberEntity findMembership(Long userId) {
        return memberMapper.selectOne(Wrappers.<DinnerHouseholdMemberEntity>lambdaQuery()
                .eq(DinnerHouseholdMemberEntity::getUserId, userId)
                .last("LIMIT 1"));
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private MenuContext lockToday(Long userId) {
        DinnerHouseholdMemberEntity membership = findMembership(userId);
        if (membership == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        DinnerHouseholdEntity household = householdMapper.selectById(membership.getHouseholdId());
        if (household == null
                || household.getStatus() != null && !"ACTIVE".equals(household.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        LocalDate menuDate = businessDateResolver.resolve(household.getTimezone(), clock.instant());
        DinnerMenuEntity menu = menuMapper.selectByHouseholdAndDateForUpdate(household.getId(), menuDate);
        if (menu == null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "Today's dinner menu was not initialized");
        }
        return new MenuContext(household, menu);
    }

    private void requireVersion(DinnerMenuEntity menu, long expectedVersion) {
        if (!Objects.equals(menu.getVersion(), expectedVersion)) {
            throw new BusinessException(ErrorCode.DINNER_MENU_VERSION_CONFLICT);
        }
    }

    private void requireMutable(DinnerMenuEntity menu) {
        if ("COMPLETED".equals(menu.getStatus())) {
            throw new BusinessException(ErrorCode.DINNER_MENU_COMPLETED);
        }
    }

    private Map<Long, ValidatedRecipe> validateRecipes(
            List<Long> recipeIds,
            Long householdId
    ) {
        if (recipeIds.isEmpty()) {
            return Map.of();
        }
        List<DinnerRecipeEntity> recipes = recipeMapper.selectByIds(recipeIds);
        Map<Long, DinnerRecipeEntity> recipesById = mapRecipes(recipes, recipeIds);
        for (Long recipeId : recipeIds) {
            DinnerRecipeEntity recipe = recipesById.get(recipeId);
            boolean system = "SYSTEM".equals(recipe.getScope())
                    && "PUBLISHED".equals(recipe.getStatus());
            boolean household = "HOUSEHOLD".equals(recipe.getScope())
                    && "PUBLISHED".equals(recipe.getStatus())
                    && Objects.equals(recipe.getHouseholdId(), householdId)
                    && recipe.getVersion() != null
                    && recipe.getVersion() > 0;
            if (!system && !household) {
                throw invalidRecipe();
            }
        }
        Map<Long, DinnerRecipeCatalogAssembler.CatalogEntry> catalog =
                catalogAssembler.assemble(recipes);
        if (!catalog.keySet().equals(new LinkedHashSet<>(recipeIds))) {
            throw invalidRecipe();
        }
        Map<Long, ValidatedRecipe> validated = new LinkedHashMap<>();
        for (Long recipeId : recipeIds) {
            DinnerRecipeEntity recipe = recipesById.get(recipeId);
            RecipeMethodSummaryResponse method = catalog.get(recipeId).defaultMethod();
            if (("SYSTEM".equals(recipe.getScope()) && method != null)
                    || ("HOUSEHOLD".equals(recipe.getScope()) && method == null)) {
                throw invalidRecipe();
            }
            validated.put(recipeId, new ValidatedRecipe(recipe, method));
        }
        return Map.copyOf(validated);
    }

    private Map<Long, DinnerRecipeEntity> loadRecipes(List<Long> recipeIds) {
        if (recipeIds.isEmpty()) {
            return Map.of();
        }
        return mapRecipes(recipeMapper.selectByIds(recipeIds), recipeIds);
    }

    private Map<Long, DinnerRecipeEntity> mapRecipes(
            List<DinnerRecipeEntity> recipes,
            List<Long> expectedIds
    ) {
        Map<Long, DinnerRecipeEntity> recipesById = new HashMap<>();
        for (DinnerRecipeEntity recipe : recipes) {
            if (recipe == null
                    || recipe.getId() == null
                    || recipesById.putIfAbsent(recipe.getId(), recipe) != null) {
                throw invalidRecipe();
            }
        }
        if (!recipesById.keySet().equals(new LinkedHashSet<>(expectedIds))) {
            throw invalidRecipe();
        }
        return recipesById;
    }

    private Map<Long, DinnerRecipeMethodEntity> loadMethods(List<Long> methodIds) {
        if (methodIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, DinnerRecipeMethodEntity> methodsById = new HashMap<>();
        for (DinnerRecipeMethodEntity method : methodMapper.selectByIds(methodIds)) {
            if (method == null
                    || method.getId() == null
                    || methodsById.putIfAbsent(method.getId(), method) != null) {
                throw invalidRecipe();
            }
        }
        if (!methodsById.keySet().equals(new LinkedHashSet<>(methodIds))) {
            throw invalidRecipe();
        }
        return methodsById;
    }

    private BusinessException invalidRecipe() {
        return new BusinessException(ErrorCode.DINNER_RECIPE_INVALID);
    }

    private List<DinnerMenuSelectionEntity> selections(Long menuId) {
        return selectionMapper.selectList(Wrappers.<DinnerMenuSelectionEntity>lambdaQuery()
                .eq(DinnerMenuSelectionEntity::getMenuId, menuId));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record MenuContext(DinnerHouseholdEntity household, DinnerMenuEntity menu) {
    }

    private record ValidatedRecipe(
            DinnerRecipeEntity recipe,
            RecipeMethodSummaryResponse method
    ) {
        long selectedVersion() {
            return "SYSTEM".equals(recipe.getScope()) ? 1L : recipe.getVersion();
        }
    }

    private record SelectionIdentity(Long recipeVersion, Long methodId) {
    }
}
