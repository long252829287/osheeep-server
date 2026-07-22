package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdSnapshot;
import com.osheeep.server.dinner.household.dto.HouseholdCreatedResponse;
import com.osheeep.server.dinner.household.dto.HouseholdInviteStatusResponse;
import com.osheeep.server.dinner.household.dto.HouseholdResponse;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerInviteCodeMapper;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class DinnerHouseholdServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 22, 5, 0);

    @Mock private DinnerHouseholdAccessService accessService;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerInviteCodeMapper inviteMapper;
    @Mock private DinnerHouseholdNameService nameService;
    @Mock private DinnerHouseholdWriteService writeService;

    private DinnerHouseholdService service;

    @BeforeEach
    void setUp() {
        service = new DinnerHouseholdService(
                accessService,
                memberMapper,
                inviteMapper,
                nameService,
                writeService,
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    @Test
    void currentReturnsNullForOnboardingWhenNoActiveHouseholdExists() {
        when(accessService.findActiveSnapshot(7L)).thenReturn(null);

        assertThat(service.current(7L)).isNull();

        verifyNoInteractions(memberMapper, inviteMapper);
    }

    @Test
    void currentIncludesRoleAndVersionContextFromOneReadSnapshot() {
        DinnerHouseholdEntity household = household();
        DinnerHouseholdMemberEntity actor = member(31L, 7L, "OWNER", 1);
        DinnerHouseholdMemberEntity partner = member(32L, 8L, "MEMBER", 2);
        when(accessService.findActiveSnapshot(7L))
                .thenReturn(new ActiveHouseholdSnapshot(actor, household));
        when(memberMapper.selectActiveByHouseholdId(11L)).thenReturn(List.of(actor, partner));
        when(inviteMapper.selectOpenByHouseholdId(11L)).thenReturn(null);

        HouseholdResponse result = service.current(7L);

        assertThat(result.id()).isEqualTo(11L);
        assertThat(result.version()).isEqualTo(7L);
        assertThat(result.inviteRevision()).isEqualTo(4L);
        assertThat(result.myRole()).isEqualTo("OWNER");
        assertThat(result.myMembershipId()).isEqualTo(31L);
        assertThat(result.myMembershipVersion()).isEqualTo(3L);
        assertThat(result.memberCount()).isEqualTo(2);
        assertThat(result.toString())
                .isEqualTo("HouseholdResponse[redacted]")
                .doesNotContain("我们的小家");
    }

    @Test
    void managementLabelsOnlyMeAndPartnerWithoutUserProfileFields() {
        DinnerHouseholdEntity household = household();
        DinnerHouseholdMemberEntity actor = member(31L, 7L, "MEMBER", 2);
        DinnerHouseholdMemberEntity partner = member(30L, 8L, "OWNER", 1);
        DinnerInviteCodeEntity invite = invite(NOW.plusHours(2), 8L);
        when(accessService.findActiveSnapshot(7L))
                .thenReturn(new ActiveHouseholdSnapshot(actor, household));
        when(memberMapper.selectActiveByHouseholdId(11L)).thenReturn(List.of(partner, actor));
        when(inviteMapper.selectOpenByHouseholdId(11L)).thenReturn(invite);

        var result = service.management(7L);

        assertThat(result.members())
                .extracting(member -> member.relation())
                .containsExactly("PARTNER", "ME");
        assertThat(result.members())
                .extracting(member -> member.membershipId())
                .containsExactly(30L, 31L);
        assertThat(result.invite().state()).isEqualTo("ACTIVE");
        assertThat(result.invite().inviteRevision()).isEqualTo(4L);
        assertThat(result.invite().createdByMe()).isFalse();
        assertThat(result.invite().expiresAt()).isEqualTo(Instant.parse("2026-07-22T07:00:00Z"));
    }

    @Test
    void managementReportsExpiredHashOnlyInviteWithoutPlaintext() {
        DinnerHouseholdEntity household = household();
        DinnerHouseholdMemberEntity actor = member(31L, 7L, "OWNER", 1);
        when(accessService.findActiveSnapshot(7L))
                .thenReturn(new ActiveHouseholdSnapshot(actor, household));
        when(memberMapper.selectActiveByHouseholdId(11L)).thenReturn(List.of(actor));
        when(inviteMapper.selectOpenByHouseholdId(11L)).thenReturn(invite(NOW, 7L));

        HouseholdInviteStatusResponse result = service.inviteStatus(7L);

        assertThat(result.state()).isEqualTo("EXPIRED");
        assertThat(result.createdByMe()).isTrue();
        assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-07-22T05:00:00Z"));
    }

    @Test
    void managementRequiresActiveHouseholdInsteadOfReturningNull() {
        when(accessService.findActiveSnapshot(7L)).thenReturn(null);

        assertBusinessError(
                () -> service.management(7L),
                ErrorCode.DINNER_HOUSEHOLD_REQUIRED);
    }

    @Test
    void managementRejectsSnapshotWithoutExactlyOneOwner() {
        DinnerHouseholdEntity household = household();
        DinnerHouseholdMemberEntity actor = member(31L, 7L, "MEMBER", 1);
        when(accessService.findActiveSnapshot(7L))
                .thenReturn(new ActiveHouseholdSnapshot(actor, household));
        when(memberMapper.selectActiveByHouseholdId(11L)).thenReturn(List.of(actor));

        assertBusinessError(
                () -> service.management(7L),
                ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);
    }

    @Test
    void managementRejectsDuplicateActiveSeats() {
        DinnerHouseholdEntity household = household();
        DinnerHouseholdMemberEntity actor = member(31L, 7L, "OWNER", 1);
        DinnerHouseholdMemberEntity partner = member(32L, 8L, "MEMBER", 1);
        when(accessService.findActiveSnapshot(7L))
                .thenReturn(new ActiveHouseholdSnapshot(actor, household));
        when(memberMapper.selectActiveByHouseholdId(11L)).thenReturn(List.of(actor, partner));

        assertBusinessError(
                () -> service.management(7L),
                ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);
    }

    @Test
    void managementRejectsUnknownMemberRole() {
        DinnerHouseholdEntity household = household();
        DinnerHouseholdMemberEntity actor = member(31L, 7L, "OWNER", 1);
        DinnerHouseholdMemberEntity partner = member(32L, 8L, "ADMIN", 2);
        when(accessService.findActiveSnapshot(7L))
                .thenReturn(new ActiveHouseholdSnapshot(actor, household));
        when(memberMapper.selectActiveByHouseholdId(11L)).thenReturn(List.of(actor, partner));

        assertBusinessError(
                () -> service.management(7L),
                ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);
    }

    @Test
    void createPrecheckAvoidsModerationForAlreadyBoundUser() {
        when(accessService.findActiveMembership(7L)).thenReturn(member(31L, 7L, "OWNER", 1));

        assertBusinessError(
                () -> service.create(7L, "新名字"),
                ErrorCode.DINNER_ALREADY_IN_HOUSEHOLD);

        verifyNoInteractions(nameService, writeService);
    }

    @Test
    void createModeratesBeforeEnteringTransactionalWriter() {
        HouseholdCreatedResponse expected = createdResponse();
        when(nameService.prepareForCreate(7L, "  e\u0301家 ")).thenReturn("é家");
        when(writeService.create(7L, "é家")).thenReturn(expected);

        assertThat(service.create(7L, "  e\u0301家 ")).isSameAs(expected);

        InOrder order = inOrder(accessService, nameService, writeService);
        order.verify(accessService).findActiveMembership(7L);
        order.verify(nameService).prepareForCreate(7L, "  e\u0301家 ");
        order.verify(writeService).create(7L, "é家");
    }

    @Test
    void renamePrechecksMembershipThenModeratesBeforeTransactionalWriter() {
        when(accessService.requireActiveHousehold(7L)).thenReturn(anyAccess());
        when(nameService.moderate(7L, "新名字")).thenReturn("新名字");
        HouseholdResponse expected = householdResponse("新名字", 8L);
        when(writeService.rename(7L, "新名字", 7L)).thenReturn(expected);

        assertThat(service.rename(7L, "新名字", 7L)).isSameAs(expected);

        InOrder order = inOrder(accessService, nameService, writeService);
        order.verify(accessService).requireActiveHousehold(7L);
        order.verify(nameService).moderate(7L, "新名字");
        order.verify(writeService).rename(7L, "新名字", 7L);
    }

    @Test
    void rejectedRenameNeverEntersTransactionalWriter() {
        when(accessService.requireActiveHousehold(7L)).thenReturn(anyAccess());
        when(nameService.moderate(7L, "被拒绝"))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_NAME_REJECTED));

        assertBusinessError(
                () -> service.rename(7L, "被拒绝", 7L),
                ErrorCode.DINNER_HOUSEHOLD_NAME_REJECTED);

        verify(writeService, never()).rename(any(), any(), any());
    }

    @Test
    void invalidInviteSyntaxMapsToStableBusinessErrorBeforeWriter() {
        assertBusinessError(
                () -> service.join(7L, "DINNER 0123 45O7"),
                ErrorCode.DINNER_INVITE_INVALID);

        verifyNoInteractions(writeService);
    }

    @Test
    void joinDoesNotMaskAnInternalWriterIllegalArgumentException() {
        IllegalArgumentException internal = new IllegalArgumentException("internal invariant");
        when(writeService.join(7L, "DINNER 0123 4567")).thenThrow(internal);

        assertThatThrownBy(() -> service.join(7L, "DINNER 0123 4567"))
                .isSameAs(internal);
    }

    @Test
    void readMethodsDeclareRepeatableReadOnlySnapshots() throws Exception {
        for (String methodName : List.of("current", "management", "inviteStatus")) {
            Method method = DinnerHouseholdService.class.getMethod(methodName, Long.class);
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional).isNotNull();
            assertThat(transactional.readOnly()).isTrue();
            assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        }
    }

    private DinnerHouseholdEntity household() {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(11L);
        household.setName("我们的小家");
        household.setTimezone("Asia/Shanghai");
        household.setStatus("ACTIVE");
        household.setVersion(7L);
        household.setInviteRevision(4L);
        return household;
    }

    private DinnerHouseholdMemberEntity member(
            Long id,
            Long userId,
            String role,
            int seatNo
    ) {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setId(id);
        member.setHouseholdId(11L);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus("ACTIVE");
        member.setSeatNo(seatNo);
        member.setVersion(3L);
        member.setJoinedAt(NOW.minusDays(1));
        member.setHistoryVisibleFrom(NOW.minusDays(1));
        return member;
    }

    private DinnerInviteCodeEntity invite(LocalDateTime expiresAt, Long createdBy) {
        DinnerInviteCodeEntity invite = new DinnerInviteCodeEntity();
        invite.setId(21L);
        invite.setHouseholdId(11L);
        invite.setCodeHash("a".repeat(64));
        invite.setExpiresAt(expiresAt);
        invite.setCreatedBy(createdBy);
        return invite;
    }

    private DinnerHouseholdAccessService.ActiveHouseholdAccess anyAccess() {
        return new DinnerHouseholdAccessService.ActiveHouseholdAccess(
                7L, 11L, 31L, 3L, "OWNER", NOW.minusDays(1), 7L, "Asia/Shanghai");
    }

    private HouseholdCreatedResponse createdResponse() {
        return new HouseholdCreatedResponse(
                householdResponse("é家", 1L),
                "DINNER 0123 4567",
                1L,
                Instant.parse("2026-07-23T05:00:00Z"));
    }

    private HouseholdResponse householdResponse(String name, Long version) {
        return new HouseholdResponse(
                11L, name, "Asia/Shanghai", 1, version, 1L,
                "OWNER", 31L, 1L);
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
