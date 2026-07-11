package com.osheeep.server.dinner.menu;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.menu.dto.MenuDishResponse;
import com.osheeep.server.dinner.menu.dto.TodayMenuResponse;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
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
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class DinnerMenuService {

    private final DinnerHouseholdMapper householdMapper;
    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerMenuMapper menuMapper;
    private final DinnerMenuSelectionMapper selectionMapper;
    private final DinnerRecipeMapper recipeMapper;
    private final BusinessDateResolver businessDateResolver;
    private final Clock clock;

    @Autowired
    public DinnerMenuService(
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerRecipeMapper recipeMapper,
            BusinessDateResolver businessDateResolver
    ) {
        this(householdMapper, memberMapper, menuMapper, selectionMapper,
                recipeMapper, businessDateResolver, Clock.systemUTC());
    }

    DinnerMenuService(
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerRecipeMapper recipeMapper,
            BusinessDateResolver businessDateResolver,
            Clock clock
    ) {
        this.householdMapper = householdMapper;
        this.memberMapper = memberMapper;
        this.menuMapper = menuMapper;
        this.selectionMapper = selectionMapper;
        this.recipeMapper = recipeMapper;
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
        List<DinnerMenuSelectionEntity> selections = selectionMapper.selectList(
                Wrappers.<DinnerMenuSelectionEntity>lambdaQuery()
                        .eq(DinnerMenuSelectionEntity::getMenuId, menu.getId()));
        Map<Long, Set<Long>> selectorsByRecipe = new LinkedHashMap<>();
        for (DinnerMenuSelectionEntity selection : selections) {
            selectorsByRecipe.computeIfAbsent(selection.getRecipeId(), ignored -> new LinkedHashSet<>())
                    .add(selection.getUserId());
        }

        List<Long> recipeIds = selectorsByRecipe.keySet().stream().sorted().toList();
        Map<Long, DinnerRecipeEntity> recipesById = new HashMap<>();
        if (!recipeIds.isEmpty()) {
            for (DinnerRecipeEntity recipe : recipeMapper.selectByIds(recipeIds)) {
                recipesById.put(recipe.getId(), recipe);
            }
        }

        List<MenuDishResponse> dishes = new ArrayList<>();
        int consensusCount = 0;
        for (Long recipeId : recipeIds) {
            DinnerRecipeEntity recipe = recipesById.get(recipeId);
            if (recipe == null) {
                continue;
            }
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
            dishes.add(new MenuDishResponse(
                    recipe.getId(), recipe.getName(), recipe.getImagePath(), recipe.getCategory(),
                    recipe.getFlavor(), recipe.getEstimatedMinutes(), source));
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
}
