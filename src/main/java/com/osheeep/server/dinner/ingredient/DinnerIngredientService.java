package com.osheeep.server.dinner.ingredient;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.ingredient.dto.IngredientResponse;
import com.osheeep.server.dinner.ingredient.dto.InventoryItemResponse;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerIngredientService {

    private final DinnerIngredientMapper ingredientMapper;
    private final DinnerHouseholdInventoryMapper inventoryMapper;
    private final DinnerHouseholdMemberMapper memberMapper;

    public DinnerIngredientService(
            DinnerIngredientMapper ingredientMapper,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerHouseholdMemberMapper memberMapper
    ) {
        this.ingredientMapper = ingredientMapper;
        this.inventoryMapper = inventoryMapper;
        this.memberMapper = memberMapper;
    }

    public List<IngredientResponse> listIngredients(Long userId) {
        DinnerHouseholdMemberEntity membership = requireMembership(userId);
        return ingredientMapper.selectList(Wrappers.<DinnerIngredientEntity>lambdaQuery()
                        .eq(DinnerIngredientEntity::getStatus, "ACTIVE")
                        .and(ingredient -> ingredient
                                .eq(DinnerIngredientEntity::getScope, "SYSTEM")
                                .or()
                                .eq(DinnerIngredientEntity::getHouseholdId, membership.getHouseholdId()))
                        .orderByAsc(DinnerIngredientEntity::getId))
                .stream()
                .map(this::toIngredientResponse)
                .toList();
    }

    public List<InventoryItemResponse> listInventory(Long userId) {
        DinnerHouseholdMemberEntity membership = requireMembership(userId);
        List<DinnerHouseholdInventoryEntity> items = inventoryMapper.selectList(
                Wrappers.<DinnerHouseholdInventoryEntity>lambdaQuery()
                        .eq(DinnerHouseholdInventoryEntity::getHouseholdId, membership.getHouseholdId())
                        .orderByAsc(DinnerHouseholdInventoryEntity::getId));
        if (items.isEmpty()) {
            return List.of();
        }
        List<Long> ingredientIds = items.stream()
                .map(DinnerHouseholdInventoryEntity::getIngredientId)
                .distinct()
                .toList();
        Map<Long, DinnerIngredientEntity> ingredientsById = ingredientMapper.selectByIds(ingredientIds)
                .stream()
                .collect(Collectors.toMap(DinnerIngredientEntity::getId, Function.identity()));
        return items.stream()
                .map(item -> toInventoryResponse(item, requireIngredientDetails(
                        ingredientsById.get(item.getIngredientId()))))
                .toList();
    }

    @Transactional
    public InventoryItemResponse upsertInventoryItem(
            Long userId,
            Long ingredientId,
            BigDecimal quantity,
            String unit,
            long expectedVersion
    ) {
        DinnerHouseholdMemberEntity membership = requireMembership(userId);
        DinnerIngredientEntity ingredient = requireActiveIngredient(
                ingredientId, membership.getHouseholdId());
        DinnerHouseholdInventoryEntity item = inventoryMapper
                .selectByHouseholdAndIngredientForUpdate(membership.getHouseholdId(), ingredientId);
        if (item == null) {
            if (expectedVersion != 0L) {
                throw new BusinessException(ErrorCode.DINNER_INVENTORY_VERSION_CONFLICT);
            }
            item = new DinnerHouseholdInventoryEntity();
            item.setHouseholdId(membership.getHouseholdId());
            item.setIngredientId(ingredientId);
            item.setVersion(0L);
            item.setQuantity(quantity);
            item.setUnit(unit.strip());
            item.setUpdatedBy(userId);
            inventoryMapper.insert(item);
        } else {
            if (!Objects.equals(item.getVersion(), expectedVersion)) {
                throw new BusinessException(ErrorCode.DINNER_INVENTORY_VERSION_CONFLICT);
            }
            item.setQuantity(quantity);
            item.setUnit(unit.strip());
            item.setUpdatedBy(userId);
            item.setVersion(item.getVersion() + 1L);
            inventoryMapper.updateById(item);
        }
        return toInventoryResponse(item, ingredient);
    }

    @Transactional
    public void removeInventoryItem(Long userId, Long ingredientId, long expectedVersion) {
        DinnerHouseholdMemberEntity membership = requireMembership(userId);
        DinnerHouseholdInventoryEntity item = inventoryMapper
                .selectByHouseholdAndIngredientForUpdate(membership.getHouseholdId(), ingredientId);
        if (item == null) {
            throw new BusinessException(ErrorCode.DINNER_INVENTORY_ITEM_NOT_FOUND);
        }
        if (!Objects.equals(item.getVersion(), expectedVersion)) {
            throw new BusinessException(ErrorCode.DINNER_INVENTORY_VERSION_CONFLICT);
        }
        inventoryMapper.delete(Wrappers.<DinnerHouseholdInventoryEntity>lambdaQuery()
                .eq(DinnerHouseholdInventoryEntity::getHouseholdId, membership.getHouseholdId())
                .eq(DinnerHouseholdInventoryEntity::getIngredientId, ingredientId)
                .eq(DinnerHouseholdInventoryEntity::getVersion, expectedVersion));
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

    private DinnerIngredientEntity requireActiveIngredient(Long ingredientId, Long householdId) {
        DinnerIngredientEntity ingredient = ingredientMapper.selectById(ingredientId);
        boolean valid = ingredient != null
                && "ACTIVE".equals(ingredient.getStatus())
                && ("SYSTEM".equals(ingredient.getScope())
                || Objects.equals(ingredient.getHouseholdId(), householdId));
        if (!valid) {
            throw new BusinessException(ErrorCode.DINNER_INGREDIENT_INVALID);
        }
        return ingredient;
    }

    private DinnerIngredientEntity requireIngredientDetails(DinnerIngredientEntity ingredient) {
        if (ingredient == null) {
            throw new BusinessException(ErrorCode.DINNER_INGREDIENT_INVALID);
        }
        return ingredient;
    }

    private IngredientResponse toIngredientResponse(DinnerIngredientEntity ingredient) {
        return new IngredientResponse(
                ingredient.getId(), ingredient.getName(), ingredient.getCategory(), ingredient.getDefaultUnit());
    }

    private InventoryItemResponse toInventoryResponse(
            DinnerHouseholdInventoryEntity item,
            DinnerIngredientEntity ingredient
    ) {
        return new InventoryItemResponse(
                item.getIngredientId(), ingredient.getName(), ingredient.getCategory(),
                item.getQuantity(), item.getUnit(), item.getVersion(), item.getUpdatedBy(),
                instant(item.getUpdatedAt()));
    }

    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
