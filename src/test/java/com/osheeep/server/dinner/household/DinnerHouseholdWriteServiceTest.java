package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.LockedHouseholdContext;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerInviteCodeMapper;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class DinnerHouseholdWriteServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 22, 5, 0);

    @Mock private UserMapper userMapper;
    @Mock private DinnerHouseholdMapper householdMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerInviteCodeMapper inviteMapper;
    @Mock private DinnerHouseholdAccessService accessService;
    @Mock private DinnerHouseholdNameService nameService;
    @Mock private DinnerHouseholdDraftLifecycleService draftLifecycleService;
    @Mock private InviteCodeGenerator inviteCodeGenerator;

    private final InviteCodeHasher inviteCodeHasher =
            new InviteCodeHasher("test-secret-at-least-32-characters");
    private DinnerHouseholdWriteService service;

    @BeforeEach
    void setUp() {
        service = new DinnerHouseholdWriteService(
                userMapper,
                householdMapper,
                memberMapper,
                inviteMapper,
                accessService,
                nameService,
                draftLifecycleService,
                inviteCodeGenerator,
                inviteCodeHasher,
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    @Test
    void createLocksActorAndPersistsOwnerInviteAndDraftBindingInOrder() {
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        when(nameService.normalize("小羊的家")).thenReturn("小羊的家");
        when(householdMapper.insert(any(DinnerHouseholdEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerHouseholdEntity>getArgument(0).setId(11L);
            return 1;
        });
        when(memberMapper.insert(any(DinnerHouseholdMemberEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerHouseholdMemberEntity>getArgument(0).setId(31L);
            return 1;
        });
        when(inviteCodeGenerator.generate()).thenReturn("DINNER 0123 4567");
        when(inviteMapper.insert(any(DinnerInviteCodeEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerInviteCodeEntity>getArgument(0).setId(21L);
            return 1;
        });

        var result = service.create(7L, "小羊的家");

        ArgumentCaptor<DinnerHouseholdEntity> householdCaptor =
                ArgumentCaptor.forClass(DinnerHouseholdEntity.class);
        ArgumentCaptor<DinnerHouseholdMemberEntity> memberCaptor =
                ArgumentCaptor.forClass(DinnerHouseholdMemberEntity.class);
        ArgumentCaptor<DinnerInviteCodeEntity> inviteCaptor =
                ArgumentCaptor.forClass(DinnerInviteCodeEntity.class);
        verify(householdMapper).insert(householdCaptor.capture());
        verify(memberMapper).insert(memberCaptor.capture());
        verify(inviteMapper).insert(inviteCaptor.capture());

        DinnerHouseholdEntity household = householdCaptor.getValue();
        assertThat(household.getVersion()).isEqualTo(1L);
        assertThat(household.getInviteRevision()).isEqualTo(1L);
        assertThat(household.getName()).isEqualTo("小羊的家");
        assertThat(household.getTimezone()).isEqualTo("Asia/Shanghai");

        DinnerHouseholdMemberEntity owner = memberCaptor.getValue();
        assertThat(owner.getRole()).isEqualTo("OWNER");
        assertThat(owner.getStatus()).isEqualTo("ACTIVE");
        assertThat(owner.getSeatNo()).isEqualTo(1);
        assertThat(owner.getVersion()).isEqualTo(1L);
        assertThat(owner.getJoinedAt()).isEqualTo(NOW);
        assertThat(owner.getHistoryVisibleFrom()).isEqualTo(NOW);

        DinnerInviteCodeEntity invite = inviteCaptor.getValue();
        assertThat(invite.getCodeHash())
                .isEqualTo(inviteCodeHasher.hash("DINNER 0123 4567"))
                .doesNotContain("DINNER", "0123", "4567");
        assertThat(invite.getExpiresAt()).isEqualTo(NOW.plusHours(24));
        assertThat(result.inviteExpiresAt()).isEqualTo(Instant.parse("2026-07-23T05:00:00Z"));
        assertThat(result.inviteRevision()).isEqualTo(1L);
        assertThat(result.household().myRole()).isEqualTo("OWNER");
        assertThat(result.household().myMembershipId()).isEqualTo(31L);
        assertThat(result.household().memberCount()).isEqualTo(1);

        InOrder order = inOrder(
                userMapper, memberMapper, householdMapper, inviteMapper, draftLifecycleService);
        order.verify(userMapper).selectByIdForUpdate(7L);
        order.verify(memberMapper).selectActiveByUserId(7L);
        order.verify(householdMapper).insert(any(DinnerHouseholdEntity.class));
        order.verify(memberMapper).insert(any(DinnerHouseholdMemberEntity.class));
        order.verify(inviteMapper).insert(any(DinnerInviteCodeEntity.class));
        order.verify(draftLifecycleService).rebindUnboundDrafts(7L, 11L);
    }

    @Test
    void createRejectsInactiveActorBeforeAnyHouseholdWrite() {
        UserEntity inactive = activeUser(7L);
        inactive.setStatus("DELETED");
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(inactive);

        assertBusinessError(() -> service.create(7L, "我们的小家"), ErrorCode.UNAUTHORIZED);

        verifyNoInteractions(householdMapper, inviteMapper, draftLifecycleService);
        verify(memberMapper, never()).insert(any(DinnerHouseholdMemberEntity.class));
    }

    @Test
    void createReturnsTheSameMillisecondExpiryThatCanBePersisted() {
        service = new DinnerHouseholdWriteService(
                userMapper,
                householdMapper,
                memberMapper,
                inviteMapper,
                accessService,
                nameService,
                draftLifecycleService,
                inviteCodeGenerator,
                inviteCodeHasher,
                Clock.fixed(
                        Instant.parse("2026-07-22T05:00:00.987654321Z"),
                        ZoneOffset.UTC));
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        when(nameService.normalize("小羊的家")).thenReturn("小羊的家");
        when(householdMapper.insert(any(DinnerHouseholdEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerHouseholdEntity>getArgument(0).setId(11L);
            return 1;
        });
        when(memberMapper.insert(any(DinnerHouseholdMemberEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerHouseholdMemberEntity>getArgument(0).setId(31L);
            return 1;
        });
        when(inviteCodeGenerator.generate()).thenReturn("DINNER 0123 4567");
        when(inviteMapper.insert(any(DinnerInviteCodeEntity.class))).thenReturn(1);

        var result = service.create(7L, "小羊的家");

        ArgumentCaptor<DinnerInviteCodeEntity> inviteCaptor =
                ArgumentCaptor.forClass(DinnerInviteCodeEntity.class);
        verify(inviteMapper).insert(inviteCaptor.capture());
        assertThat(inviteCaptor.getValue().getExpiresAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 23, 5, 0, 0, 987_000_000));
        assertThat(result.inviteExpiresAt())
                .isEqualTo(Instant.parse("2026-07-23T05:00:00.987Z"));
    }

    @Test
    void createMapsOnlyKnownActiveUserConstraintAndNeverCreatesInviteAfterConflict() {
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        when(nameService.normalize(any())).thenReturn("我们的小家");
        when(householdMapper.insert(any(DinnerHouseholdEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerHouseholdEntity>getArgument(0).setId(11L);
            return 1;
        });
        when(memberMapper.insert(any(DinnerHouseholdMemberEntity.class))).thenThrow(
                new DuplicateKeyException("uk_dinner_household_members_active_user"));

        assertBusinessError(
                () -> service.create(7L, "我们的小家"),
                ErrorCode.DINNER_ALREADY_IN_HOUSEHOLD);

        verifyNoInteractions(inviteMapper, draftLifecycleService);
    }

    @Test
    void createDoesNotMaskUnknownDuplicateConstraint() {
        DuplicateKeyException unknown = new DuplicateKeyException("uk_unrelated_table");
        when(userMapper.selectByIdForUpdate(7L)).thenReturn(activeUser(7L));
        when(nameService.normalize(any())).thenReturn("我们的小家");
        when(householdMapper.insert(any(DinnerHouseholdEntity.class))).thenThrow(unknown);

        assertThatThrownBy(() -> service.create(7L, "我们的小家")).isSameAs(unknown);
    }

    @Test
    void joinConsumesInviteAllocatesFreeSeatAndAdvancesBothRevisions() {
        DinnerHouseholdEntity household = household(11L, 7L, 4L);
        DinnerHouseholdMemberEntity owner = member(31L, 11L, 7L, "OWNER", 1);
        DinnerInviteCodeEntity invite = invite(21L, 11L, NOW.plusHours(1));
        when(userMapper.selectByIdForUpdate(8L)).thenReturn(activeUser(8L));
        when(inviteMapper.selectByCodeHash(any())).thenReturn(invite);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household);
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L)).thenReturn(List.of(owner));
        when(inviteMapper.selectByIdAndHouseholdIdForUpdate(21L, 11L)).thenReturn(invite);
        when(inviteMapper.consumeOpenInvite(21L, 11L, NOW, 8L)).thenReturn(1);
        when(memberMapper.insert(any(DinnerHouseholdMemberEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerHouseholdMemberEntity>getArgument(0).setId(32L);
            return 1;
        });
        when(householdMapper.advanceMembershipAndInviteRevision(11L, 7L, 4L)).thenReturn(1);

        var result = service.join(8L, "DINNER 0123 4567");

        ArgumentCaptor<DinnerHouseholdMemberEntity> memberCaptor =
                ArgumentCaptor.forClass(DinnerHouseholdMemberEntity.class);
        verify(memberMapper).insert(memberCaptor.capture());
        DinnerHouseholdMemberEntity joined = memberCaptor.getValue();
        assertThat(joined.getRole()).isEqualTo("MEMBER");
        assertThat(joined.getSeatNo()).isEqualTo(2);
        assertThat(joined.getVersion()).isEqualTo(1L);
        assertThat(joined.getJoinedAt()).isEqualTo(NOW);
        assertThat(joined.getHistoryVisibleFrom()).isEqualTo(NOW);
        assertThat(result.version()).isEqualTo(8L);
        assertThat(result.inviteRevision()).isEqualTo(5L);
        assertThat(result.myRole()).isEqualTo("MEMBER");
        assertThat(result.memberCount()).isEqualTo(2);

        InOrder order = inOrder(
                userMapper, memberMapper, inviteMapper, householdMapper, draftLifecycleService);
        order.verify(userMapper).selectByIdForUpdate(8L);
        order.verify(memberMapper).selectActiveByUserId(8L);
        order.verify(inviteMapper).selectByCodeHash(any());
        order.verify(householdMapper).selectByIdForUpdate(11L);
        order.verify(memberMapper).selectActiveByHouseholdIdForUpdate(11L);
        order.verify(inviteMapper).selectByIdAndHouseholdIdForUpdate(21L, 11L);
        order.verify(inviteMapper).consumeOpenInvite(21L, 11L, NOW, 8L);
        order.verify(memberMapper).insert(any(DinnerHouseholdMemberEntity.class));
        order.verify(householdMapper).advanceMembershipAndInviteRevision(11L, 7L, 4L);
        order.verify(draftLifecycleService).rebindUnboundDrafts(8L, 11L);
    }

    @Test
    void joinUsesSeatOneWhenTheRemainingOwnerOccupiesSeatTwo() {
        stubJoinableHousehold(member(31L, 11L, 7L, "OWNER", 2));

        service.join(8L, "DINNER 0123 4567");

        ArgumentCaptor<DinnerHouseholdMemberEntity> memberCaptor =
                ArgumentCaptor.forClass(DinnerHouseholdMemberEntity.class);
        verify(memberMapper).insert(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getSeatNo()).isEqualTo(1);
    }

    @Test
    void joinRejectsFullHouseholdBeforeLockingOrConsumingInvite() {
        DinnerInviteCodeEntity invite = invite(21L, 11L, NOW.plusHours(1));
        when(userMapper.selectByIdForUpdate(8L)).thenReturn(activeUser(8L));
        when(inviteMapper.selectByCodeHash(any())).thenReturn(invite);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L, 7L, 4L));
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L)).thenReturn(List.of(
                member(31L, 11L, 7L, "OWNER", 1),
                member(32L, 11L, 9L, "MEMBER", 2)));

        assertBusinessError(
                () -> service.join(8L, "DINNER 0123 4567"),
                ErrorCode.DINNER_HOUSEHOLD_FULL);

        verify(inviteMapper, never()).selectByIdAndHouseholdIdForUpdate(anyLong(), anyLong());
        verify(inviteMapper, never()).consumeOpenInvite(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void joinRechecksExpiryAfterTakingHouseholdAndInviteLocks() {
        DinnerInviteCodeEntity candidate = invite(21L, 11L, NOW.plusHours(1));
        DinnerInviteCodeEntity expired = invite(21L, 11L, NOW);
        when(userMapper.selectByIdForUpdate(8L)).thenReturn(activeUser(8L));
        when(inviteMapper.selectByCodeHash(any())).thenReturn(candidate);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L, 7L, 4L));
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L)).thenReturn(
                List.of(member(31L, 11L, 7L, "OWNER", 1)));
        when(inviteMapper.selectByIdAndHouseholdIdForUpdate(21L, 11L)).thenReturn(expired);

        assertBusinessError(
                () -> service.join(8L, "DINNER 0123 4567"),
                ErrorCode.DINNER_INVITE_EXPIRED);

        verify(memberMapper, never()).insert(any(DinnerHouseholdMemberEntity.class));
    }

    @Test
    void joinMapsActorLockTimeoutToRecoverableHouseholdConflict() {
        when(userMapper.selectByIdForUpdate(8L))
                .thenThrow(new CannotAcquireLockException("timeout"));

        assertBusinessError(
                () -> service.join(8L, "DINNER 0123 4567"),
                ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);
    }

    @Test
    void renameChecksExpectedVersionAndChangesOnlyHouseholdVersion() {
        DinnerHouseholdEntity household = household(11L, 7L, 4L);
        DinnerHouseholdMemberEntity actor = member(31L, 11L, 7L, "MEMBER", 2);
        when(nameService.normalize("新名字")).thenReturn("新名字");
        when(accessService.lockActiveHouseholdContext(7L))
                .thenReturn(context(activeUser(7L), household, List.of(
                        member(30L, 11L, 8L, "OWNER", 1), actor), actor));
        when(householdMapper.renameActiveHousehold(11L, 7L, "新名字")).thenReturn(1);

        var result = service.rename(7L, "新名字", 7L);

        assertThat(result.name()).isEqualTo("新名字");
        assertThat(result.version()).isEqualTo(8L);
        assertThat(result.inviteRevision()).isEqualTo(4L);
        assertThat(result.myRole()).isEqualTo("MEMBER");
    }

    @Test
    void renameRejectsStaleVersionWithoutUpdatingHousehold() {
        DinnerHouseholdEntity household = household(11L, 8L, 4L);
        DinnerHouseholdMemberEntity actor = member(31L, 11L, 7L, "OWNER", 1);
        when(nameService.normalize(any())).thenReturn("新名字");
        when(accessService.lockActiveHouseholdContext(7L))
                .thenReturn(context(activeUser(7L), household, List.of(actor), actor));

        assertBusinessError(
                () -> service.rename(7L, "新名字", 7L),
                ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);

        verify(householdMapper, never()).renameActiveHousehold(anyLong(), anyLong(), any());
    }

    @Test
    void refreshRevokesExpiredOpenInviteAndAdvancesInviteRevisionOnlyOnce() {
        DinnerHouseholdEntity household = household(11L, 7L, 4L);
        DinnerHouseholdMemberEntity actor = member(31L, 11L, 7L, "MEMBER", 1);
        DinnerInviteCodeEntity expired = invite(21L, 11L, NOW.minusMinutes(1));
        when(accessService.lockActiveHouseholdContext(7L))
                .thenReturn(context(activeUser(7L), household, List.of(actor), actor));
        when(inviteMapper.selectAllOpenByHouseholdIdForUpdate(11L))
                .thenReturn(List.of(expired));
        when(inviteMapper.revokeOpenInvite(21L, 11L, NOW, "REFRESHED")).thenReturn(1);
        when(inviteCodeGenerator.generate()).thenReturn("DINNER 0123 4567");
        when(inviteMapper.insert(any(DinnerInviteCodeEntity.class))).thenReturn(1);
        when(householdMapper.advanceInviteRevision(11L, 7L, 4L)).thenReturn(1);

        var result = service.refreshInvite(7L);

        assertThat(result.household().version()).isEqualTo(7L);
        assertThat(result.household().inviteRevision()).isEqualTo(5L);
        assertThat(result.inviteRevision()).isEqualTo(5L);
        assertThat(result.household().memberCount()).isEqualTo(1);
        verify(householdMapper).advanceInviteRevision(11L, 7L, 4L);
    }

    @Test
    void refreshRejectsFullHouseholdWithoutTouchingInvites() {
        DinnerHouseholdEntity household = household(11L, 7L, 4L);
        DinnerHouseholdMemberEntity actor = member(31L, 11L, 7L, "OWNER", 1);
        when(accessService.lockActiveHouseholdContext(7L)).thenReturn(context(
                activeUser(7L),
                household,
                List.of(actor, member(32L, 11L, 8L, "MEMBER", 2)),
                actor));

        assertBusinessError(
                () -> service.refreshInvite(7L),
                ErrorCode.DINNER_HOUSEHOLD_FULL);

        verifyNoInteractions(inviteMapper, inviteCodeGenerator);
    }

    @Test
    void revokeIsNaturallyIdempotentWhenNoOpenInviteExists() {
        DinnerHouseholdEntity household = household(11L, 7L, 4L);
        DinnerHouseholdMemberEntity actor = member(31L, 11L, 7L, "OWNER", 1);
        when(accessService.lockActiveHouseholdContext(7L))
                .thenReturn(context(activeUser(7L), household, List.of(actor), actor));
        when(inviteMapper.selectAllOpenByHouseholdIdForUpdate(11L)).thenReturn(List.of());

        var result = service.revokeInvite(7L);

        assertThat(result.state()).isEqualTo("NONE");
        assertThat(result.inviteRevision()).isEqualTo(4L);
        verify(householdMapper, never()).advanceInviteRevision(anyLong(), anyLong(), anyLong());
    }

    @Test
    void revokeMarksEveryOpenInviteAndAdvancesRevisionOnce() {
        DinnerHouseholdEntity household = household(11L, 7L, 4L);
        DinnerHouseholdMemberEntity actor = member(31L, 11L, 7L, "MEMBER", 1);
        DinnerInviteCodeEntity open = invite(21L, 11L, NOW.minusHours(1));
        when(accessService.lockActiveHouseholdContext(7L))
                .thenReturn(context(activeUser(7L), household, List.of(actor), actor));
        when(inviteMapper.selectAllOpenByHouseholdIdForUpdate(11L)).thenReturn(List.of(open));
        when(inviteMapper.revokeOpenInvite(21L, 11L, NOW, "MEMBER_REVOKED")).thenReturn(1);
        when(householdMapper.advanceInviteRevision(11L, 7L, 4L)).thenReturn(1);

        var result = service.revokeInvite(7L);

        assertThat(result.inviteRevision()).isEqualTo(5L);
        verify(inviteMapper).revokeOpenInvite(21L, 11L, NOW, "MEMBER_REVOKED");
        verify(householdMapper).advanceInviteRevision(11L, 7L, 4L);
    }

    private void stubJoinableHousehold(DinnerHouseholdMemberEntity owner) {
        DinnerInviteCodeEntity invite = invite(21L, 11L, NOW.plusHours(1));
        when(userMapper.selectByIdForUpdate(8L)).thenReturn(activeUser(8L));
        when(inviteMapper.selectByCodeHash(any())).thenReturn(invite);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L, 7L, 4L));
        when(memberMapper.selectActiveByHouseholdIdForUpdate(11L)).thenReturn(List.of(owner));
        when(inviteMapper.selectByIdAndHouseholdIdForUpdate(21L, 11L)).thenReturn(invite);
        when(inviteMapper.consumeOpenInvite(21L, 11L, NOW, 8L)).thenReturn(1);
        when(memberMapper.insert(any(DinnerHouseholdMemberEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerHouseholdMemberEntity>getArgument(0).setId(32L);
            return 1;
        });
        when(householdMapper.advanceMembershipAndInviteRevision(11L, 7L, 4L)).thenReturn(1);
    }

    private UserEntity activeUser(Long id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setStatus("ACTIVE");
        return user;
    }

    private DinnerHouseholdEntity household(Long id, Long version, Long inviteRevision) {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(id);
        household.setName("我们的小家");
        household.setTimezone("Asia/Shanghai");
        household.setStatus("ACTIVE");
        household.setVersion(version);
        household.setInviteRevision(inviteRevision);
        return household;
    }

    private DinnerHouseholdMemberEntity member(
            Long id,
            Long householdId,
            Long userId,
            String role,
            int seatNo
    ) {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setId(id);
        member.setHouseholdId(householdId);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus("ACTIVE");
        member.setSeatNo(seatNo);
        member.setVersion(1L);
        member.setJoinedAt(NOW.minusDays(1));
        member.setHistoryVisibleFrom(NOW.minusDays(1));
        return member;
    }

    private DinnerInviteCodeEntity invite(Long id, Long householdId, LocalDateTime expiresAt) {
        DinnerInviteCodeEntity invite = new DinnerInviteCodeEntity();
        invite.setId(id);
        invite.setHouseholdId(householdId);
        invite.setCodeHash(inviteCodeHasher.hash("DINNER 0123 4567"));
        invite.setExpiresAt(expiresAt);
        invite.setCreatedBy(7L);
        return invite;
    }

    private LockedHouseholdContext context(
            UserEntity actor,
            DinnerHouseholdEntity household,
            List<DinnerHouseholdMemberEntity> members,
            DinnerHouseholdMemberEntity actorMembership
    ) {
        return new LockedHouseholdContext(
                actor,
                household,
                members,
                new ActiveHouseholdAccess(
                        actor.getId(),
                        household.getId(),
                        actorMembership.getId(),
                        actorMembership.getVersion(),
                        actorMembership.getRole(),
                        actorMembership.getHistoryVisibleFrom(),
                        household.getVersion(),
                        household.getTimezone()));
    }

    private void assertBusinessError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(errorCode));
    }
}
