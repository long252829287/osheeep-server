package com.osheeep.server.dinner.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.LockedHouseholdContext;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class DinnerIngredientServiceTest {

    @Mock private DinnerIngredientMapper ingredientMapper;
    @Mock private DinnerHouseholdInventoryMapper inventoryMapper;
    @Mock private DinnerHouseholdAccessService accessService;

    private DinnerIngredientService service;

    @BeforeEach
    void setUp() {
        service = new DinnerIngredientService(ingredientMapper, inventoryMapper, accessService);
    }

    @Test
    void listsAccessibleActiveIngredientsForCurrentHousehold() {
        when(accessService.requireActiveHousehold(7L)).thenReturn(access(7L, 11L));
        when(ingredientMapper.selectList(any())).thenReturn(List.of(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚", "ACTIVE"),
                ingredient(4L, "HOUSEHOLD", 11L, "冻豆腐", "豆制品", "块", "ACTIVE")));

        assertThat(service.listIngredients(7L)).containsExactly(
                new IngredientResponse(3L, "鸡蛋", "蛋奶", "枚"),
                new IngredientResponse(4L, "冻豆腐", "豆制品", "块"));
    }

    @Test
    void interpretsDatabaseTimestampInAsiaShanghai() {
        DinnerHouseholdInventoryEntity item = inventory(11L, 3L, "6.000", "枚", 2L);
        item.setUpdatedBy(8L);
        item.setUpdatedAt(LocalDateTime.of(2026, 7, 15, 6, 30));
        when(accessService.requireActiveHousehold(7L)).thenReturn(access(7L, 11L));
        when(inventoryMapper.selectList(any())).thenReturn(List.of(item));
        when(ingredientMapper.selectByIds(List.of(3L))).thenReturn(List.of(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚", "ACTIVE")));

        assertThat(service.listInventory(7L)).containsExactly(new InventoryItemResponse(
                3L, "鸡蛋", "蛋奶", new BigDecimal("6.000"), "枚", 2L, 8L,
                Instant.parse("2026-07-14T22:30:00Z")));
    }

    @Test
    void upsertLocksHouseholdContextBeforeIngredientAndInventory() {
        DinnerHouseholdInventoryEntity item = inventory(11L, 3L, "6.000", "枚", 2L);
        stubLockedAccess(7L, 11L);
        when(ingredientMapper.selectById(3L)).thenReturn(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚", "ACTIVE"));
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L))
                .thenReturn(item);

        assertThatThrownBy(() -> service.upsertInventoryItem(
                7L, 3L, BigDecimal.ONE, "枚", 1L))
                .isInstanceOf(BusinessException.class);

        InOrder order = inOrder(accessService, ingredientMapper, inventoryMapper);
        order.verify(accessService).lockActiveHouseholdContext(7L);
        order.verify(ingredientMapper).selectById(3L);
        order.verify(inventoryMapper)
                .selectByHouseholdAndIngredientForUpdate(11L, 3L);
    }

    @ParameterizedTest(name = "{0} locked household context failure stops inventory upsert")
    @ValueSource(strings = {"STALE_MEMBERSHIP", "INACTIVE_HOUSEHOLD"})
    void staleHouseholdContextStopsInventoryUpsertBeforeDomainLookup(String reason) {
        when(accessService.lockActiveHouseholdContext(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThatThrownBy(() -> service.upsertInventoryItem(
                7L, 3L, BigDecimal.ONE, "枚", 0L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThat(reason).isNotBlank();
        verifyNoInteractions(ingredientMapper, inventoryMapper);
    }

    @Test
    void creatingAnInventoryItemReturnsDatabaseManagedTimestamp() {
        DinnerHouseholdInventoryEntity persisted = inventory(11L, 3L, "8.000", "枚", 1L);
        persisted.setId(21L);
        persisted.setUpdatedBy(7L);
        persisted.setUpdatedAt(LocalDateTime.of(2026, 7, 15, 7, 30));
        stubLockedAccess(7L, 11L);
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
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.updatedBy()).isEqualTo(7L);
        assertThat(result.updatedAt()).isEqualTo(Instant.parse("2026-07-14T23:30:00Z"));
        verify(inventoryMapper).insert(argThat((DinnerHouseholdInventoryEntity inserted) ->
                inserted.getVersion() == 1L));
        verify(inventoryMapper).selectById(21L);
    }

    @Test
    void newInventoryItemRejectsNonzeroExpectedVersion() {
        stubLockedAccess(7L, 11L);
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
    void existingInventoryItemRejectsCreateOnlyExpectedVersionWithoutMutation() {
        DinnerHouseholdInventoryEntity item = inventory(11L, 3L, "6.000", "枚", 0L);
        stubLockedAccess(7L, 11L);
        when(ingredientMapper.selectById(3L)).thenReturn(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚", "ACTIVE"));
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L))
                .thenReturn(item);

        assertThatThrownBy(() -> service.upsertInventoryItem(
                7L, 3L, new BigDecimal("8.000"), "盒", 0L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_VERSION_CONFLICT));
        assertThat(item.getQuantity()).isEqualByComparingTo("6.000");
        assertThat(item.getUnit()).isEqualTo("枚");
        assertThat(item.getVersion()).isZero();
        verify(inventoryMapper, never()).updateById(any(DinnerHouseholdInventoryEntity.class));
    }

    @Test
    void concurrentInsertDuplicateKeyBecomesVersionConflict() {
        stubLockedAccess(7L, 11L);
        when(ingredientMapper.selectById(3L)).thenReturn(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚", "ACTIVE"));
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L)).thenReturn(null);
        when(inventoryMapper.insert(any(DinnerHouseholdInventoryEntity.class)))
                .thenThrow(new DuplicateKeyException("concurrent create"));

        assertThatThrownBy(() -> service.upsertInventoryItem(
                7L, 3L, new BigDecimal("8.000"), "枚", 0L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_VERSION_CONFLICT));
    }

    @Test
    void lockAcquisitionFailureBecomesVersionConflict() {
        stubLockedAccess(7L, 11L);
        when(ingredientMapper.selectById(3L)).thenReturn(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚", "ACTIVE"));
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L))
                .thenThrow(new CannotAcquireLockException("lock wait timeout"));

        assertThatThrownBy(() -> service.upsertInventoryItem(
                7L, 3L, new BigDecimal("8.000"), "枚", 1L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_VERSION_CONFLICT));
    }

    @Test
    void deadlockFailureBecomesVersionConflict() {
        DinnerHouseholdInventoryEntity item = inventory(11L, 3L, "6.000", "枚", 1L);
        item.setId(21L);
        stubLockedAccess(7L, 11L);
        when(ingredientMapper.selectById(3L)).thenReturn(
                ingredient(3L, "SYSTEM", null, "鸡蛋", "蛋奶", "枚", "ACTIVE"));
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L))
                .thenReturn(item);
        when(inventoryMapper.updateById(item))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock", null));

        assertThatThrownBy(() -> service.upsertInventoryItem(
                7L, 3L, new BigDecimal("8.000"), "枚", 1L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_VERSION_CONFLICT));
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
        stubLockedAccess(7L, 11L);
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
        assertThat(result.updatedAt()).isEqualTo(Instant.parse("2026-07-14T23:30:00Z"));
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
        stubLockedAccess(7L, 11L);
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
        stubLockedAccess(7L, 11L);
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
        stubLockedAccess(7L, 11L);
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
        when(accessService.requireActiveHousehold(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThatThrownBy(() -> service.listInventory(7L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));
    }

    @ParameterizedTest(name = "{0} member cannot list old-household ingredients")
    @ValueSource(strings = {"LEFT", "REMOVED"})
    void formerMemberCannotListOldHouseholdIngredients(String membershipStatus) {
        when(accessService.requireActiveHousehold(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThatThrownBy(() -> service.listIngredients(7L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode().name())
                                .isEqualTo("DINNER_HOUSEHOLD_REQUIRED"));

        verifyNoInteractions(ingredientMapper, inventoryMapper);
    }

    @ParameterizedTest(name = "{0} member cannot list old-household inventory")
    @ValueSource(strings = {"LEFT", "REMOVED"})
    void formerMemberCannotListOldHouseholdInventory(String membershipStatus) {
        when(accessService.requireActiveHousehold(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThatThrownBy(() -> service.listInventory(7L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode().name())
                                .isEqualTo("DINNER_HOUSEHOLD_REQUIRED"));

        verifyNoInteractions(ingredientMapper, inventoryMapper);
    }

    @Test
    void deletingAnInventoryItemUsesExactVersion() {
        DinnerHouseholdInventoryEntity item = inventory(11L, 3L, "6.000", "枚", 2L);
        item.setId(21L);
        stubLockedAccess(7L, 11L);
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L)).thenReturn(item);

        service.removeInventoryItem(7L, 3L, 2L);

        verify(inventoryMapper).delete(any());
        InOrder order = inOrder(accessService, inventoryMapper);
        order.verify(accessService).lockActiveHouseholdContext(7L);
        order.verify(inventoryMapper)
                .selectByHouseholdAndIngredientForUpdate(11L, 3L);
    }

    @Test
    void staleHouseholdContextStopsInventoryRemovalBeforeDomainLookup() {
        when(accessService.lockActiveHouseholdContext(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThatThrownBy(() -> service.removeInventoryItem(7L, 3L, 1L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        verifyNoInteractions(ingredientMapper, inventoryMapper);
    }

    @Test
    void inventoryRemovalLockFailureBecomesVersionConflict() {
        stubLockedAccess(7L, 11L);
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L))
                .thenThrow(new CannotAcquireLockException("lock wait timeout"));

        assertThatThrownBy(() -> service.removeInventoryItem(7L, 3L, 1L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_VERSION_CONFLICT));

        verify(inventoryMapper, never()).delete(any());
    }

    @Test
    void deletingMissingInventoryItemReturnsNotFound() {
        stubLockedAccess(7L, 11L);
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
        stubLockedAccess(7L, 11L);
        when(inventoryMapper.selectByHouseholdAndIngredientForUpdate(11L, 3L)).thenReturn(item);

        assertThatThrownBy(() -> service.removeInventoryItem(7L, 3L, 1L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INVENTORY_VERSION_CONFLICT));
        verify(inventoryMapper, never()).delete(any());
    }

    private void stubLockedAccess(Long userId, Long householdId) {
        LockedHouseholdContext context = mock(LockedHouseholdContext.class);
        when(context.access()).thenReturn(access(userId, householdId));
        when(accessService.lockActiveHouseholdContext(userId)).thenReturn(context);
    }

    private ActiveHouseholdAccess access(Long userId, Long householdId) {
        return new ActiveHouseholdAccess(
                userId,
                householdId,
                31L,
                1L,
                "MEMBER",
                LocalDateTime.of(2026, 7, 1, 0, 0),
                1L,
                "Asia/Shanghai");
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
