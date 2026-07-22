package com.osheeep.server.dinner.household;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.LockedHouseholdContext;
import com.osheeep.server.dinner.household.dto.HouseholdCreatedResponse;
import com.osheeep.server.dinner.household.dto.HouseholdInviteStatusResponse;
import com.osheeep.server.dinner.household.dto.HouseholdResponse;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerHouseholdWriteService {

    private static final String ACTIVE = "ACTIVE";
    private static final String OWNER = "OWNER";
    private static final String MEMBER = "MEMBER";
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final int MAX_INVITE_ATTEMPTS = 5;

    private static final String ACTIVE_USER_CONSTRAINT =
            "uk_dinner_household_members_active_user";
    private static final String ACTIVE_SEAT_CONSTRAINT =
            "uk_dinner_household_members_active_seat";
    private static final String ACTIVE_OWNER_CONSTRAINT =
            "uk_dinner_household_members_active_owner";
    private static final String INVITE_HASH_CONSTRAINT = "uk_dinner_invite_codes_hash";
    private static final String OPEN_INVITE_CONSTRAINT =
            "uk_dinner_invite_codes_open_household";

    private final UserMapper userMapper;
    private final DinnerHouseholdMapper householdMapper;
    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerInviteCodeMapper inviteMapper;
    private final DinnerHouseholdAccessService accessService;
    private final DinnerHouseholdNameService nameService;
    private final DinnerHouseholdDraftLifecycleService draftLifecycleService;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final InviteCodeHasher inviteCodeHasher;
    private final Clock clock;

    @Autowired
    public DinnerHouseholdWriteService(
            UserMapper userMapper,
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerInviteCodeMapper inviteMapper,
            DinnerHouseholdAccessService accessService,
            DinnerHouseholdNameService nameService,
            DinnerHouseholdDraftLifecycleService draftLifecycleService,
            InviteCodeGenerator inviteCodeGenerator,
            InviteCodeHasher inviteCodeHasher
    ) {
        this(
                userMapper,
                householdMapper,
                memberMapper,
                inviteMapper,
                accessService,
                nameService,
                draftLifecycleService,
                inviteCodeGenerator,
                inviteCodeHasher,
                Clock.systemUTC());
    }

    DinnerHouseholdWriteService(
            UserMapper userMapper,
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerInviteCodeMapper inviteMapper,
            DinnerHouseholdAccessService accessService,
            DinnerHouseholdNameService nameService,
            DinnerHouseholdDraftLifecycleService draftLifecycleService,
            InviteCodeGenerator inviteCodeGenerator,
            InviteCodeHasher inviteCodeHasher,
            Clock clock
    ) {
        this.userMapper = userMapper;
        this.householdMapper = householdMapper;
        this.memberMapper = memberMapper;
        this.inviteMapper = inviteMapper;
        this.accessService = accessService;
        this.nameService = nameService;
        this.draftLifecycleService = draftLifecycleService;
        this.inviteCodeGenerator = inviteCodeGenerator;
        this.inviteCodeHasher = inviteCodeHasher;
        this.clock = clock;
    }

    @Transactional
    public HouseholdCreatedResponse create(Long actorUserId, String preparedName) {
        try {
            lockActiveActor(actorUserId);
            if (memberMapper.selectActiveByUserId(actorUserId) != null) {
                throw new BusinessException(ErrorCode.DINNER_ALREADY_IN_HOUSEHOLD);
            }
            String normalizedName = nameService.normalize(preparedName);
            LocalDateTime now = now();

            DinnerHouseholdEntity household = new DinnerHouseholdEntity();
            household.setName(normalizedName);
            household.setTimezone(DEFAULT_TIMEZONE);
            household.setStatus(ACTIVE);
            household.setVersion(1L);
            household.setInviteRevision(1L);
            household.setCreatedBy(actorUserId);
            requireSingleWrite(householdMapper.insert(household));

            DinnerHouseholdMemberEntity owner = newMembership(
                    household.getId(), actorUserId, OWNER, 1, now);
            requireSingleWrite(memberMapper.insert(owner));

            GeneratedInvite generatedInvite = insertInvite(household.getId(), actorUserId, now);
            draftLifecycleService.rebindUnboundDrafts(actorUserId, household.getId());
            return new HouseholdCreatedResponse(
                    response(household, owner, 1),
                    generatedInvite.plaintext(),
                    household.getInviteRevision(),
                    generatedInvite.expiresAt());
        } catch (DuplicateKeyException exception) {
            throw mapCreateDuplicate(exception);
        } catch (PessimisticLockingFailureException exception) {
            throw householdVersionConflict();
        }
    }

    @Transactional
    public HouseholdResponse join(Long actorUserId, String normalizedInviteCode) {
        try {
            lockActiveActor(actorUserId);
            if (memberMapper.selectActiveByUserId(actorUserId) != null) {
                throw new BusinessException(ErrorCode.DINNER_ALREADY_IN_HOUSEHOLD);
            }

            String canonicalCode = InviteCodeGenerator.normalize(normalizedInviteCode);
            String codeHash = inviteCodeHasher.hash(canonicalCode);
            DinnerInviteCodeEntity candidate = inviteMapper.selectByCodeHash(codeHash);
            validateInvite(candidate, now());

            DinnerHouseholdEntity household =
                    householdMapper.selectByIdForUpdate(candidate.getHouseholdId());
            requireActiveHousehold(household, candidate.getHouseholdId(), ErrorCode.DINNER_INVITE_INVALID);

            List<DinnerHouseholdMemberEntity> members =
                    memberMapper.selectActiveByHouseholdIdForUpdate(household.getId());
            int freeSeat = requireJoinableMembers(members, household.getId(), actorUserId);
            if (members.size() >= 2) {
                throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_FULL);
            }

            DinnerInviteCodeEntity lockedInvite = inviteMapper.selectByIdAndHouseholdIdForUpdate(
                    candidate.getId(), household.getId());
            if (!sameInvite(candidate, lockedInvite, codeHash)) {
                throw new BusinessException(ErrorCode.DINNER_INVITE_INVALID);
            }
            LocalDateTime now = now();
            validateInvite(lockedInvite, now);
            if (inviteMapper.consumeOpenInvite(
                    lockedInvite.getId(), household.getId(), now, actorUserId) != 1) {
                throw new BusinessException(ErrorCode.DINNER_INVITE_INVALID);
            }

            DinnerHouseholdMemberEntity member = newMembership(
                    household.getId(), actorUserId, MEMBER, freeSeat, now);
            requireSingleWrite(memberMapper.insert(member));
            if (householdMapper.advanceMembershipAndInviteRevision(
                    household.getId(), household.getVersion(), household.getInviteRevision()) != 1) {
                throw householdVersionConflict();
            }
            household.setVersion(household.getVersion() + 1L);
            household.setInviteRevision(household.getInviteRevision() + 1L);

            draftLifecycleService.rebindUnboundDrafts(actorUserId, household.getId());
            return response(household, member, members.size() + 1);
        } catch (DuplicateKeyException exception) {
            throw mapJoinDuplicate(exception);
        } catch (PessimisticLockingFailureException exception) {
            throw householdVersionConflict();
        }
    }

    @Transactional
    public HouseholdResponse rename(
            Long actorUserId,
            String preparedName,
            Long expectedVersion
    ) {
        try {
            if (expectedVersion == null || expectedVersion < 1) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            }
            String normalizedName = nameService.normalize(preparedName);
            LockedHouseholdContext context = accessService.lockActiveHouseholdContext(actorUserId);
            DinnerHouseholdEntity household = context.household();
            if (!Objects.equals(household.getVersion(), expectedVersion)
                    || householdMapper.renameActiveHousehold(
                    household.getId(), expectedVersion, normalizedName) != 1) {
                throw householdVersionConflict();
            }
            household.setName(normalizedName);
            household.setVersion(expectedVersion + 1L);
            return response(household, context.access().membershipId(),
                    context.access().membershipVersion(), context.access().role(),
                    context.members().size());
        } catch (PessimisticLockingFailureException exception) {
            throw householdVersionConflict();
        }
    }

    @Transactional
    public HouseholdCreatedResponse refreshInvite(Long actorUserId) {
        try {
            LockedHouseholdContext context = accessService.lockActiveHouseholdContext(actorUserId);
            if (context.members().size() >= 2) {
                throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_FULL);
            }
            DinnerHouseholdEntity household = context.household();
            LocalDateTime now = now();
            revokeAllOpenInvites(household.getId(), now, "REFRESHED");
            GeneratedInvite generatedInvite = insertInvite(household.getId(), actorUserId, now);
            advanceInviteRevision(household);
            return new HouseholdCreatedResponse(
                    response(household, context.access().membershipId(),
                            context.access().membershipVersion(), context.access().role(),
                            context.members().size()),
                    generatedInvite.plaintext(),
                    household.getInviteRevision(),
                    generatedInvite.expiresAt());
        } catch (DuplicateKeyException exception) {
            if (hasConstraint(exception, OPEN_INVITE_CONSTRAINT)
                    || hasConstraint(exception, ACTIVE_OWNER_CONSTRAINT)
                    || hasConstraint(exception, ACTIVE_SEAT_CONSTRAINT)) {
                throw householdVersionConflict();
            }
            throw exception;
        } catch (PessimisticLockingFailureException exception) {
            throw householdVersionConflict();
        }
    }

    @Transactional
    public HouseholdInviteStatusResponse revokeInvite(Long actorUserId) {
        try {
            LockedHouseholdContext context = accessService.lockActiveHouseholdContext(actorUserId);
            DinnerHouseholdEntity household = context.household();
            LocalDateTime now = now();
            List<DinnerInviteCodeEntity> openInvites =
                    inviteMapper.selectAllOpenByHouseholdIdForUpdate(household.getId());
            if (openInvites.isEmpty()) {
                return new HouseholdInviteStatusResponse(
                        "NONE", household.getInviteRevision(), null, false);
            }
            revokeLockedInvites(household.getId(), openInvites, now, "MEMBER_REVOKED");
            advanceInviteRevision(household);
            return new HouseholdInviteStatusResponse(
                    "NONE", household.getInviteRevision(), null, false);
        } catch (PessimisticLockingFailureException exception) {
            throw householdVersionConflict();
        }
    }

    private UserEntity lockActiveActor(Long actorUserId) {
        UserEntity actor = userMapper.selectByIdForUpdate(actorUserId);
        if (actor == null
                || actorUserId == null
                || !actorUserId.equals(actor.getId())
                || !ACTIVE.equals(actor.getStatus())
                || actor.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "User is not available");
        }
        return actor;
    }

    private DinnerHouseholdMemberEntity newMembership(
            Long householdId,
            Long userId,
            String role,
            int seatNo,
            LocalDateTime now
    ) {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setHouseholdId(householdId);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(ACTIVE);
        member.setSeatNo(seatNo);
        member.setHistoryVisibleFrom(now);
        member.setVersion(1L);
        member.setJoinedAt(now);
        return member;
    }

    private GeneratedInvite insertInvite(Long householdId, Long actorUserId, LocalDateTime now) {
        for (int attempt = 0; attempt < MAX_INVITE_ATTEMPTS; attempt++) {
            String plaintext = inviteCodeGenerator.generate();
            Instant expiresAt = now.toInstant(ZoneOffset.UTC).plus(24, ChronoUnit.HOURS);
            DinnerInviteCodeEntity invite = new DinnerInviteCodeEntity();
            invite.setHouseholdId(householdId);
            invite.setCodeHash(inviteCodeHasher.hash(plaintext));
            invite.setExpiresAt(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
            invite.setCreatedBy(actorUserId);
            try {
                requireSingleWrite(inviteMapper.insert(invite));
                return new GeneratedInvite(plaintext, expiresAt);
            } catch (DuplicateKeyException exception) {
                if (hasConstraint(exception, INVITE_HASH_CONSTRAINT)) {
                    continue;
                }
                throw exception;
            }
        }
        throw new BusinessException(ErrorCode.BUSINESS_ERROR, "Unable to create invite code");
    }

    private void revokeAllOpenInvites(Long householdId, LocalDateTime now, String reason) {
        List<DinnerInviteCodeEntity> openInvites =
                inviteMapper.selectAllOpenByHouseholdIdForUpdate(householdId);
        revokeLockedInvites(householdId, openInvites, now, reason);
    }

    private void revokeLockedInvites(
            Long householdId,
            List<DinnerInviteCodeEntity> openInvites,
            LocalDateTime now,
            String reason
    ) {
        Long previousId = null;
        for (DinnerInviteCodeEntity invite : openInvites) {
            if (invite == null
                    || invite.getId() == null
                    || !householdId.equals(invite.getHouseholdId())
                    || invite.getConsumedAt() != null
                    || invite.getRevokedAt() != null
                    || (previousId != null && invite.getId() <= previousId)
                    || inviteMapper.revokeOpenInvite(
                    invite.getId(), householdId, now, reason) != 1) {
                throw householdVersionConflict();
            }
            previousId = invite.getId();
        }
    }

    private void advanceInviteRevision(DinnerHouseholdEntity household) {
        if (householdMapper.advanceInviteRevision(
                household.getId(), household.getVersion(), household.getInviteRevision()) != 1) {
            throw householdVersionConflict();
        }
        household.setInviteRevision(household.getInviteRevision() + 1L);
    }

    private void validateInvite(DinnerInviteCodeEntity invite, LocalDateTime now) {
        if (invite == null
                || invite.getId() == null
                || invite.getHouseholdId() == null
                || invite.getCodeHash() == null
                || invite.getConsumedAt() != null
                || invite.getRevokedAt() != null
                || invite.getExpiresAt() == null) {
            throw new BusinessException(ErrorCode.DINNER_INVITE_INVALID);
        }
        if (!invite.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.DINNER_INVITE_EXPIRED);
        }
    }

    private void requireActiveHousehold(
            DinnerHouseholdEntity household,
            Long expectedId,
            ErrorCode missingError
    ) {
        if (household == null
                || !Objects.equals(expectedId, household.getId())
                || !ACTIVE.equals(household.getStatus())
                || household.getVersion() == null
                || household.getVersion() < 1
                || household.getInviteRevision() == null
                || household.getInviteRevision() < 0) {
            throw new BusinessException(missingError);
        }
    }

    private int requireJoinableMembers(
            List<DinnerHouseholdMemberEntity> members,
            Long householdId,
            Long actorUserId
    ) {
        if (members == null || members.isEmpty() || members.size() > 2) {
            throw householdVersionConflict();
        }
        boolean[] occupiedSeats = new boolean[3];
        int ownerCount = 0;
        Long previousId = null;
        for (DinnerHouseholdMemberEntity member : members) {
            if (!isCompleteActiveMember(member, householdId)
                    || actorUserId.equals(member.getUserId())
                    || (previousId != null && member.getId() <= previousId)
                    || occupiedSeats[member.getSeatNo()]) {
                throw householdVersionConflict();
            }
            occupiedSeats[member.getSeatNo()] = true;
            if (OWNER.equals(member.getRole())) {
                ownerCount++;
            }
            previousId = member.getId();
        }
        if (ownerCount != 1) {
            throw householdVersionConflict();
        }
        return occupiedSeats[1] ? 2 : 1;
    }

    private boolean isCompleteActiveMember(
            DinnerHouseholdMemberEntity member,
            Long householdId
    ) {
        return member != null
                && member.getId() != null
                && householdId.equals(member.getHouseholdId())
                && member.getUserId() != null
                && ACTIVE.equals(member.getStatus())
                && (OWNER.equals(member.getRole()) || MEMBER.equals(member.getRole()))
                && member.getSeatNo() != null
                && member.getSeatNo() >= 1
                && member.getSeatNo() <= 2
                && member.getVersion() != null
                && member.getVersion() >= 1
                && member.getHistoryVisibleFrom() != null;
    }

    private boolean sameInvite(
            DinnerInviteCodeEntity candidate,
            DinnerInviteCodeEntity locked,
            String expectedHash
    ) {
        return candidate != null
                && locked != null
                && Objects.equals(candidate.getId(), locked.getId())
                && Objects.equals(candidate.getHouseholdId(), locked.getHouseholdId())
                && Objects.equals(candidate.getCodeHash(), expectedHash)
                && Objects.equals(locked.getCodeHash(), expectedHash);
    }

    private HouseholdResponse response(
            DinnerHouseholdEntity household,
            DinnerHouseholdMemberEntity actorMembership,
            int memberCount
    ) {
        return response(
                household,
                actorMembership.getId(),
                actorMembership.getVersion(),
                actorMembership.getRole(),
                memberCount);
    }

    private HouseholdResponse response(
            DinnerHouseholdEntity household,
            Long membershipId,
            Long membershipVersion,
            String role,
            int memberCount
    ) {
        return new HouseholdResponse(
                household.getId(),
                household.getName(),
                household.getTimezone(),
                memberCount,
                household.getVersion(),
                household.getInviteRevision(),
                role,
                membershipId,
                membershipVersion);
    }

    private BusinessException mapCreateDuplicate(DuplicateKeyException exception) {
        if (hasConstraint(exception, ACTIVE_USER_CONSTRAINT)) {
            return new BusinessException(ErrorCode.DINNER_ALREADY_IN_HOUSEHOLD);
        }
        if (hasConstraint(exception, ACTIVE_OWNER_CONSTRAINT)
                || hasConstraint(exception, ACTIVE_SEAT_CONSTRAINT)
                || hasConstraint(exception, OPEN_INVITE_CONSTRAINT)) {
            return householdVersionConflict();
        }
        throw exception;
    }

    private BusinessException mapJoinDuplicate(DuplicateKeyException exception) {
        if (hasConstraint(exception, ACTIVE_USER_CONSTRAINT)) {
            return new BusinessException(ErrorCode.DINNER_ALREADY_IN_HOUSEHOLD);
        }
        if (hasConstraint(exception, ACTIVE_SEAT_CONSTRAINT)) {
            return new BusinessException(ErrorCode.DINNER_HOUSEHOLD_FULL);
        }
        if (hasConstraint(exception, ACTIVE_OWNER_CONSTRAINT)
                || hasConstraint(exception, OPEN_INVITE_CONSTRAINT)) {
            return householdVersionConflict();
        }
        throw exception;
    }

    private boolean hasConstraint(Throwable error, String constraint) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(constraint)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void requireSingleWrite(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException("Dinner household write affected an unexpected row count");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private BusinessException householdVersionConflict() {
        return new BusinessException(ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);
    }

    private record GeneratedInvite(String plaintext, Instant expiresAt) {
    }
}
