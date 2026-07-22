package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdDraftLifecycleService.LockedTerminationRecipes;
import com.osheeep.server.dinner.household.DinnerHouseholdOperationService.HouseholdOperationCommand;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import com.osheeep.server.dinner.household.mapper.DinnerInviteCodeMapper;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeIngredientEntity;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerMembershipTerminationServiceTest {

    private static final Long HOUSEHOLD_ID = 11L;
    private static final Long OWNER_USER_ID = 7L;
    private static final Long MEMBER_USER_ID = 8L;
    private static final Long OWNER_MEMBERSHIP_ID = 31L;
    private static final Long MEMBER_MEMBERSHIP_ID = 32L;
    private static final Long HOUSEHOLD_VERSION = 8L;
    private static final Long INVITE_REVISION = 5L;
    private static final Long OWNER_MEMBERSHIP_VERSION = 4L;
    private static final Long MEMBER_MEMBERSHIP_VERSION = 3L;
    private static final String IDEMPOTENCY_KEY =
            "7b20fb9b-a868-48bf-98e5-36643b9921b1";
    private static final String FINGERPRINT = "household-operation:v1:test-fingerprint";
    private static final Instant CLOCK_INSTANT =
            Instant.parse("2026-07-22T05:00:00.123456789Z");
    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 7, 22, 5, 0, 0, 123_000_000);
    private static final LocalDateTime JOINED_AT =
            LocalDateTime.of(2026, 7, 1, 3, 0);

    @Mock private UserMapper userMapper;
    @Mock private DinnerHouseholdOperationMapper operationMapper;
    @Mock private DinnerHouseholdMapper householdMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerInviteCodeMapper inviteMapper;
    @Mock private DinnerMenuMapper menuMapper;
    @Mock private DinnerMenuSelectionMapper selectionMapper;
    @Mock private DinnerHouseholdDraftLifecycleService draftLifecycleService;
    @Mock private DinnerHouseholdInventoryMapper inventoryMapper;
    @Mock private DinnerIngredientMapper ingredientMapper;

    private DinnerMembershipTerminationService service;

    @BeforeEach
    void setUp() {
        service = new DinnerMembershipTerminationService(
                userMapper,
                operationMapper,
                householdMapper,
                memberMapper,
                inviteMapper,
                menuMapper,
                selectionMapper,
                draftLifecycleService,
                inventoryMapper,
                ingredientMapper,
                new ObjectMapper(),
                Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC));
    }

    @Test
    void memberLeaveLocksEveryAggregateThenAppliesTheExactSharedTerminationTransition() {
        HouseholdOperationCommand command = leaveCommand();
        DinnerHouseholdMemberEntity owner = ownerMembership();
        DinnerHouseholdMemberEntity member = memberMembership();
        DinnerHouseholdEntity household = household();
        stubLockedContext(MEMBER_USER_ID, member, household, List.of(owner, member));

        List<DinnerInviteCodeEntity> invites = List.of(
                invite(61L), invite(62L));
        List<DinnerMenuEntity> menus = List.of(
                menu(21L, "DRAFT", 4L),
                menu(22L, "CONFIRMED", 9L));
        List<DinnerMenuSelectionEntity> selections = List.of(
                selection(71L, 21L, MEMBER_USER_ID, 201L),
                selection(72L, 21L, OWNER_USER_ID, 202L),
                selection(73L, 22L, MEMBER_USER_ID, 203L));
        List<DinnerRecipeEntity> recipes = List.of(
                recipe(81L, MEMBER_USER_ID, "DRAFT", 5L),
                recipe(82L, MEMBER_USER_ID, "PUBLISHED", 6L),
                recipe(83L, OWNER_USER_ID, "ARCHIVED", 7L));
        LockedTerminationRecipes recipeLocks =
                new LockedTerminationRecipes(recipes, List.of());
        List<DinnerRecipeIngredientEntity> recipeIngredients = List.of(
                recipeIngredient(91L, 81L, 101L),
                recipeIngredient(92L, 81L, 1001L));
        List<DinnerHouseholdInventoryEntity> inventory = List.of(
                inventory(101L, 101L), inventory(102L, 102L));
        List<DinnerIngredientEntity> householdIngredients = List.of(
                householdIngredient(101L), householdIngredient(102L));

        when(inviteMapper.selectAllOpenByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(invites);
        when(menuMapper.selectUncompletedByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(menus);
        when(selectionMapper.selectByMenuIdsForUpdate(List.of(21L, 22L)))
                .thenReturn(selections);
        when(draftLifecycleService.lockTerminationRecipes(
                MEMBER_USER_ID, HOUSEHOLD_ID)).thenReturn(recipeLocks);
        when(draftLifecycleService.lockPersonalDraftIngredients(
                MEMBER_USER_ID, HOUSEHOLD_ID, recipeLocks))
                .thenReturn(recipeIngredients);
        when(inventoryMapper.selectAllByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(inventory);
        when(ingredientMapper.selectAllHouseholdIngredientsForUpdate(HOUSEHOLD_ID))
                .thenReturn(householdIngredients);
        when(inviteMapper.revokeOpenInvite(
                61L, HOUSEHOLD_ID, NOW, "MEMBERSHIP_CHANGED")).thenReturn(1);
        when(inviteMapper.revokeOpenInvite(
                62L, HOUSEHOLD_ID, NOW, "MEMBERSHIP_CHANGED")).thenReturn(1);
        when(selectionMapper.deleteByMenuIdsAndUserId(
                List.of(21L, 22L), MEMBER_USER_ID)).thenReturn(2);
        when(menuMapper.resetUncompletedMenus(HOUSEHOLD_ID, List.of(21L, 22L)))
                .thenReturn(2);
        when(memberMapper.endActiveMember(
                MEMBER_MEMBERSHIP_ID,
                HOUSEHOLD_ID,
                MEMBER_USER_ID,
                MEMBER_MEMBERSHIP_VERSION,
                "LEFT",
                NOW,
                MEMBER_USER_ID,
                "SELF_LEFT")).thenReturn(1);
        when(householdMapper.advanceMembershipAndInviteRevision(
                HOUSEHOLD_ID, HOUSEHOLD_VERSION, INVITE_REVISION)).thenReturn(1);
        when(operationMapper.insert(any(DinnerHouseholdOperationEntity.class)))
                .thenReturn(1);

        var result = service.terminate(command);

        ArgumentCaptor<DinnerHouseholdOperationEntity> operationCaptor =
                ArgumentCaptor.forClass(DinnerHouseholdOperationEntity.class);
        InOrder order = inOrder(
                userMapper,
                operationMapper,
                memberMapper,
                householdMapper,
                inviteMapper,
                menuMapper,
                selectionMapper,
                draftLifecycleService,
                inventoryMapper,
                ingredientMapper);
        order.verify(userMapper).selectByIdForUpdate(MEMBER_USER_ID);
        order.verify(operationMapper).selectByActorAndIdempotencyKeyForUpdate(
                MEMBER_USER_ID, IDEMPOTENCY_KEY);
        order.verify(memberMapper).selectActiveByUserId(MEMBER_USER_ID);
        order.verify(householdMapper).selectByIdForUpdate(HOUSEHOLD_ID);
        order.verify(memberMapper).selectActiveByHouseholdIdForUpdate(HOUSEHOLD_ID);
        order.verify(inviteMapper).selectAllOpenByHouseholdIdForUpdate(HOUSEHOLD_ID);
        order.verify(menuMapper).selectUncompletedByHouseholdIdForUpdate(HOUSEHOLD_ID);
        order.verify(selectionMapper).selectByMenuIdsForUpdate(List.of(21L, 22L));
        order.verify(draftLifecycleService).lockTerminationRecipes(
                MEMBER_USER_ID, HOUSEHOLD_ID);
        order.verify(draftLifecycleService).lockPersonalDraftIngredients(
                MEMBER_USER_ID, HOUSEHOLD_ID, recipeLocks);
        order.verify(inventoryMapper).selectAllByHouseholdIdForUpdate(HOUSEHOLD_ID);
        order.verify(ingredientMapper).selectAllHouseholdIngredientsForUpdate(HOUSEHOLD_ID);
        order.verify(inviteMapper).revokeOpenInvite(
                61L, HOUSEHOLD_ID, NOW, "MEMBERSHIP_CHANGED");
        order.verify(inviteMapper).revokeOpenInvite(
                62L, HOUSEHOLD_ID, NOW, "MEMBERSHIP_CHANGED");
        order.verify(selectionMapper).deleteByMenuIdsAndUserId(
                List.of(21L, 22L), MEMBER_USER_ID);
        order.verify(menuMapper).resetUncompletedMenus(
                HOUSEHOLD_ID, List.of(21L, 22L));
        order.verify(draftLifecycleService).detachPersonalDrafts(
                MEMBER_USER_ID,
                HOUSEHOLD_ID,
                recipeLocks,
                recipeIngredients,
                householdIngredients);
        order.verify(memberMapper).endActiveMember(
                MEMBER_MEMBERSHIP_ID,
                HOUSEHOLD_ID,
                MEMBER_USER_ID,
                MEMBER_MEMBERSHIP_VERSION,
                "LEFT",
                NOW,
                MEMBER_USER_ID,
                "SELF_LEFT");
        order.verify(householdMapper).advanceMembershipAndInviteRevision(
                HOUSEHOLD_ID, HOUSEHOLD_VERSION, INVITE_REVISION);
        order.verify(operationMapper).insert(operationCaptor.capture());

        DinnerHouseholdOperationEntity stored = operationCaptor.getValue();
        assertThat(stored.getHouseholdId()).isEqualTo(HOUSEHOLD_ID);
        assertThat(stored.getActorId()).isEqualTo(MEMBER_USER_ID);
        assertThat(stored.getActorMembershipId()).isEqualTo(MEMBER_MEMBERSHIP_ID);
        assertThat(stored.getTargetMemberId()).isNull();
        assertThat(stored.getOperationType())
                .isEqualTo(DinnerHouseholdOperationService.MEMBER_LEAVE);
        assertThat(stored.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(stored.getRequestFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(stored.getResultSchemaVersion()).isEqualTo(1);
        assertThat(stored.getResultHouseholdVersion()).isEqualTo(9L);
        assertThat(stored.getResultPayload()).isEqualTo("{\"actorHasHousehold\":false}");
        assertThat(stored.getCreatedAt()).isEqualTo(NOW);
        assertThat(stored.getExpiresAt()).isEqualTo(NOW.plusDays(14));

        assertThat(result.operationType())
                .isEqualTo(DinnerHouseholdOperationService.MEMBER_LEAVE);
        assertThat(result.replayed()).isFalse();
        assertThat(result.actorHasHousehold()).isFalse();
        assertThat(result.householdVersion()).isEqualTo(9L);

        // Inventory, household ingredients, and published/archived recipes are locked only;
        // their mutation contract belongs to the draft lifecycle and no direct write is allowed.
        verifyNoMoreInteractions(inventoryMapper, ingredientMapper);
    }

    @Test
    void ownerRemovalEndsOnlyTheTargetAndKeepsTheActorInTheHousehold() {
        HouseholdOperationCommand command = removeCommand(
                OWNER_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                MEMBER_MEMBERSHIP_ID,
                MEMBER_MEMBERSHIP_VERSION);
        DinnerHouseholdMemberEntity owner = ownerMembership();
        DinnerHouseholdMemberEntity member = memberMembership();
        stubLockedContext(OWNER_USER_ID, owner, household(), List.of(owner, member));
        stubEmptyChildLocks(MEMBER_USER_ID);
        when(memberMapper.endActiveMember(
                MEMBER_MEMBERSHIP_ID,
                HOUSEHOLD_ID,
                MEMBER_USER_ID,
                MEMBER_MEMBERSHIP_VERSION,
                "REMOVED",
                NOW,
                OWNER_USER_ID,
                "OWNER_REMOVED")).thenReturn(1);
        when(householdMapper.advanceMembershipAndInviteRevision(
                HOUSEHOLD_ID, HOUSEHOLD_VERSION, INVITE_REVISION)).thenReturn(1);
        when(operationMapper.insert(any(DinnerHouseholdOperationEntity.class)))
                .thenReturn(1);

        var result = service.terminate(command);

        ArgumentCaptor<DinnerHouseholdOperationEntity> operationCaptor =
                ArgumentCaptor.forClass(DinnerHouseholdOperationEntity.class);
        verify(operationMapper).insert(operationCaptor.capture());
        DinnerHouseholdOperationEntity stored = operationCaptor.getValue();
        assertThat(stored.getActorId()).isEqualTo(OWNER_USER_ID);
        assertThat(stored.getActorMembershipId()).isEqualTo(OWNER_MEMBERSHIP_ID);
        assertThat(stored.getTargetMemberId()).isEqualTo(MEMBER_MEMBERSHIP_ID);
        assertThat(stored.getOperationType())
                .isEqualTo(DinnerHouseholdOperationService.OWNER_REMOVE);
        assertThat(stored.getResultPayload()).isEqualTo("{\"actorHasHousehold\":true}");
        assertThat(stored.getCreatedAt()).isEqualTo(NOW);
        assertThat(stored.getExpiresAt()).isEqualTo(NOW.plusDays(14));
        assertThat(result.replayed()).isFalse();
        assertThat(result.actorHasHousehold()).isTrue();
        assertThat(result.householdVersion()).isEqualTo(9L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownerCannotLeaveAOneOrTwoPersonHousehold(boolean householdHasMember) {
        DinnerHouseholdMemberEntity owner = ownerMembership();
        List<DinnerHouseholdMemberEntity> members = householdHasMember
                ? List.of(owner, memberMembership())
                : List.of(owner);
        stubLockedContext(OWNER_USER_ID, owner, household(), members);
        HouseholdOperationCommand command = new HouseholdOperationCommand(
                OWNER_USER_ID,
                OWNER_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                null,
                null,
                DinnerHouseholdOperationService.MEMBER_LEAVE,
                IDEMPOTENCY_KEY,
                FINGERPRINT);

        assertBusinessError(
                () -> service.terminate(command),
                ErrorCode.DINNER_HOUSEHOLD_OWNER_CANNOT_LEAVE);

        assertNoTerminalMutations();
    }

    @Test
    void memberCannotRemoveAnotherMembership() {
        DinnerHouseholdMemberEntity owner = ownerMembership();
        DinnerHouseholdMemberEntity member = memberMembership();
        stubLockedContext(MEMBER_USER_ID, member, household(), List.of(owner, member));
        HouseholdOperationCommand command = new HouseholdOperationCommand(
                MEMBER_USER_ID,
                MEMBER_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                OWNER_MEMBERSHIP_ID,
                OWNER_MEMBERSHIP_VERSION,
                DinnerHouseholdOperationService.OWNER_REMOVE,
                IDEMPOTENCY_KEY,
                FINGERPRINT);

        assertBusinessError(
                () -> service.terminate(command),
                ErrorCode.DINNER_HOUSEHOLD_OWNER_REQUIRED);

        assertNoTerminalMutations();
    }

    @Test
    void ownerCannotRemoveItsOwnMembership() {
        DinnerHouseholdMemberEntity owner = ownerMembership();
        DinnerHouseholdMemberEntity member = memberMembership();
        stubLockedContext(OWNER_USER_ID, owner, household(), List.of(owner, member));

        assertBusinessError(
                () -> service.terminate(removeCommand(
                        OWNER_MEMBERSHIP_ID,
                        HOUSEHOLD_VERSION,
                        OWNER_MEMBERSHIP_ID,
                        OWNER_MEMBERSHIP_VERSION)),
                ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);

        assertNoTerminalMutations();
    }

    @Test
    void foreignTargetIsNotDisclosedAsAnExistingMembership() {
        DinnerHouseholdMemberEntity owner = ownerMembership();
        DinnerHouseholdMemberEntity member = memberMembership();
        stubLockedContext(OWNER_USER_ID, owner, household(), List.of(owner, member));

        assertBusinessError(
                () -> service.terminate(removeCommand(
                        OWNER_MEMBERSHIP_ID, HOUSEHOLD_VERSION, 999L, 1L)),
                ErrorCode.DINNER_HOUSEHOLD_MEMBER_NOT_FOUND);

        assertNoTerminalMutations();
    }

    @Test
    void inactiveTargetIsNotAcceptedFromTheActiveMembershipSnapshot() {
        DinnerHouseholdMemberEntity owner = ownerMembership();
        // The active-membership lock intentionally contains no ended row.
        stubLockedContext(OWNER_USER_ID, owner, household(), List.of(owner));

        assertBusinessError(
                () -> service.terminate(removeCommand(
                        OWNER_MEMBERSHIP_ID,
                        HOUSEHOLD_VERSION,
                        MEMBER_MEMBERSHIP_ID,
                        MEMBER_MEMBERSHIP_VERSION + 1)),
                ErrorCode.DINNER_HOUSEHOLD_MEMBER_NOT_FOUND);

        assertNoTerminalMutations();
    }

    @Test
    void mismatchedActorMembershipIdIsAStateConflict() {
        DinnerHouseholdMemberEntity owner = ownerMembership();
        DinnerHouseholdMemberEntity member = memberMembership();
        stubLockedContext(MEMBER_USER_ID, member, household(), List.of(owner, member));
        HouseholdOperationCommand command = new HouseholdOperationCommand(
                MEMBER_USER_ID,
                999L,
                HOUSEHOLD_VERSION,
                null,
                null,
                DinnerHouseholdOperationService.MEMBER_LEAVE,
                IDEMPOTENCY_KEY,
                FINGERPRINT);

        assertBusinessError(
                () -> service.terminate(command),
                ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);

        assertNoTerminalMutations();
    }

    @Test
    void actorMembershipVersionChangeBetweenCandidateReadAndFullLockIsAConflict() {
        DinnerHouseholdMemberEntity candidate = memberMembership();
        DinnerHouseholdMemberEntity locked = memberMembership();
        locked.setVersion(MEMBER_MEMBERSHIP_VERSION + 1);
        stubLockedContext(
                MEMBER_USER_ID,
                candidate,
                household(),
                List.of(ownerMembership(), locked));

        assertBusinessError(
                () -> service.terminate(leaveCommand()),
                ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);

        assertNoTerminalMutations();
    }

    @Test
    void staleHouseholdVersionIsRejectedBeforeAnyChildAggregateLock() {
        DinnerHouseholdMemberEntity owner = ownerMembership();
        DinnerHouseholdMemberEntity member = memberMembership();
        stubLockedContext(MEMBER_USER_ID, member, household(), List.of(owner, member));
        HouseholdOperationCommand command = new HouseholdOperationCommand(
                MEMBER_USER_ID,
                MEMBER_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION - 1,
                null,
                null,
                DinnerHouseholdOperationService.MEMBER_LEAVE,
                IDEMPOTENCY_KEY,
                FINGERPRINT);

        assertBusinessError(
                () -> service.terminate(command),
                ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);

        verifyNoInteractions(inviteMapper, menuMapper, selectionMapper,
                draftLifecycleService, inventoryMapper, ingredientMapper);
        assertNoTerminalMutations();
    }

    @Test
    void staleTargetMembershipVersionIsAStateConflict() {
        DinnerHouseholdMemberEntity owner = ownerMembership();
        DinnerHouseholdMemberEntity member = memberMembership();
        stubLockedContext(OWNER_USER_ID, owner, household(), List.of(owner, member));

        assertBusinessError(
                () -> service.terminate(removeCommand(
                        OWNER_MEMBERSHIP_ID,
                        HOUSEHOLD_VERSION,
                        MEMBER_MEMBERSHIP_ID,
                        MEMBER_MEMBERSHIP_VERSION - 1)),
                ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);

        assertNoTerminalMutations();
    }

    @Test
    void operationIsRecheckedAfterActorLockAndReplayedEvenWhenMembershipAlreadyEnded() {
        HouseholdOperationCommand command = leaveCommand();
        DinnerHouseholdOperationEntity existing = operationResult(
                DinnerHouseholdOperationService.MEMBER_LEAVE,
                MEMBER_USER_ID,
                MEMBER_MEMBERSHIP_ID,
                null,
                false);
        when(userMapper.selectByIdForUpdate(MEMBER_USER_ID))
                .thenReturn(activeUser(MEMBER_USER_ID));
        when(operationMapper.selectByActorAndIdempotencyKeyForUpdate(
                MEMBER_USER_ID, IDEMPOTENCY_KEY)).thenReturn(existing);

        var result = service.terminate(command);

        InOrder order = inOrder(userMapper, operationMapper);
        order.verify(userMapper).selectByIdForUpdate(MEMBER_USER_ID);
        order.verify(operationMapper).selectByActorAndIdempotencyKeyForUpdate(
                MEMBER_USER_ID, IDEMPOTENCY_KEY);
        assertThat(result.operationType())
                .isEqualTo(DinnerHouseholdOperationService.MEMBER_LEAVE);
        assertThat(result.replayed()).isTrue();
        assertThat(result.actorHasHousehold()).isFalse();
        assertThat(result.householdVersion()).isEqualTo(9L);
        verifyNoInteractions(
                householdMapper,
                memberMapper,
                inviteMapper,
                menuMapper,
                selectionMapper,
                draftLifecycleService,
                inventoryMapper,
                ingredientMapper);
        verify(operationMapper, never()).insert(any(DinnerHouseholdOperationEntity.class));
    }

    @Test
    void concurrentRequestsPastOuterPrecheckSerializeOnActorAndSecondReplaysStoredSuccess()
            throws Exception {
        HouseholdOperationCommand command = leaveCommand();
        DinnerHouseholdMemberEntity owner = ownerMembership();
        DinnerHouseholdMemberEntity member = memberMembership();
        DinnerHouseholdEntity household = household();
        List<DinnerMenuEntity> menus = List.of(menu(21L, "DRAFT", 4L));
        List<DinnerMenuSelectionEntity> selections = List.of(
                selection(71L, 21L, MEMBER_USER_ID, 201L));
        ReentrantLock actorRowLock = new ReentrantLock(true);
        AtomicBoolean membershipEnded = new AtomicBoolean();
        AtomicReference<DinnerHouseholdOperationEntity> storedOperation =
                new AtomicReference<>();
        CountDownLatch bothOuterPrechecksComplete = new CountDownLatch(2);
        CountDownLatch startTermination = new CountDownLatch(1);

        when(operationMapper.selectByActorAndIdempotencyKey(
                MEMBER_USER_ID, IDEMPOTENCY_KEY))
                .thenAnswer(invocation -> storedOperation.get());
        when(userMapper.selectByIdForUpdate(MEMBER_USER_ID)).thenAnswer(invocation -> {
            if (!actorRowLock.tryLock(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out acquiring the simulated actor row lock");
            }
            return activeUser(MEMBER_USER_ID);
        });
        when(operationMapper.selectByActorAndIdempotencyKeyForUpdate(
                MEMBER_USER_ID, IDEMPOTENCY_KEY)).thenAnswer(invocation -> {
                    assertThat(actorRowLock.isHeldByCurrentThread()).isTrue();
                    DinnerHouseholdOperationEntity existing = storedOperation.get();
                    if (existing != null) {
                        actorRowLock.unlock();
                    }
                    return existing;
                });
        when(memberMapper.selectActiveByUserId(MEMBER_USER_ID)).thenAnswer(invocation -> {
            if (membershipEnded.get()) {
                throw new AssertionError(
                        "A replay must not query an already-ended membership");
            }
            return member;
        });
        when(householdMapper.selectByIdForUpdate(HOUSEHOLD_ID)).thenReturn(household);
        when(memberMapper.selectActiveByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(List.of(owner, member));
        when(inviteMapper.selectAllOpenByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(List.of());
        when(menuMapper.selectUncompletedByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(menus);
        when(selectionMapper.selectByMenuIdsForUpdate(List.of(21L)))
                .thenReturn(selections);
        LockedTerminationRecipes recipeLocks =
                new LockedTerminationRecipes(List.of(), List.of());
        when(draftLifecycleService.lockTerminationRecipes(
                MEMBER_USER_ID, HOUSEHOLD_ID)).thenReturn(recipeLocks);
        when(draftLifecycleService.lockPersonalDraftIngredients(
                MEMBER_USER_ID, HOUSEHOLD_ID, recipeLocks)).thenReturn(List.of());
        when(inventoryMapper.selectAllByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(List.of());
        when(ingredientMapper.selectAllHouseholdIngredientsForUpdate(HOUSEHOLD_ID))
                .thenReturn(List.of());
        when(selectionMapper.deleteByMenuIdsAndUserId(
                List.of(21L), MEMBER_USER_ID)).thenReturn(1);
        when(menuMapper.resetUncompletedMenus(HOUSEHOLD_ID, List.of(21L)))
                .thenReturn(1);
        when(memberMapper.endActiveMember(
                MEMBER_MEMBERSHIP_ID,
                HOUSEHOLD_ID,
                MEMBER_USER_ID,
                MEMBER_MEMBERSHIP_VERSION,
                "LEFT",
                NOW,
                MEMBER_USER_ID,
                "SELF_LEFT")).thenAnswer(invocation -> {
                    membershipEnded.set(true);
                    return 1;
                });
        when(householdMapper.advanceMembershipAndInviteRevision(
                HOUSEHOLD_ID, HOUSEHOLD_VERSION, INVITE_REVISION)).thenReturn(1);
        when(operationMapper.insert(any(DinnerHouseholdOperationEntity.class)))
                .thenAnswer(invocation -> {
                    assertThat(actorRowLock.isHeldByCurrentThread()).isTrue();
                    assertThat(membershipEnded.get()).isTrue();
                    storedOperation.set(invocation.getArgument(0));
                    actorRowLock.unlock();
                    return 1;
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<com.osheeep.server.dinner.household.dto.HouseholdMutationResponse> first =
                executor.submit(() -> {
                    assertThat(operationMapper.selectByActorAndIdempotencyKey(
                            MEMBER_USER_ID, IDEMPOTENCY_KEY)).isNull();
                    bothOuterPrechecksComplete.countDown();
                    if (!startTermination.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out at the outer-precheck barrier");
                    }
                    try {
                        return service.terminate(command);
                    } finally {
                        if (actorRowLock.isHeldByCurrentThread()) {
                            actorRowLock.unlock();
                        }
                    }
                });
        Future<com.osheeep.server.dinner.household.dto.HouseholdMutationResponse> second =
                executor.submit(() -> {
                    assertThat(operationMapper.selectByActorAndIdempotencyKey(
                            MEMBER_USER_ID, IDEMPOTENCY_KEY)).isNull();
                    bothOuterPrechecksComplete.countDown();
                    if (!startTermination.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out at the outer-precheck barrier");
                    }
                    try {
                        return service.terminate(command);
                    } finally {
                        if (actorRowLock.isHeldByCurrentThread()) {
                            actorRowLock.unlock();
                        }
                    }
                });

        try {
            assertThat(bothOuterPrechecksComplete.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(storedOperation).hasValue(null);
            startTermination.countDown();

            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(List.of(firstResult.replayed(), secondResult.replayed()))
                    .containsExactlyInAnyOrder(false, true);
            assertThat(List.of(
                    firstResult.actorHasHousehold(),
                    secondResult.actorHasHousehold()))
                    .containsOnly(false);
            assertThat(List.of(
                    firstResult.householdVersion(),
                    secondResult.householdVersion()))
                    .containsOnly(9L);
        } finally {
            startTermination.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        verify(operationMapper, times(2)).selectByActorAndIdempotencyKey(
                MEMBER_USER_ID, IDEMPOTENCY_KEY);
        verify(userMapper, times(2)).selectByIdForUpdate(MEMBER_USER_ID);
        verify(operationMapper, times(2)).selectByActorAndIdempotencyKeyForUpdate(
                MEMBER_USER_ID, IDEMPOTENCY_KEY);
        verify(memberMapper, times(1)).selectActiveByUserId(MEMBER_USER_ID);
        verify(householdMapper, times(1)).selectByIdForUpdate(HOUSEHOLD_ID);
        verify(memberMapper, times(1)).selectActiveByHouseholdIdForUpdate(HOUSEHOLD_ID);
        verify(inviteMapper, times(1)).selectAllOpenByHouseholdIdForUpdate(HOUSEHOLD_ID);
        verify(menuMapper, times(1)).selectUncompletedByHouseholdIdForUpdate(HOUSEHOLD_ID);
        verify(selectionMapper, times(1)).selectByMenuIdsForUpdate(List.of(21L));
        verify(draftLifecycleService, times(1)).lockTerminationRecipes(
                MEMBER_USER_ID, HOUSEHOLD_ID);
        verify(draftLifecycleService, times(1)).lockPersonalDraftIngredients(
                MEMBER_USER_ID, HOUSEHOLD_ID, recipeLocks);
        verify(inventoryMapper, times(1)).selectAllByHouseholdIdForUpdate(HOUSEHOLD_ID);
        verify(ingredientMapper, times(1))
                .selectAllHouseholdIngredientsForUpdate(HOUSEHOLD_ID);
        verify(selectionMapper, times(1)).deleteByMenuIdsAndUserId(
                List.of(21L), MEMBER_USER_ID);
        verify(menuMapper, times(1)).resetUncompletedMenus(HOUSEHOLD_ID, List.of(21L));
        verify(draftLifecycleService, times(1)).detachPersonalDrafts(
                MEMBER_USER_ID,
                HOUSEHOLD_ID,
                recipeLocks,
                List.of(),
                List.of());
        verify(memberMapper, times(1)).endActiveMember(
                MEMBER_MEMBERSHIP_ID,
                HOUSEHOLD_ID,
                MEMBER_USER_ID,
                MEMBER_MEMBERSHIP_VERSION,
                "LEFT",
                NOW,
                MEMBER_USER_ID,
                "SELF_LEFT");
        verify(householdMapper, times(1)).advanceMembershipAndInviteRevision(
                HOUSEHOLD_ID, HOUSEHOLD_VERSION, INVITE_REVISION);
        verify(operationMapper, times(1)).insert(any(DinnerHouseholdOperationEntity.class));
        assertThat(membershipEnded.get()).isTrue();
        assertThat(storedOperation).doesNotHaveValue(null);
    }

    @Test
    void completedMenuCanNeverReachSelectionOrResetMutations() {
        DinnerHouseholdMemberEntity owner = ownerMembership();
        DinnerHouseholdMemberEntity member = memberMembership();
        stubLockedContext(MEMBER_USER_ID, member, household(), List.of(owner, member));
        when(inviteMapper.selectAllOpenByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(List.of());
        when(menuMapper.selectUncompletedByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(List.of(menu(23L, "COMPLETED", 10L)));

        assertBusinessError(
                () -> service.terminate(leaveCommand()),
                ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);

        verifyNoInteractions(
                selectionMapper,
                draftLifecycleService,
                inventoryMapper,
                ingredientMapper);
        verify(menuMapper, never()).resetUncompletedMenus(anyLong(), any());
        assertNoTerminalMutations();
    }

    private void stubLockedContext(
            Long actorUserId,
            DinnerHouseholdMemberEntity candidate,
            DinnerHouseholdEntity household,
            List<DinnerHouseholdMemberEntity> lockedMembers
    ) {
        when(userMapper.selectByIdForUpdate(actorUserId)).thenReturn(activeUser(actorUserId));
        when(memberMapper.selectActiveByUserId(actorUserId)).thenReturn(candidate);
        when(householdMapper.selectByIdForUpdate(HOUSEHOLD_ID)).thenReturn(household);
        when(memberMapper.selectActiveByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(lockedMembers);
    }

    private void stubEmptyChildLocks(Long targetUserId) {
        LockedTerminationRecipes recipeLocks =
                new LockedTerminationRecipes(List.of(), List.of());
        when(inviteMapper.selectAllOpenByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(List.of());
        when(menuMapper.selectUncompletedByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(List.of());
        when(draftLifecycleService.lockTerminationRecipes(
                targetUserId, HOUSEHOLD_ID)).thenReturn(recipeLocks);
        when(draftLifecycleService.lockPersonalDraftIngredients(
                targetUserId, HOUSEHOLD_ID, recipeLocks)).thenReturn(List.of());
        when(inventoryMapper.selectAllByHouseholdIdForUpdate(HOUSEHOLD_ID))
                .thenReturn(List.of());
        when(ingredientMapper.selectAllHouseholdIngredientsForUpdate(HOUSEHOLD_ID))
                .thenReturn(List.of());
    }

    private void assertNoTerminalMutations() {
        verify(inviteMapper, never()).revokeOpenInvite(
                anyLong(), anyLong(), any(LocalDateTime.class), any(String.class));
        verify(selectionMapper, never()).deleteByMenuIdsAndUserId(any(), anyLong());
        verify(menuMapper, never()).resetUncompletedMenus(anyLong(), any());
        verify(draftLifecycleService, never()).detachPersonalDrafts(
                anyLong(), anyLong(), any(), any(), any());
        verify(memberMapper, never()).endActiveMember(
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                any(String.class),
                any(LocalDateTime.class),
                anyLong(),
                any(String.class));
        verify(householdMapper, never()).advanceMembershipAndInviteRevision(
                anyLong(), anyLong(), anyLong());
        verify(operationMapper, never()).insert(any(DinnerHouseholdOperationEntity.class));
    }

    private void assertBusinessError(ThrowingCallable call, ErrorCode expectedCode) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(expectedCode));
    }

    private HouseholdOperationCommand leaveCommand() {
        return new HouseholdOperationCommand(
                MEMBER_USER_ID,
                MEMBER_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                null,
                null,
                DinnerHouseholdOperationService.MEMBER_LEAVE,
                IDEMPOTENCY_KEY,
                FINGERPRINT);
    }

    private HouseholdOperationCommand removeCommand(
            Long actorMembershipId,
            Long expectedHouseholdVersion,
            Long targetMembershipId,
            Long targetMembershipVersion
    ) {
        return new HouseholdOperationCommand(
                OWNER_USER_ID,
                actorMembershipId,
                expectedHouseholdVersion,
                targetMembershipId,
                targetMembershipVersion,
                DinnerHouseholdOperationService.OWNER_REMOVE,
                IDEMPOTENCY_KEY,
                FINGERPRINT);
    }

    private UserEntity activeUser(Long id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setStatus("ACTIVE");
        return user;
    }

    private DinnerHouseholdEntity household() {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(HOUSEHOLD_ID);
        household.setName("小羊的家");
        household.setTimezone("Asia/Shanghai");
        household.setStatus("ACTIVE");
        household.setVersion(HOUSEHOLD_VERSION);
        household.setInviteRevision(INVITE_REVISION);
        return household;
    }

    private DinnerHouseholdMemberEntity ownerMembership() {
        return membership(
                OWNER_MEMBERSHIP_ID,
                OWNER_USER_ID,
                "OWNER",
                1,
                OWNER_MEMBERSHIP_VERSION);
    }

    private DinnerHouseholdMemberEntity memberMembership() {
        return membership(
                MEMBER_MEMBERSHIP_ID,
                MEMBER_USER_ID,
                "MEMBER",
                2,
                MEMBER_MEMBERSHIP_VERSION);
    }

    private DinnerHouseholdMemberEntity membership(
            Long id,
            Long userId,
            String role,
            int seatNo,
            Long version
    ) {
        DinnerHouseholdMemberEntity membership = new DinnerHouseholdMemberEntity();
        membership.setId(id);
        membership.setHouseholdId(HOUSEHOLD_ID);
        membership.setUserId(userId);
        membership.setRole(role);
        membership.setStatus("ACTIVE");
        membership.setSeatNo(seatNo);
        membership.setVersion(version);
        membership.setJoinedAt(JOINED_AT);
        membership.setHistoryVisibleFrom(JOINED_AT);
        return membership;
    }

    private DinnerInviteCodeEntity invite(Long id) {
        DinnerInviteCodeEntity invite = new DinnerInviteCodeEntity();
        invite.setId(id);
        invite.setHouseholdId(HOUSEHOLD_ID);
        invite.setCodeHash("hash-" + id);
        return invite;
    }

    private DinnerMenuEntity menu(Long id, String status, Long version) {
        DinnerMenuEntity menu = new DinnerMenuEntity();
        menu.setId(id);
        menu.setHouseholdId(HOUSEHOLD_ID);
        menu.setStatus(status);
        menu.setVersion(version);
        if ("CONFIRMED".equals(status)) {
            menu.setConfirmedBy(OWNER_USER_ID);
            menu.setConfirmedAt(NOW.minusDays(1));
        }
        if ("COMPLETED".equals(status)) {
            menu.setCompletedBy(OWNER_USER_ID);
            menu.setCompletedAt(NOW.minusHours(1));
        }
        return menu;
    }

    private DinnerMenuSelectionEntity selection(
            Long id,
            Long menuId,
            Long userId,
            Long recipeId
    ) {
        DinnerMenuSelectionEntity selection = new DinnerMenuSelectionEntity();
        selection.setId(id);
        selection.setMenuId(menuId);
        selection.setUserId(userId);
        selection.setRecipeId(recipeId);
        selection.setRecipeVersion(1L);
        return selection;
    }

    private DinnerRecipeEntity recipe(
            Long id,
            Long creatorId,
            String status,
            Long version
    ) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(id);
        recipe.setScope("HOUSEHOLD");
        recipe.setHouseholdId(HOUSEHOLD_ID);
        recipe.setCreatorId(creatorId);
        recipe.setLastModifiedBy(creatorId);
        recipe.setStatus(status);
        recipe.setVersion(version);
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

    private DinnerHouseholdInventoryEntity inventory(Long id, Long ingredientId) {
        DinnerHouseholdInventoryEntity item = new DinnerHouseholdInventoryEntity();
        item.setId(id);
        item.setHouseholdId(HOUSEHOLD_ID);
        item.setIngredientId(ingredientId);
        return item;
    }

    private DinnerIngredientEntity householdIngredient(Long id) {
        DinnerIngredientEntity ingredient = new DinnerIngredientEntity();
        ingredient.setId(id);
        ingredient.setScope("HOUSEHOLD");
        ingredient.setHouseholdId(HOUSEHOLD_ID);
        ingredient.setName("食材-" + id);
        ingredient.setStatus("ACTIVE");
        return ingredient;
    }

    private DinnerHouseholdOperationEntity operationResult(
            String operationType,
            Long actorId,
            Long actorMembershipId,
            Long targetMembershipId,
            boolean actorHasHousehold
    ) {
        DinnerHouseholdOperationEntity operation = new DinnerHouseholdOperationEntity();
        operation.setId(501L);
        operation.setHouseholdId(HOUSEHOLD_ID);
        operation.setActorId(actorId);
        operation.setActorMembershipId(actorMembershipId);
        operation.setTargetMemberId(targetMembershipId);
        operation.setOperationType(operationType);
        operation.setIdempotencyKey(IDEMPOTENCY_KEY);
        operation.setRequestFingerprint(FINGERPRINT);
        operation.setResultSchemaVersion(1);
        operation.setResultHouseholdVersion(9L);
        operation.setResultPayload(actorHasHousehold
                ? "{\"actorHasHousehold\":true}"
                : "{\"actorHasHousehold\":false}");
        operation.setCreatedAt(NOW.minusMinutes(1));
        operation.setExpiresAt(NOW.plusDays(14));
        return operation;
    }
}
