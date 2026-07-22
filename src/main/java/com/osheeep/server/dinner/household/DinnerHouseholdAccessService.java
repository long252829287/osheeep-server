package com.osheeep.server.dinner.household;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DinnerHouseholdAccessService {

    private static final String ACTIVE = "ACTIVE";
    private static final String OWNER = "OWNER";
    private static final String MEMBER = "MEMBER";

    private final UserMapper userMapper;
    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerHouseholdMapper householdMapper;

    @Autowired
    public DinnerHouseholdAccessService(
            UserMapper userMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerHouseholdMapper householdMapper
    ) {
        this.userMapper = userMapper;
        this.memberMapper = memberMapper;
        this.householdMapper = householdMapper;
    }

    /**
     * Compatibility constructor for read-only adapters that have not yet moved user locking to
     * this boundary. Write paths must use the Spring-managed three-argument service.
     */
    public DinnerHouseholdAccessService(
            DinnerHouseholdMemberMapper memberMapper,
            DinnerHouseholdMapper householdMapper
    ) {
        this(null, memberMapper, householdMapper);
    }

    public DinnerHouseholdMemberEntity findActiveMembership(Long userId) {
        if (userId == null) {
            return null;
        }
        DinnerHouseholdMemberEntity membership = memberMapper.selectActiveByUserId(userId);
        if (membership == null
                || !ACTIVE.equals(membership.getStatus())
                || membership.getId() == null
                || membership.getHouseholdId() == null
                || !userId.equals(membership.getUserId())) {
            return null;
        }
        return membership;
    }

    public DinnerHouseholdEntity findActiveHousehold(Long userId) {
        ActiveContext context = findActiveContext(userId);
        return context == null ? null : context.household();
    }

    public ActiveHouseholdAccess requireActiveHousehold(Long userId) {
        ActiveContext context = findActiveContext(userId);
        if (context == null) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED);
        }
        DinnerHouseholdMemberEntity membership = context.membership();
        DinnerHouseholdEntity household = context.household();
        return new ActiveHouseholdAccess(
                userId,
                household.getId(),
                membership.getId(),
                membership.getVersion(),
                membership.getRole(),
                membership.getHistoryVisibleFrom(),
                household.getVersion(),
                household.getTimezone());
    }

    public ActiveHouseholdAccess requireOwner(ActiveHouseholdAccess access) {
        if (access == null || !OWNER.equals(access.role())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return access;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LockedHouseholdContext lockActiveHouseholdContext(Long actorUserId) {
        if (userMapper == null) {
            throw new IllegalStateException(
                    "Household write locking requires a UserMapper-backed access service");
        }

        try {
            UserEntity actorUser = userMapper.selectByIdForUpdate(actorUserId);
            if (!isActiveActor(actorUser, actorUserId)) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "User is not available");
            }

            // This unlocked row supplies only the candidate household to lock next. No
            // authorization decision is made until the household and its complete ACTIVE
            // membership set are locked.
            DinnerHouseholdMemberEntity candidate = memberMapper.selectActiveByUserId(actorUserId);
            Long candidateHouseholdId = candidate == null ? null : candidate.getHouseholdId();
            if (candidateHouseholdId == null) {
                throw householdRequired();
            }

            DinnerHouseholdEntity household =
                    householdMapper.selectByIdForUpdate(candidateHouseholdId);
            if (!isCompleteActiveHousehold(household, candidateHouseholdId)) {
                throw householdRequired();
            }

            List<DinnerHouseholdMemberEntity> members =
                    memberMapper.selectActiveByHouseholdIdForUpdate(candidateHouseholdId);
            DinnerHouseholdMemberEntity actorMembership = validateLockedMemberships(
                    members, candidateHouseholdId, actorUserId);
            if (!sameAuthorizationSnapshot(candidate, actorMembership)) {
                throw householdVersionConflict();
            }

            ActiveHouseholdAccess access = toAccess(actorUserId, actorMembership, household);
            return new LockedHouseholdContext(actorUser, household, members, access);
        } catch (PessimisticLockingFailureException exception) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);
        }
    }

    public LockedHouseholdContext requireOwner(LockedHouseholdContext context) {
        if (context == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        requireOwner(context.access());
        return context;
    }

    private ActiveContext findActiveContext(Long userId) {
        DinnerHouseholdMemberEntity membership = findActiveMembership(userId);
        if (!isCompleteActiveMembership(membership)) {
            return null;
        }
        DinnerHouseholdEntity household = householdMapper.selectById(membership.getHouseholdId());
        if (!isCompleteActiveHousehold(household, membership.getHouseholdId())) {
            return null;
        }
        return new ActiveContext(membership, household);
    }

    private boolean isCompleteActiveMembership(DinnerHouseholdMemberEntity membership) {
        return membership != null
                && membership.getVersion() != null
                && membership.getVersion() >= 1
                && (OWNER.equals(membership.getRole()) || MEMBER.equals(membership.getRole()))
                && membership.getHistoryVisibleFrom() != null;
    }

    private boolean isCompleteActiveHousehold(DinnerHouseholdEntity household, Long householdId) {
        return household != null
                && householdId.equals(household.getId())
                && ACTIVE.equals(household.getStatus())
                && household.getVersion() != null
                && household.getVersion() >= 1
                && StringUtils.hasText(household.getTimezone());
    }

    private boolean isActiveActor(UserEntity actor, Long actorUserId) {
        return actor != null
                && actorUserId != null
                && actorUserId.equals(actor.getId())
                && ACTIVE.equals(actor.getStatus())
                && actor.getDeletedAt() == null;
    }

    private DinnerHouseholdMemberEntity validateLockedMemberships(
            List<DinnerHouseholdMemberEntity> members,
            Long householdId,
            Long actorUserId
    ) {
        if (members == null || members.size() > 2) {
            throw householdVersionConflict();
        }
        if (members.isEmpty()) {
            throw householdRequired();
        }

        Long previousId = null;
        boolean[] occupiedSeats = new boolean[3];
        int ownerCount = 0;
        DinnerHouseholdMemberEntity actorMembership = null;
        for (DinnerHouseholdMemberEntity membership : members) {
            Integer seatNo = membership == null ? null : membership.getSeatNo();
            if (!isCompleteLockedMembership(membership, householdId)
                    || (previousId != null && membership.getId() <= previousId)
                    || seatNo == null
                    || seatNo < 1
                    || seatNo > 2
                    || occupiedSeats[seatNo]) {
                throw householdVersionConflict();
            }
            occupiedSeats[seatNo] = true;
            if (OWNER.equals(membership.getRole())) {
                ownerCount++;
            }
            previousId = membership.getId();
            if (actorUserId.equals(membership.getUserId())) {
                if (actorMembership != null) {
                    throw householdVersionConflict();
                }
                actorMembership = membership;
            }
        }
        if (actorMembership == null) {
            throw householdRequired();
        }
        if (ownerCount != 1) {
            throw householdVersionConflict();
        }
        return actorMembership;
    }

    private boolean isCompleteLockedMembership(
            DinnerHouseholdMemberEntity membership,
            Long householdId
    ) {
        return isCompleteActiveMembership(membership)
                && ACTIVE.equals(membership.getStatus())
                && householdId.equals(membership.getHouseholdId())
                && membership.getId() != null
                && membership.getUserId() != null;
    }

    private boolean sameAuthorizationSnapshot(
            DinnerHouseholdMemberEntity candidate,
            DinnerHouseholdMemberEntity locked
    ) {
        return candidate != null
                && locked != null
                && ACTIVE.equals(candidate.getStatus())
                && Objects.equals(candidate.getId(), locked.getId())
                && Objects.equals(candidate.getUserId(), locked.getUserId())
                && Objects.equals(candidate.getHouseholdId(), locked.getHouseholdId())
                && Objects.equals(candidate.getVersion(), locked.getVersion())
                && Objects.equals(candidate.getRole(), locked.getRole())
                && Objects.equals(candidate.getSeatNo(), locked.getSeatNo())
                && Objects.equals(candidate.getHistoryVisibleFrom(), locked.getHistoryVisibleFrom());
    }

    private ActiveHouseholdAccess toAccess(
            Long userId,
            DinnerHouseholdMemberEntity membership,
            DinnerHouseholdEntity household
    ) {
        return new ActiveHouseholdAccess(
                userId,
                household.getId(),
                membership.getId(),
                membership.getVersion(),
                membership.getRole(),
                membership.getHistoryVisibleFrom(),
                household.getVersion(),
                household.getTimezone());
    }

    private BusinessException householdRequired() {
        return new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED);
    }

    private BusinessException householdVersionConflict() {
        return new BusinessException(ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);
    }

    private record ActiveContext(
            DinnerHouseholdMemberEntity membership,
            DinnerHouseholdEntity household
    ) {}

    public record ActiveHouseholdAccess(
            Long userId,
            Long householdId,
            Long membershipId,
            Long membershipVersion,
            String role,
            LocalDateTime historyVisibleFrom,
            Long householdVersion,
            String timezone
    ) {}

    public record LockedHouseholdContext(
            UserEntity actorUser,
            DinnerHouseholdEntity household,
            List<DinnerHouseholdMemberEntity> members,
            ActiveHouseholdAccess access
    ) {
        public LockedHouseholdContext {
            Objects.requireNonNull(actorUser, "actorUser");
            Objects.requireNonNull(household, "household");
            members = List.copyOf(members);
            Objects.requireNonNull(access, "access");
        }
    }
}
