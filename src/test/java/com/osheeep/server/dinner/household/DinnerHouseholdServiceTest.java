package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerInviteCodeMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class DinnerHouseholdServiceTest {

    @Mock private DinnerHouseholdMapper householdMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerInviteCodeMapper inviteMapper;
    private final InviteCodeHasher inviteCodeHasher =
            new InviteCodeHasher("test-secret-at-least-32-characters");
    private final SecureRandom secureRandom = new SecureRandom() {
        @Override
        public int nextInt(int bound) {
            return 5268;
        }
    };

    private DinnerHouseholdService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-11T06:00:00Z"), ZoneOffset.UTC);
        service = new DinnerHouseholdService(
                householdMapper, memberMapper, inviteMapper, inviteCodeHasher, clock, secureRandom);
    }

    @Test
    void createAddsOwnerAndReturnsInviteValidForTwentyFourHours() {
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(householdMapper.insert(any(DinnerHouseholdEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerHouseholdEntity>getArgument(0).setId(11L);
            return 1;
        });

        var result = service.create(7L, "我们的小家");

        assertThat(result.household().id()).isEqualTo(11L);
        assertThat(result.inviteCode()).isEqualTo("DINNER 5268");
        assertThat(result.inviteExpiresAt()).isEqualTo(Instant.parse("2026-07-12T06:00:00Z"));
        verify(memberMapper).insert(any(DinnerHouseholdMemberEntity.class));
        verify(inviteMapper).insert(any(DinnerInviteCodeEntity.class));
    }

    @Test
    void createRejectsUserAlreadyInHousehold() {
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));

        assertThatThrownBy(() -> service.create(7L, "我们的小家"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.DINNER_ALREADY_IN_HOUSEHOLD));
    }

    @Test
    void joinAddsSecondMember() {
        DinnerInviteCodeEntity invite = invite(11L, Instant.parse("2026-07-12T06:00:00Z"));
        DinnerHouseholdEntity household = household(11L);
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(inviteMapper.selectByCodeHash(any())).thenReturn(invite);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household);
        when(inviteMapper.selectByIdAndHouseholdIdForUpdate(21L, 11L)).thenReturn(invite);
        when(memberMapper.selectCount(any())).thenReturn(1L);

        var result = service.join(8L, "dinner 5268");

        assertThat(result.id()).isEqualTo(11L);
        assertThat(result.memberCount()).isEqualTo(2);
        verify(memberMapper).insert(any(DinnerHouseholdMemberEntity.class));
    }

    @Test
    void joinRejectsInviteRevokedWhileWaitingForHouseholdLock() {
        DinnerInviteCodeEntity located = invite(11L, Instant.parse("2026-07-12T06:00:00Z"));
        DinnerInviteCodeEntity revoked = invite(11L, Instant.parse("2026-07-12T06:00:00Z"));
        revoked.setRevokedAt(Instant.parse("2026-07-11T05:59:00Z")
                .atOffset(ZoneOffset.UTC).toLocalDateTime());
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(inviteMapper.selectByCodeHash(any())).thenReturn(located);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L));
        when(inviteMapper.selectByIdAndHouseholdIdForUpdate(21L, 11L)).thenReturn(revoked);

        assertThatThrownBy(() -> service.join(8L, "DINNER 5268"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.DINNER_INVITE_INVALID));
        verify(memberMapper, never()).insert(any(DinnerHouseholdMemberEntity.class));
        InOrder order = inOrder(householdMapper, inviteMapper, memberMapper);
        order.verify(inviteMapper).selectByCodeHash(any());
        order.verify(householdMapper).selectByIdForUpdate(11L);
        order.verify(inviteMapper).selectByIdAndHouseholdIdForUpdate(21L, 11L);
    }

    @Test
    void joinRejectsFullHouseholdInsideLock() {
        DinnerInviteCodeEntity invite = invite(11L, Instant.parse("2026-07-12T06:00:00Z"));
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(inviteMapper.selectByCodeHash(any())).thenReturn(invite);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L));
        when(inviteMapper.selectByIdAndHouseholdIdForUpdate(21L, 11L)).thenReturn(invite);
        when(memberMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.join(8L, "DINNER5268"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.DINNER_HOUSEHOLD_FULL));
    }

    @Test
    void joinMapsConcurrentMembershipConflict() {
        DinnerInviteCodeEntity invite = invite(11L, Instant.parse("2026-07-12T06:00:00Z"));
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(inviteMapper.selectByCodeHash(any())).thenReturn(invite);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L));
        when(inviteMapper.selectByIdAndHouseholdIdForUpdate(21L, 11L)).thenReturn(invite);
        when(memberMapper.selectCount(any())).thenReturn(1L);
        when(memberMapper.insert(any(DinnerHouseholdMemberEntity.class)))
                .thenThrow(new DuplicateKeyException("membership conflict"));

        assertThatThrownBy(() -> service.join(8L, "DINNER 5268"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.DINNER_ALREADY_IN_HOUSEHOLD));
    }

    @Test
    void currentReturnsHouseholdSummary() {
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(householdMapper.selectById(11L)).thenReturn(household(11L));
        when(memberMapper.selectCount(any())).thenReturn(2L);

        assertThat(service.current(7L)).isEqualTo(
                new com.osheeep.server.dinner.household.dto.HouseholdResponse(
                        11L, "我们的小家", "Asia/Shanghai", 2));
    }

    @Test
    void joinRejectsUnknownInvite() {
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(inviteMapper.selectByCodeHash(any())).thenReturn(null);

        assertThatThrownBy(() -> service.join(8L, "DINNER 0000"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.DINNER_INVITE_INVALID));
        verify(memberMapper, never()).insert(any(DinnerHouseholdMemberEntity.class));
    }

    @Test
    void joinRejectsExpiredInvite() {
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(inviteMapper.selectByCodeHash(any())).thenReturn(
                invite(11L, Instant.parse("2026-07-11T06:00:00Z")));

        assertThatThrownBy(() -> service.join(8L, "DINNER 5268"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.DINNER_INVITE_EXPIRED));
        verify(householdMapper, never()).selectByIdForUpdate(any());
    }

    @Test
    void refreshRevokesActiveInviteAndReturnsReplacement() {
        DinnerInviteCodeEntity activeInvite = invite(11L, Instant.parse("2026-07-12T06:00:00Z"));
        activeInvite.setId(21L);
        when(memberMapper.selectByUserIdForUpdate(7L)).thenReturn(member(11L, 7L));
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L));
        when(inviteMapper.selectActiveByHouseholdIdForUpdate(any(), any())).thenReturn(activeInvite);

        var result = service.refreshInvite(7L);

        assertThat(result.inviteCode()).isEqualTo("DINNER 5268");
        assertThat(activeInvite.getRevokedAt()).isNotNull();
        verify(inviteMapper).updateById(activeInvite);
        InOrder order = inOrder(memberMapper, householdMapper, inviteMapper);
        order.verify(memberMapper).selectByUserIdForUpdate(7L);
        order.verify(householdMapper).selectByIdForUpdate(11L);
        order.verify(inviteMapper).selectActiveByHouseholdIdForUpdate(any(), any());
        order.verify(inviteMapper).updateById(activeInvite);
        order.verify(inviteMapper).insert(any(DinnerInviteCodeEntity.class));
    }

    private DinnerHouseholdMemberEntity member(Long householdId, Long userId) {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setHouseholdId(householdId);
        member.setUserId(userId);
        return member;
    }

    private DinnerHouseholdEntity household(Long id) {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(id);
        household.setName("我们的小家");
        household.setTimezone("Asia/Shanghai");
        household.setStatus("ACTIVE");
        return household;
    }

    private DinnerInviteCodeEntity invite(Long householdId, Instant expiresAt) {
        DinnerInviteCodeEntity invite = new DinnerInviteCodeEntity();
        invite.setId(21L);
        invite.setHouseholdId(householdId);
        invite.setCodeHash(inviteCodeHasher.hash("DINNER5268"));
        invite.setExpiresAt(expiresAt.atOffset(ZoneOffset.UTC).toLocalDateTime());
        return invite;
    }
}
