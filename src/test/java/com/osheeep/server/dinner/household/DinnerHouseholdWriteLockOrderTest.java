package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.LockedHouseholdContext;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class DinnerHouseholdWriteLockOrderTest {

    private static final LocalDateTime JOINED_AT = LocalDateTime.of(2026, 7, 11, 6, 0);

    @Mock private UserMapper userMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerHouseholdMapper householdMapper;

    private DinnerHouseholdAccessService service;

    @BeforeEach
    void setUp() {
        service = new DinnerHouseholdAccessService(userMapper, memberMapper, householdMapper);
    }

    @Test
    void locksActorThenCandidateHouseholdThenHouseholdThenAllActiveMembers() {
        UserEntity actor = activeUser(7L);
        DinnerHouseholdMemberEntity candidate =
                membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        DinnerHouseholdEntity household = household(11L, "ACTIVE", 8L);
        DinnerHouseholdMemberEntity partner =
                membership(42L, 11L, 8L, "MEMBER", "ACTIVE", 2L);
        List<DinnerHouseholdMemberEntity> lockedMembers = List.of(candidate, partner);
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(actor);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(candidate);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household);
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L)).thenReturn(lockedMembers);

        LockedHouseholdContext context = service.lockActiveHouseholdContext(7L);

        InOrder order = inOrder(userMapper, memberMapper, householdMapper);
        order.verify(userMapper).selectByIdForUpdate(7L);
        order.verify(memberMapper).selectActiveByUserId(7L);
        order.verify(householdMapper).selectByIdForUpdate(11L);
        order.verify(memberMapper).selectActiveByHouseholdIdForUpdate(11L);
        assertThat(context.actorUser()).isSameAs(actor);
        assertThat(context.household()).isSameAs(household);
        assertThat(context.members()).containsExactly(candidate, partner);
        assertThat(context.access().userId()).isEqualTo(7L);
        assertThat(context.access().householdId()).isEqualTo(11L);
        assertThat(context.access().membershipId()).isEqualTo(41L);
        assertThat(context.access().membershipVersion()).isEqualTo(4L);
        assertThat(context.access().role()).isEqualTo("OWNER");
        assertThat(context.access().historyVisibleFrom()).isEqualTo(JOINED_AT);
        assertThat(context.access().householdVersion()).isEqualTo(8L);
        assertThat(context.access().timezone()).isEqualTo("Asia/Shanghai");
        assertThat(service.requireOwner(context)).isSameAs(context);
        assertThatThrownBy(() -> context.members().add(candidate))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void lockedContextRequiresAnExistingTransaction() throws NoSuchMethodException {
        Transactional transaction = DinnerHouseholdAccessService.class
                .getMethod("lockActiveHouseholdContext", Long.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void rejectsMissingLockedActorBeforeLookingUpCandidateHousehold() {
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(null);

        assertBusinessError(() -> service.lockActiveHouseholdContext(7L), "UNAUTHORIZED");

        verifyNoInteractions(memberMapper, householdMapper);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"DELETED", "SUSPENDED"})
    void rejectsUnavailableLockedActorBeforeLookingUpCandidateHousehold(String status) {
        UserEntity actor = activeUser(7L);
        actor.setStatus(status);
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(actor);

        assertBusinessError(() -> service.lockActiveHouseholdContext(7L), "UNAUTHORIZED");

        verifyNoInteractions(memberMapper, householdMapper);
    }

    @Test
    void rejectsDeletedLockedActorBeforeLookingUpCandidateHousehold() {
        UserEntity actor = activeUser(7L);
        actor.setDeletedAt(LocalDateTime.of(2026, 7, 22, 9, 0));
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(actor);

        assertBusinessError(() -> service.lockActiveHouseholdContext(7L), "UNAUTHORIZED");

        verifyNoInteractions(memberMapper, householdMapper);
    }

    @Test
    void actorLockTimeoutIsARecoverableHouseholdConflict() {
        when(userMapper.selectByIdForUpdate(7L))
                .thenThrow(new CannotAcquireLockException("lock wait timeout"));

        assertBusinessError(
                () -> service.lockActiveHouseholdContext(7L),
                "DINNER_HOUSEHOLD_VERSION_CONFLICT");

        verifyNoInteractions(memberMapper, householdMapper);
    }

    @Test
    void rejectsCandidateWithoutHouseholdBeforeTakingAHouseholdLock() {
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        DinnerHouseholdMemberEntity candidate =
                membership(41L, null, 7L, "OWNER", "ACTIVE", 4L);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(candidate);

        assertBusinessError(
                () -> service.lockActiveHouseholdContext(7L),
                "DINNER_HOUSEHOLD_REQUIRED");

        verifyNoInteractions(householdMapper);
        verify(memberMapper, never()).selectActiveByHouseholdIdForUpdate(11L);
    }

    @Test
    void rejectsInactiveLockedHouseholdBeforeTakingMembershipLocks() {
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        DinnerHouseholdMemberEntity candidate =
                membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(candidate);
        when(householdMapper.selectByIdForUpdate(11L))
                .thenReturn(household(11L, "DISSOLVED", 9L));

        assertBusinessError(
                () -> service.lockActiveHouseholdContext(7L),
                "DINNER_HOUSEHOLD_REQUIRED");

        verify(memberMapper, never()).selectActiveByHouseholdIdForUpdate(11L);
    }

    @Test
    void failsClosedWhenActorMembershipVersionChangesBeforeLockedSetIsRead() {
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        DinnerHouseholdMemberEntity candidate =
                membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        DinnerHouseholdMemberEntity changed =
                membership(41L, 11L, 7L, "OWNER", "ACTIVE", 5L);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(candidate);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L, "ACTIVE", 8L));
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L)).thenReturn(List.of(changed));

        assertBusinessError(
                () -> service.lockActiveHouseholdContext(7L),
                "DINNER_HOUSEHOLD_VERSION_CONFLICT");
    }

    @Test
    void failsClosedWhenCandidateMembershipNoLongerBelongsToLockedSet() {
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        DinnerHouseholdMemberEntity candidate =
                membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        DinnerHouseholdMemberEntity partner =
                membership(42L, 11L, 8L, "MEMBER", "ACTIVE", 2L);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(candidate);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L, "ACTIVE", 8L));
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L)).thenReturn(List.of(partner));

        assertBusinessError(
                () -> service.lockActiveHouseholdContext(7L),
                "DINNER_HOUSEHOLD_REQUIRED");
    }

    @Test
    void requiresHouseholdWhenLockedActiveMembershipSetIsEmpty() {
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        DinnerHouseholdMemberEntity candidate =
                membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(candidate);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L, "ACTIVE", 8L));
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L)).thenReturn(List.of());

        assertBusinessError(
                () -> service.lockActiveHouseholdContext(7L),
                "DINNER_HOUSEHOLD_REQUIRED");
    }

    @Test
    void failsClosedWhenActorSeatChangesBeforeLockedSetIsRead() {
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        DinnerHouseholdMemberEntity candidate =
                membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        DinnerHouseholdMemberEntity changed =
                membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        changed.setSeatNo(2);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(candidate);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L, "ACTIVE", 8L));
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L)).thenReturn(List.of(changed));

        assertBusinessError(
                () -> service.lockActiveHouseholdContext(7L),
                "DINNER_HOUSEHOLD_VERSION_CONFLICT");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void failsClosedForDuplicateOrOutOfRangeSeatInLockedFamily(int partnerSeat) {
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        DinnerHouseholdMemberEntity actor =
                membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        DinnerHouseholdMemberEntity partner =
                membership(42L, 11L, 8L, "MEMBER", "ACTIVE", 2L);
        partner.setSeatNo(partnerSeat);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(actor);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L, "ACTIVE", 8L));
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L))
                .thenReturn(List.of(actor, partner));

        assertBusinessError(
                () -> service.lockActiveHouseholdContext(7L),
                "DINNER_HOUSEHOLD_VERSION_CONFLICT");
    }

    @Test
    void failsClosedWhenLockedFamilyHasNoOwner() {
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        DinnerHouseholdMemberEntity actor =
                membership(41L, 11L, 7L, "MEMBER", "ACTIVE", 4L);
        actor.setSeatNo(1);
        DinnerHouseholdMemberEntity partner =
                membership(42L, 11L, 8L, "MEMBER", "ACTIVE", 2L);
        partner.setSeatNo(2);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(actor);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L, "ACTIVE", 8L));
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L))
                .thenReturn(List.of(actor, partner));

        assertBusinessError(
                () -> service.lockActiveHouseholdContext(7L),
                "DINNER_HOUSEHOLD_VERSION_CONFLICT");
    }

    @Test
    void failsClosedWhenLockedFamilyHasTwoOwners() {
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        DinnerHouseholdMemberEntity actor =
                membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        DinnerHouseholdMemberEntity partner =
                membership(42L, 11L, 8L, "OWNER", "ACTIVE", 2L);
        partner.setSeatNo(2);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(actor);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L, "ACTIVE", 8L));
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L))
                .thenReturn(List.of(actor, partner));

        assertBusinessError(
                () -> service.lockActiveHouseholdContext(7L),
                "DINNER_HOUSEHOLD_VERSION_CONFLICT");
    }

    @Test
    void failsClosedWhenLockedFamilyExceedsTwoActiveMembers() {
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        DinnerHouseholdMemberEntity actor =
                membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        DinnerHouseholdMemberEntity partner =
                membership(42L, 11L, 8L, "MEMBER", "ACTIVE", 2L);
        DinnerHouseholdMemberEntity unexpected =
                membership(43L, 11L, 9L, "MEMBER", "ACTIVE", 1L);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(actor);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L, "ACTIVE", 8L));
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L))
                .thenReturn(List.of(actor, partner, unexpected));

        assertBusinessError(
                () -> service.lockActiveHouseholdContext(7L),
                "DINNER_HOUSEHOLD_VERSION_CONFLICT");
    }

    @Test
    void failsClosedWhenLockedMembershipRowsAreNotInAscendingIdOrder() {
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        DinnerHouseholdMemberEntity actor =
                membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        DinnerHouseholdMemberEntity partner =
                membership(42L, 11L, 8L, "MEMBER", "ACTIVE", 2L);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(actor);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L, "ACTIVE", 8L));
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L))
                .thenReturn(List.of(partner, actor));

        assertBusinessError(
                () -> service.lockActiveHouseholdContext(7L),
                "DINNER_HOUSEHOLD_VERSION_CONFLICT");
    }

    private UserEntity activeUser(Long id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setStatus("ACTIVE");
        return user;
    }

    private DinnerHouseholdMemberEntity membership(
            Long id,
            Long householdId,
            Long userId,
            String role,
            String status,
            Long version
    ) {
        DinnerHouseholdMemberEntity membership = new DinnerHouseholdMemberEntity();
        membership.setId(id);
        membership.setHouseholdId(householdId);
        membership.setUserId(userId);
        membership.setRole(role);
        membership.setStatus(status);
        membership.setSeatNo("OWNER".equals(role) ? 1 : 2);
        membership.setVersion(version);
        membership.setHistoryVisibleFrom(JOINED_AT);
        membership.setJoinedAt(JOINED_AT);
        return membership;
    }

    private DinnerHouseholdEntity household(Long id, String status, Long version) {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(id);
        household.setName("我们的小家");
        household.setTimezone("Asia/Shanghai");
        household.setStatus(status);
        household.setVersion(version);
        return household;
    }

    private void assertBusinessError(Runnable call, String expectedCode) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode().name()).isEqualTo(expectedCode));
    }
}
