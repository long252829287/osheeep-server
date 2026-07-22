package com.osheeep.server.dinner.household;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.dto.HouseholdCreatedResponse;
import com.osheeep.server.dinner.household.dto.HouseholdResponse;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerInviteCodeMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DinnerHouseholdService {

    private static final String DEFAULT_NAME = "我们的小家";
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final int MAX_INVITE_ATTEMPTS = 5;

    private final DinnerHouseholdMapper householdMapper;
    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerInviteCodeMapper inviteMapper;
    private final InviteCodeHasher inviteCodeHasher;
    private final DinnerHouseholdAccessService accessService;
    private final Clock clock;
    private final SecureRandom secureRandom;

    @Autowired
    public DinnerHouseholdService(
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerInviteCodeMapper inviteMapper,
            InviteCodeHasher inviteCodeHasher,
            DinnerHouseholdAccessService accessService
    ) {
        this(
                householdMapper,
                memberMapper,
                inviteMapper,
                inviteCodeHasher,
                accessService,
                Clock.systemUTC(),
                new SecureRandom());
    }

    DinnerHouseholdService(
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerInviteCodeMapper inviteMapper,
            InviteCodeHasher inviteCodeHasher,
            Clock clock,
            SecureRandom secureRandom
    ) {
        this(
                householdMapper,
                memberMapper,
                inviteMapper,
                inviteCodeHasher,
                new DinnerHouseholdAccessService(memberMapper, householdMapper),
                clock,
                secureRandom);
    }

    DinnerHouseholdService(
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerInviteCodeMapper inviteMapper,
            InviteCodeHasher inviteCodeHasher,
            DinnerHouseholdAccessService accessService,
            Clock clock,
            SecureRandom secureRandom
    ) {
        this.householdMapper = householdMapper;
        this.memberMapper = memberMapper;
        this.inviteMapper = inviteMapper;
        this.inviteCodeHasher = inviteCodeHasher;
        this.accessService = accessService;
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    public HouseholdResponse current(Long userId) {
        DinnerHouseholdEntity household = accessService.findActiveHousehold(userId);
        if (household == null) {
            return null;
        }
        long memberCount = countMembers(household.getId());
        return response(household, memberCount);
    }

    @Transactional
    public HouseholdCreatedResponse create(Long userId, String requestedName) {
        requireNotInHousehold(userId);
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setName(StringUtils.hasText(requestedName) ? requestedName.trim() : DEFAULT_NAME);
        household.setTimezone(DEFAULT_TIMEZONE);
        household.setStatus("ACTIVE");
        household.setCreatedBy(userId);
        householdMapper.insert(household);
        memberMapper.insert(member(household.getId(), userId));
        return createInvite(household, userId);
    }

    @Transactional
    public HouseholdCreatedResponse refreshInvite(Long userId) {
        DinnerHouseholdMemberEntity membership = memberMapper.selectByUserIdForUpdate(userId);
        if (membership == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        DinnerHouseholdEntity household = householdMapper.selectByIdForUpdate(membership.getHouseholdId());
        if (household == null || !"ACTIVE".equals(household.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        LocalDateTime now = now();
        DinnerInviteCodeEntity activeInvite = inviteMapper.selectActiveByHouseholdIdForUpdate(
                membership.getHouseholdId(), now);
        if (activeInvite != null) {
            activeInvite.setRevokedAt(now);
            inviteMapper.updateById(activeInvite);
        }
        return createInvite(household, userId);
    }

    @Transactional
    public HouseholdResponse join(Long userId, String inviteCode) {
        requireNotInHousehold(userId);
        String codeHash = inviteCodeHasher.hash(inviteCode);
        DinnerInviteCodeEntity locatedInvite = inviteMapper.selectByCodeHash(codeHash);
        validateInvite(locatedInvite);
        DinnerHouseholdEntity household = householdMapper.selectByIdForUpdate(locatedInvite.getHouseholdId());
        if (household == null || !"ACTIVE".equals(household.getStatus())) {
            throw new BusinessException(ErrorCode.DINNER_INVITE_INVALID);
        }
        DinnerInviteCodeEntity lockedInvite = inviteMapper.selectByIdAndHouseholdIdForUpdate(
                locatedInvite.getId(), household.getId());
        validateInvite(lockedInvite);
        long memberCount = countMembers(household.getId());
        if (memberCount >= 2) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_FULL);
        }
        try {
            memberMapper.insert(member(household.getId(), userId));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.DINNER_ALREADY_IN_HOUSEHOLD);
        }
        return response(household, memberCount + 1);
    }

    private void validateInvite(DinnerInviteCodeEntity invite) {
        if (invite == null || invite.getRevokedAt() != null) {
            throw new BusinessException(ErrorCode.DINNER_INVITE_INVALID);
        }
        if (!invite.getExpiresAt().isAfter(now())) {
            throw new BusinessException(ErrorCode.DINNER_INVITE_EXPIRED);
        }
    }

    private HouseholdCreatedResponse createInvite(DinnerHouseholdEntity household, Long userId) {
        for (int attempt = 0; attempt < MAX_INVITE_ATTEMPTS; attempt++) {
            String normalizedCode = "DINNER%04d".formatted(secureRandom.nextInt(10_000));
            Instant expiresAt = clock.instant().plusSeconds(24 * 60 * 60);
            DinnerInviteCodeEntity invite = new DinnerInviteCodeEntity();
            invite.setHouseholdId(household.getId());
            invite.setCodeHash(inviteCodeHasher.hash(normalizedCode));
            invite.setExpiresAt(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
            invite.setCreatedBy(userId);
            try {
                inviteMapper.insert(invite);
                String displayCode = normalizedCode.substring(0, 6) + " " + normalizedCode.substring(6);
                return new HouseholdCreatedResponse(response(household, 1), displayCode, expiresAt);
            } catch (DuplicateKeyException ignored) {
                // Retry with a new random code; never log the colliding plaintext code.
            }
        }
        throw new BusinessException(ErrorCode.BUSINESS_ERROR, "Unable to create invite code");
    }

    private void requireNotInHousehold(Long userId) {
        if (accessService.findActiveMembership(userId) != null) {
            throw new BusinessException(ErrorCode.DINNER_ALREADY_IN_HOUSEHOLD);
        }
    }

    private long countMembers(Long householdId) {
        return memberMapper.selectCount(Wrappers.<DinnerHouseholdMemberEntity>lambdaQuery()
                .eq(DinnerHouseholdMemberEntity::getHouseholdId, householdId)
                .eq(DinnerHouseholdMemberEntity::getStatus, "ACTIVE"));
    }

    private DinnerHouseholdMemberEntity member(Long householdId, Long userId) {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setHouseholdId(householdId);
        member.setUserId(userId);
        return member;
    }

    private HouseholdResponse response(DinnerHouseholdEntity household, long memberCount) {
        return new HouseholdResponse(
                household.getId(), household.getName(), household.getTimezone(), Math.toIntExact(memberCount));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
