package com.osheeep.server.dinner.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerIngredientServiceTest {

    @Mock private DinnerIngredientMapper ingredientMapper;
    @Mock private DinnerHouseholdInventoryMapper inventoryMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;

    private DinnerIngredientService service;

    @BeforeEach
    void setUp() {
        service = new DinnerIngredientService(ingredientMapper, inventoryMapper, memberMapper);
    }

    @Test
    void listsAccessibleActiveIngredientsForCurrentHousehold() {
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(ingredientMapper.selectList(any())).thenReturn(List.of(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚", "ACTIVE"),
                ingredient(4L, "HOUSEHOLD", 11L, "冻豆腐", "豆制品", "块", "ACTIVE")));

        assertThat(service.listIngredients(7L)).containsExactly(
                new IngredientResponse(3L, "鸡蛋", "蛋奶", "枚"),
                new IngredientResponse(4L, "冻豆腐", "豆制品", "块"));
    }

    @Test
    void listsHouseholdInventoryWithIngredientDetailsAndUtcTimestamp() {
        DinnerHouseholdInventoryEntity item = inventory(11L, 3L, "6.000", "枚", 2L);
        item.setUpdatedBy(8L);
        item.setUpdatedAt(LocalDateTime.of(2026, 7, 15, 6, 30));
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(inventoryMapper.selectList(any())).thenReturn(List.of(item));
        when(ingredientMapper.selectByIds(List.of(3L))).thenReturn(List.of(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚", "ACTIVE")));

        assertThat(service.listInventory(7L)).containsExactly(new InventoryItemResponse(
                3L, "鸡蛋", "蛋奶", new BigDecimal("6.000"), "枚", 2L, 8L,
                Instant.parse("2026-07-15T06:30:00Z")));
    }

    @Test
    void creatingAnInventoryItemReturnsDatabaseManagedTimestamp() {
        DinnerHouseholdInventoryEntity persisted = inventory(11L, 3L, "8.000", "枚", 0L);
        persisted.setId(21L);
        persisted.setUpdatedBy(7L);
        persisted.setUpdatedAt(LocalDateTime.of(2026, 7, 15, 7, 30));
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(ingredientMapper.selectById(3L)).thenReturn(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚", "ACTIVE"));
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L)).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<DinnerHouseholdInventoryEntity>getArgument(0).setId(21L);
            return 1;
        }).when(inventoryMapper).insert(any(DinnerHouseholdInventoryEntity.class));
        when(inventoryMapper.selectById(21L)).thenReturn(persisted);

        InventoryItemResponse result = service.upsertInventoryItem(
                7L, 3L, new BigDecimal("8.000"), "  枚  ", 0L);

        assertThat(result.quantity()).isEqualByComparingTo("8.000");
        assertThat(result.unit()).isEqualTo("枚");
        assertThat(result.version()).isZero();
        assertThat(result.updatedBy()).isEqualTo(7L);
        assertThat(result.updatedAt()).isEqualTo(Instant.parse("2026-07-15T07:30:00Z"));
        verify(inventoryMapper).insert(any(DinnerHouseholdInventoryEntity.class));
        verify(inventoryMapper).selectById(21L);
    }

    @Test
    void newInventoryItemRejectsNonzeroExpectedVersion() {
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(ingredientMapper.selectById(3L)).thenReturn(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚", "ACTIVE"));
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L)).thenReturn(null);

        assertThatThrownBy(() -> service.upsertInventoryItem(
                7L, 3L, new BigDecimal("8.000"), "枚", 1L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_VERSION_CONFLICT));
        verify(inventoryMapper, never()).insert(any(DinnerHouseholdInventoryEntity.class));
    }

    @Test
    void updatingAnInventoryItemReturnsRefreshedDatabaseTimestamp() {
        DinnerHouseholdInventoryEntity item = inventory(11L, 3L, "6.000", "枚", 2L);
        item.setId(21L);
        item.setUpdatedAt(LocalDateTime.of(2026, 7, 15, 6, 30));
        DinnerHouseholdInventoryEntity persisted = inventory(11L, 3L, "8.000", "枚", 3L);
        persisted.setId(21L);
        persisted.setUpdatedBy(7L);
        persisted.setUpdatedAt(LocalDateTime.of(2026, 7, 15, 7, 30));
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(ingredientMapper.selectById(3L)).thenReturn(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚", "ACTIVE"));
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L))
                .thenReturn(item);
        when(inventoryMapper.selectById(21L)).thenReturn(persisted);

        InventoryItemResponse result = service.upsertInventoryItem(
                7L, 3L, new BigDecimal("8.000"), "枚", 2L);

        assertThat(result.quantity()).isEqualByComparingTo("8.000");
        assertThat(result.version()).isEqualTo(3L);
        assertThat(result.updatedBy()).isEqualTo(7L);
        assertThat(result.updatedAt()).isEqualTo(Instant.parse("2026-07-15T07:30:00Z"));
        verify(inventoryMapper).updateById(item);
        verify(inventoryMapper).selectById(21L);
    }

    @Test
    void inventoryTimestampIsExcludedFromApplicationWrites() throws Exception {
        TableField mapping = DinnerHouseholdInventoryEntity.class
                .getDeclaredField("updatedAt")
                .getAnnotation(TableField.class);

        assertThat(mapping.insertStrategy()).isEqualTo(FieldStrategy.NEVER);
        assertThat(mapping.updateStrategy()).isEqualTo(FieldStrategy.NEVER);
    }

    @Test
    void staleInventoryVersionDoesNotMutateTheItem() {
        DinnerHouseholdInventoryEntity item = inventory(11L, 3L, "6.000", "枚", 4L);
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(ingredientMapper.selectById(3L)).thenReturn(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚", "ACTIVE"));
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L))
                .thenReturn(item);

        assertThatThrownBy(() -> service.upsertInventoryItem(
                7L, 3L, new BigDecimal("8.000"), "枚", 3L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_VERSION_CONFLICT));
        assertThat(item.getQuantity()).isEqualByComparingTo("6.000");
        assertThat(item.getVersion()).isEqualTo(4L);
        verify(inventoryMapper, never()).updateById(any(DinnerHouseholdInventoryEntity.class));
    }

    @Test
    void rejectsForeignHouseholdIngredient() {
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(ingredientMapper.selectById(3L)).thenReturn(
                ingredient(3L, "HOUSEHOLD", 12L, "冻豆腐", "豆制品", "块", "ACTIVE"));

        assertThatThrownBy(() -> service.upsertInventoryItem(
                7L, 3L, BigDecimal.ONE, "块", 0L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.DINNER_INGREDIENT_INVALID));
        verify(inventoryMapper, never()).selectByHouseholdAndIngredientForUpdate(any(), any());
    }

    @Test
    void rejectsInactiveSystemIngredient() {
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(ingredientMapper.selectById(3L)).thenReturn(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚", "INACTIVE"));

        assertThatThrownBy(() -> service.upsertInventoryItem(
                7L, 3L, BigDecimal.ONE, "枚", 0L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.DINNER_INGREDIENT_INVALID));
        verify(inventoryMapper, never()).selectByHouseholdAndIngredientForUpdate(any(), any());
    }

    @Test
    void inventoryOperationsRequireMembership() {
        when(memberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.listInventory(7L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void deletingAnInventoryItemUsesExactVersion() {
        DinnerHouseholdInventoryEntity item = inventory(11L, 3L, "6.000", "枚", 2L);
        item.setId(21L);
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L)).thenReturn(item);

        service.removeInventoryItem(7L, 3L, 2L);

        verify(inventoryMapper).delete(any());
    }

    @Test
    void deletingMissingInventoryItemReturnsNotFound() {
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L)).thenReturn(null);

        assertThatThrownBy(() -> service.removeInventoryItem(7L, 3L, 0L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_ITEM_NOT_FOUND));
        verify(inventoryMapper, never()).delete(any());
    }

    @Test
    void staleDeleteDoesNotRemoveInventoryItem() {
        DinnerHouseholdInventoryEntity item = inventory(11L, 3L, "6.000", "枚", 2L);
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L)).thenReturn(item);

        assertThatThrownBy(() -> service.removeInventoryItem(7L, 3L, 1L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_VERSION_CONFLICT));
        verify(inventoryMapper, never()).delete(any());
    }

    private DinnerHouseholdMemberEntity member(Long householdId, Long userId) {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setHouseholdId(householdId);
        member.setUserId(userId);
        return member;
    }

    private DinnerIngredientEntity ingredient(
            Long id,
            String scope,
            Long householdId,
            String name,
            String category,
            String defaultUnit,
            String status
    ) {
        DinnerIngredientEntity ingredient = new DinnerIngredientEntity();
        ingredient.setId(id);
        ingredient.setScope(scope);
        ingredient.setHouseholdId(householdId);
        ingredient.setName(name);
        ingredient.setCategory(category);
        ingredient.setDefaultUnit(defaultUnit);
        ingredient.setStatus(status);
        return ingredient;
    }

    private DinnerHouseholdInventoryEntity inventory(
            Long householdId,
            Long ingredientId,
            String quantity,
            String unit,
            Long version
    ) {
        DinnerHouseholdInventoryEntity item = new DinnerHouseholdInventoryEntity();
        item.setHouseholdId(householdId);
        item.setIngredientId(ingredientId);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnit(unit);
        item.setVersion(version);
        return item;
    }
}
