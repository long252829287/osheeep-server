package com.osheeep.server.dinner.household;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdSnapshot;
import com.osheeep.server.dinner.household.dto.HouseholdCreatedResponse;
import com.osheeep.server.dinner.household.dto.HouseholdInviteStatusResponse;
import com.osheeep.server.dinner.household.dto.HouseholdManagementResponse;
import com.osheeep.server.dinner.household.dto.HouseholdMemberResponse;
import com.osheeep.server.dinner.household.dto.HouseholdResponse;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerInviteCodeMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerHouseholdService {

    private static final String ACTIVE = "ACTIVE";
    private static final String OWNER = "OWNER";
    private static final String MEMBER = "MEMBER";

    private final DinnerHouseholdAccessService accessService;
    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerInviteCodeMapper inviteMapper;
    private final DinnerHouseholdNameService nameService;
    private final DinnerHouseholdWriteService writeService;
    private final Clock clock;

    @Autowired
    public DinnerHouseholdService(
            DinnerHouseholdAccessService accessService,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerInviteCodeMapper inviteMapper,
            DinnerHouseholdNameService nameService,
            DinnerHouseholdWriteService writeService
    ) {
        this(
                accessService,
                memberMapper,
                inviteMapper,
                nameService,
                writeService,
                Clock.systemUTC());
    }

    DinnerHouseholdService(
            DinnerHouseholdAccessService accessService,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerInviteCodeMapper inviteMapper,
            DinnerHouseholdNameService nameService,
            DinnerHouseholdWriteService writeService,
            Clock clock
    ) {
        this.accessService = accessService;
        this.memberMapper = memberMapper;
        this.inviteMapper = inviteMapper;
        this.nameService = nameService;
        this.writeService = writeService;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HouseholdResponse current(Long userId) {
        HouseholdReadSnapshot snapshot = readSnapshot(userId, true);
        return snapshot == null ? null : snapshot.household();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HouseholdManagementResponse management(Long userId) {
        HouseholdReadSnapshot snapshot = readSnapshot(userId, false);
        return new HouseholdManagementResponse(
                snapshot.household(), snapshot.members(), snapshot.invite());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HouseholdInviteStatusResponse inviteStatus(Long userId) {
        return readSnapshot(userId, false).invite();
    }

    public HouseholdCreatedResponse create(Long userId, String requestedName) {
        if (accessService.findActiveMembership(userId) != null) {
            throw new BusinessException(ErrorCode.DINNER_ALREADY_IN_HOUSEHOLD);
        }
        String preparedName = nameService.prepareForCreate(userId, requestedName);
        return writeService.create(userId, preparedName);
    }

    public HouseholdResponse join(Long userId, String inviteCode) {
        String normalizedInviteCode;
        try {
            normalizedInviteCode = InviteCodeGenerator.normalize(inviteCode);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.DINNER_INVITE_INVALID);
        }
        return writeService.join(userId, normalizedInviteCode);
    }

    public HouseholdResponse rename(Long userId, String name, Long expectedVersion) {
        accessService.requireActiveHousehold(userId);
        String preparedName = nameService.moderate(userId, name);
        return writeService.rename(userId, preparedName, expectedVersion);
    }

    public HouseholdCreatedResponse refreshInvite(Long userId) {
        return writeService.refreshInvite(userId);
    }

    public HouseholdInviteStatusResponse revokeInvite(Long userId) {
        return writeService.revokeInvite(userId);
    }

    private HouseholdReadSnapshot readSnapshot(Long userId, boolean nullable) {
        ActiveHouseholdSnapshot active = accessService.findActiveSnapshot(userId);
        if (active == null) {
            if (nullable) {
                return null;
            }
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED);
        }
        DinnerHouseholdEntity household = active.household();
        DinnerHouseholdMemberEntity actorMembership = active.membership();
        List<DinnerHouseholdMemberEntity> activeMembers =
                memberMapper.selectActiveByHouseholdId(household.getId());
        validateReadMembers(activeMembers, household.getId(), actorMembership);

        List<HouseholdMemberResponse> memberResponses = new ArrayList<>(activeMembers.size());
        for (DinnerHouseholdMemberEntity membership : activeMembers) {
            memberResponses.add(new HouseholdMemberResponse(
                    membership.getId(),
                    membership.getVersion(),
                    membership.getRole(),
                    userId.equals(membership.getUserId()) ? "ME" : "PARTNER",
                    toInstant(membership.getJoinedAt())));
        }
        HouseholdResponse householdResponse = new HouseholdResponse(
                household.getId(),
                household.getName(),
                household.getTimezone(),
                activeMembers.size(),
                household.getVersion(),
                household.getInviteRevision(),
                actorMembership.getRole(),
                actorMembership.getId(),
                actorMembership.getVersion());
        HouseholdInviteStatusResponse inviteResponse = inviteResponse(
                userId, household, inviteMapper.selectOpenByHouseholdId(household.getId()));
        return new HouseholdReadSnapshot(householdResponse, memberResponses, inviteResponse);
    }

    private void validateReadMembers(
            List<DinnerHouseholdMemberEntity> members,
            Long householdId,
            DinnerHouseholdMemberEntity actorMembership
    ) {
        if (members == null || members.isEmpty() || members.size() > 2) {
            throw householdVersionConflict();
        }
        Long previousId = null;
        int actorCount = 0;
        int ownerCount = 0;
        boolean[] occupiedSeats = new boolean[3];
        Set<Long> userIds = new HashSet<>();
        for (DinnerHouseholdMemberEntity member : members) {
            Integer seatNo = member == null ? null : member.getSeatNo();
            if (member == null
                    || member.getId() == null
                    || !householdId.equals(member.getHouseholdId())
                    || !ACTIVE.equals(member.getStatus())
                    || member.getUserId() == null
                    || !userIds.add(member.getUserId())
                    || member.getVersion() == null
                    || member.getVersion() < 1
                    || (!OWNER.equals(member.getRole()) && !MEMBER.equals(member.getRole()))
                    || seatNo == null
                    || seatNo < 1
                    || seatNo > 2
                    || occupiedSeats[seatNo]
                    || member.getJoinedAt() == null
                    || member.getHistoryVisibleFrom() == null
                    || member.getEndedAt() != null
                    || member.getEndedBy() != null
                    || member.getEndReason() != null
                    || (previousId != null && member.getId() <= previousId)) {
                throw householdVersionConflict();
            }
            occupiedSeats[seatNo] = true;
            if (OWNER.equals(member.getRole())) {
                ownerCount++;
            }
            if (Objects.equals(actorMembership.getId(), member.getId())
                    && Objects.equals(actorMembership.getUserId(), member.getUserId())
                    && Objects.equals(actorMembership.getHouseholdId(), member.getHouseholdId())
                    && Objects.equals(actorMembership.getVersion(), member.getVersion())
                    && Objects.equals(actorMembership.getRole(), member.getRole())
                    && Objects.equals(actorMembership.getSeatNo(), member.getSeatNo())
                    && Objects.equals(actorMembership.getHistoryVisibleFrom(),
                            member.getHistoryVisibleFrom())) {
                actorCount++;
            }
            previousId = member.getId();
        }
        if (actorCount != 1 || ownerCount != 1) {
            throw householdVersionConflict();
        }
    }

    private HouseholdInviteStatusResponse inviteResponse(
            Long userId,
            DinnerHouseholdEntity household,
            DinnerInviteCodeEntity invite
    ) {
        if (invite == null) {
            return new HouseholdInviteStatusResponse(
                    "NONE", household.getInviteRevision(), null, false);
        }
        if (!household.getId().equals(invite.getHouseholdId())
                || invite.getConsumedAt() != null
                || invite.getRevokedAt() != null
                || invite.getExpiresAt() == null) {
            throw householdVersionConflict();
        }
        String state = invite.getExpiresAt().isAfter(now()) ? "ACTIVE" : "EXPIRED";
        return new HouseholdInviteStatusResponse(
                state,
                household.getInviteRevision(),
                toInstant(invite.getExpiresAt()),
                userId.equals(invite.getCreatedBy()));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private Instant toInstant(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }

    private BusinessException householdVersionConflict() {
        return new BusinessException(ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);
    }

    private record HouseholdReadSnapshot(
            HouseholdResponse household,
            List<HouseholdMemberResponse> members,
            HouseholdInviteStatusResponse invite
    ) {
        private HouseholdReadSnapshot {
            members = List.copyOf(members);
        }
    }
}
