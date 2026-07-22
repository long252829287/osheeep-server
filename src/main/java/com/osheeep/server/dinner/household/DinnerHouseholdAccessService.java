package com.osheeep.server.dinner.household;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DinnerHouseholdAccessService {

    private static final String ACTIVE = "ACTIVE";
    private static final String OWNER = "OWNER";
    private static final String MEMBER = "MEMBER";

    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerHouseholdMapper householdMapper;

    public DinnerHouseholdAccessService(
            DinnerHouseholdMemberMapper memberMapper,
            DinnerHouseholdMapper householdMapper
    ) {
        this.memberMapper = memberMapper;
        this.householdMapper = householdMapper;
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
}
