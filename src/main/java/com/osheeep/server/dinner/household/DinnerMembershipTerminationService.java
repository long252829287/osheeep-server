package com.osheeep.server.dinner.household;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdDraftLifecycleService.LockedTerminationRecipes;
import com.osheeep.server.dinner.household.DinnerHouseholdOperationService.HouseholdOperationCommand;
import com.osheeep.server.dinner.household.dto.HouseholdMutationResponse;
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
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeIngredientEntity;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DinnerMembershipTerminationService {

    private static final String ACTIVE = "ACTIVE";
    private static final String OWNER = "OWNER";
    private static final String MEMBER = "MEMBER";

    private final UserMapper userMapper;
    private final DinnerHouseholdOperationMapper operationMapper;
    private final DinnerHouseholdMapper householdMapper;
    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerInviteCodeMapper inviteMapper;
    private final DinnerMenuMapper menuMapper;
    private final DinnerMenuSelectionMapper selectionMapper;
    private final DinnerHouseholdDraftLifecycleService draftLifecycleService;
    private final DinnerHouseholdInventoryMapper inventoryMapper;
    private final DinnerIngredientMapper ingredientMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public DinnerMembershipTerminationService(
            UserMapper userMapper,
            DinnerHouseholdOperationMapper operationMapper,
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerInviteCodeMapper inviteMapper,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerHouseholdDraftLifecycleService draftLifecycleService,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerIngredientMapper ingredientMapper,
            ObjectMapper objectMapper
    ) {
        this(
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
                objectMapper,
                Clock.systemUTC());
    }

    DinnerMembershipTerminationService(
            UserMapper userMapper,
            DinnerHouseholdOperationMapper operationMapper,
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerInviteCodeMapper inviteMapper,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerHouseholdDraftLifecycleService draftLifecycleService,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerIngredientMapper ingredientMapper,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.userMapper = userMapper;
        this.operationMapper = operationMapper;
        this.householdMapper = householdMapper;
        this.memberMapper = memberMapper;
        this.inviteMapper = inviteMapper;
        this.menuMapper = menuMapper;
        this.selectionMapper = selectionMapper;
        this.draftLifecycleService = draftLifecycleService;
        this.inventoryMapper = inventoryMapper;
        this.ingredientMapper = ingredientMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public HouseholdMutationResponse terminate(HouseholdOperationCommand command) {
        try {
            return terminateLocked(command);
        } catch (PessimisticLockingFailureException exception) {
            throw householdVersionConflict();
        }
    }

    private HouseholdMutationResponse terminateLocked(HouseholdOperationCommand command) {
        UserEntity actor = userMapper.selectByIdForUpdate(command.actorUserId());
        if (!isActiveActor(actor, command.actorUserId())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "User is not available");
        }

        LocalDateTime now = utcNow();
        DinnerHouseholdOperationEntity existing =
                operationMapper.selectByActorAndIdempotencyKeyForUpdate(
                        command.actorUserId(), command.idempotencyKey());
        if (existing != null) {
            if (!DinnerHouseholdOperationService.isExpired(existing, now)) {
                return DinnerHouseholdOperationService.replay(command, existing, objectMapper);
            }
            if (operationMapper.deleteExpiredByActorAndIdempotencyKey(
                    command.actorUserId(), command.idempotencyKey(), now) != 1) {
                throw householdVersionConflict();
            }
        }

        LockedTerminationContext context = lockAndValidateContext(command);
        Long householdId = context.household().getId();
        Long targetUserId = context.target().getUserId();

        // Acquire every child lock before applying changes. The fixed order is invite, menu,
        // recipe, inventory, then household ingredient.
        List<DinnerInviteCodeEntity> invites = lockOpenInvites(householdId);
        List<DinnerMenuEntity> menus = lockUncompletedMenus(householdId);
        List<Long> menuIds = menus.stream().map(DinnerMenuEntity::getId).toList();
        List<DinnerMenuSelectionEntity> selections = lockSelections(menuIds);
        LockedTerminationRecipes recipes =
                draftLifecycleService.lockTerminationRecipes(
                        targetUserId, householdId);
        List<DinnerRecipeIngredientEntity> recipeIngredients =
                draftLifecycleService.lockPersonalDraftIngredients(
                        targetUserId, householdId, recipes);
        List<DinnerHouseholdInventoryEntity> inventory =
                inventoryMapper.selectAllByHouseholdIdForUpdate(householdId);
        validateInventory(householdId, inventory);
        List<DinnerIngredientEntity> householdIngredients =
                ingredientMapper.selectAllHouseholdIngredientsForUpdate(householdId);
        if (householdIngredients == null) {
            throw householdVersionConflict();
        }

        revokeOpenInvites(invites, householdId, now);
        resetMenus(menus, selections, menuIds, householdId, targetUserId);
        draftLifecycleService.detachPersonalDrafts(
                targetUserId,
                householdId,
                recipes,
                recipeIngredients,
                householdIngredients);

        TerminationPolicy policy = context.policy();
        if (memberMapper.endActiveMember(
                context.target().getId(),
                householdId,
                targetUserId,
                context.target().getVersion(),
                policy.endedStatus,
                now,
                command.actorUserId(),
                policy.endReason) != 1) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);
        }

        DinnerHouseholdEntity household = context.household();
        if (householdMapper.advanceMembershipAndInviteRevision(
                householdId,
                household.getVersion(),
                household.getInviteRevision()) != 1) {
            throw householdVersionConflict();
        }
        long resultHouseholdVersion = Math.addExact(household.getVersion(), 1L);
        persistResult(command, householdId, policy, resultHouseholdVersion, now);
        return new HouseholdMutationResponse(
                command.operationType(),
                false,
                policy.actorHasHousehold,
                resultHouseholdVersion);
    }

    private LockedTerminationContext lockAndValidateContext(HouseholdOperationCommand command) {
        DinnerHouseholdMemberEntity candidate =
                memberMapper.selectActiveByUserId(command.actorUserId());
        if (!isCandidate(candidate, command.actorUserId())) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED);
        }

        DinnerHouseholdEntity household =
                householdMapper.selectByIdForUpdate(candidate.getHouseholdId());
        if (!isActiveHousehold(household, candidate.getHouseholdId())) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED);
        }
        List<DinnerHouseholdMemberEntity> members =
                memberMapper.selectActiveByHouseholdIdForUpdate(household.getId());
        DinnerHouseholdMemberEntity actorMembership = validateMembershipSet(
                members, household.getId(), command.actorUserId());
        if (!sameSnapshot(candidate, actorMembership)) {
            throw householdVersionConflict();
        }
        if (!Objects.equals(command.actorMembershipId(), actorMembership.getId())) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);
        }
        if (!Objects.equals(command.expectedHouseholdVersion(), household.getVersion())) {
            throw householdVersionConflict();
        }

        TerminationPolicy policy = TerminationPolicy.from(command.operationType());
        DinnerHouseholdMemberEntity target;
        if (policy == TerminationPolicy.SELF_LEFT) {
            if (OWNER.equals(actorMembership.getRole())) {
                throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_OWNER_CANNOT_LEAVE);
            }
            if (!MEMBER.equals(actorMembership.getRole())) {
                throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);
            }
            target = actorMembership;
        } else {
            if (!OWNER.equals(actorMembership.getRole())) {
                throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_OWNER_REQUIRED);
            }
            if (Objects.equals(command.targetMembershipId(), actorMembership.getId())) {
                throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);
            }
            target = members.stream()
                    .filter(member -> Objects.equals(
                            command.targetMembershipId(), member.getId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.DINNER_HOUSEHOLD_MEMBER_NOT_FOUND));
            if (!MEMBER.equals(target.getRole())
                    || !Objects.equals(
                            command.targetMembershipVersion(), target.getVersion())) {
                throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT);
            }
        }
        return new LockedTerminationContext(household, target, policy);
    }

    private List<DinnerInviteCodeEntity> lockOpenInvites(Long householdId) {
        List<DinnerInviteCodeEntity> invites =
                inviteMapper.selectAllOpenByHouseholdIdForUpdate(householdId);
        if (invites == null) {
            throw householdVersionConflict();
        }
        Long previousId = null;
        for (DinnerInviteCodeEntity invite : invites) {
            if (invite == null
                    || invite.getId() == null
                    || !Objects.equals(householdId, invite.getHouseholdId())
                    || invite.getConsumedAt() != null
                    || invite.getRevokedAt() != null
                    || (previousId != null && invite.getId() <= previousId)) {
                throw householdVersionConflict();
            }
            previousId = invite.getId();
        }
        return List.copyOf(invites);
    }

    private List<DinnerMenuEntity> lockUncompletedMenus(Long householdId) {
        List<DinnerMenuEntity> menus =
                menuMapper.selectUncompletedByHouseholdIdForUpdate(householdId);
        if (menus == null) {
            throw householdVersionConflict();
        }
        Long previousId = null;
        for (DinnerMenuEntity menu : menus) {
            if (menu == null
                    || menu.getId() == null
                    || !Objects.equals(householdId, menu.getHouseholdId())
                    || !("DRAFT".equals(menu.getStatus())
                    || "CONFIRMED".equals(menu.getStatus()))
                    || menu.getVersion() == null
                    || menu.getVersion() < 0
                    || (previousId != null && menu.getId() <= previousId)) {
                throw householdVersionConflict();
            }
            previousId = menu.getId();
        }
        return List.copyOf(menus);
    }

    private List<DinnerMenuSelectionEntity> lockSelections(List<Long> menuIds) {
        if (menuIds.isEmpty()) {
            return List.of();
        }
        List<DinnerMenuSelectionEntity> selections =
                selectionMapper.selectByMenuIdsForUpdate(menuIds);
        if (selections == null) {
            throw householdVersionConflict();
        }
        Set<Long> allowedMenuIds = Set.copyOf(menuIds);
        Long previousMenuId = null;
        Long previousId = null;
        for (DinnerMenuSelectionEntity selection : selections) {
            if (selection == null
                    || selection.getId() == null
                    || selection.getMenuId() == null
                    || selection.getUserId() == null
                    || selection.getRecipeId() == null
                    || !allowedMenuIds.contains(selection.getMenuId())
                    || (previousMenuId != null
                    && (selection.getMenuId() < previousMenuId
                    || (selection.getMenuId().equals(previousMenuId)
                    && selection.getId() <= previousId)))) {
                throw householdVersionConflict();
            }
            previousMenuId = selection.getMenuId();
            previousId = selection.getId();
        }
        return List.copyOf(selections);
    }

    private void validateInventory(
            Long householdId,
            List<DinnerHouseholdInventoryEntity> inventory
    ) {
        if (inventory == null) {
            throw householdVersionConflict();
        }
        Long previousId = null;
        for (DinnerHouseholdInventoryEntity item : inventory) {
            if (item == null
                    || item.getId() == null
                    || !Objects.equals(householdId, item.getHouseholdId())
                    || (previousId != null && item.getId() <= previousId)) {
                throw householdVersionConflict();
            }
            previousId = item.getId();
        }
    }

    private void revokeOpenInvites(
            List<DinnerInviteCodeEntity> invites,
            Long householdId,
            LocalDateTime now
    ) {
        for (DinnerInviteCodeEntity invite : invites) {
            if (inviteMapper.revokeOpenInvite(
                    invite.getId(), householdId, now, "MEMBERSHIP_CHANGED") != 1) {
                throw householdVersionConflict();
            }
        }
    }

    private void resetMenus(
            List<DinnerMenuEntity> menus,
            List<DinnerMenuSelectionEntity> selections,
            List<Long> menuIds,
            Long householdId,
            Long targetUserId
    ) {
        if (menuIds.isEmpty()) {
            return;
        }
        long targetSelectionCount = selections.stream()
                .filter(selection -> Objects.equals(targetUserId, selection.getUserId()))
                .count();
        if (selectionMapper.deleteByMenuIdsAndUserId(menuIds, targetUserId)
                != targetSelectionCount) {
            throw householdVersionConflict();
        }
        if (menuMapper.resetUncompletedMenus(householdId, menuIds) != menus.size()) {
            throw householdVersionConflict();
        }
    }

    private DinnerHouseholdMemberEntity validateMembershipSet(
            List<DinnerHouseholdMemberEntity> members,
            Long householdId,
            Long actorUserId
    ) {
        if (members == null || members.isEmpty() || members.size() > 2) {
            throw householdVersionConflict();
        }
        Long previousId = null;
        Set<Integer> seats = new HashSet<>();
        int ownerCount = 0;
        DinnerHouseholdMemberEntity actor = null;
        for (DinnerHouseholdMemberEntity member : members) {
            if (!isCompleteActiveMember(member, householdId)
                    || (previousId != null && member.getId() <= previousId)
                    || !seats.add(member.getSeatNo())) {
                throw householdVersionConflict();
            }
            if (OWNER.equals(member.getRole())) {
                ownerCount++;
            }
            if (Objects.equals(actorUserId, member.getUserId())) {
                if (actor != null) {
                    throw householdVersionConflict();
                }
                actor = member;
            }
            previousId = member.getId();
        }
        if (ownerCount != 1) {
            throw householdVersionConflict();
        }
        if (actor == null) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED);
        }
        return actor;
    }

    private boolean isCandidate(DinnerHouseholdMemberEntity candidate, Long actorUserId) {
        return candidate != null
                && candidate.getId() != null
                && candidate.getHouseholdId() != null
                && Objects.equals(actorUserId, candidate.getUserId())
                && ACTIVE.equals(candidate.getStatus());
    }

    private boolean isCompleteActiveMember(
            DinnerHouseholdMemberEntity member,
            Long householdId
    ) {
        return member != null
                && member.getId() != null
                && Objects.equals(householdId, member.getHouseholdId())
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

    private boolean isActiveHousehold(DinnerHouseholdEntity household, Long householdId) {
        return household != null
                && Objects.equals(householdId, household.getId())
                && ACTIVE.equals(household.getStatus())
                && household.getVersion() != null
                && household.getVersion() >= 1
                && household.getInviteRevision() != null
                && household.getInviteRevision() >= 0
                && StringUtils.hasText(household.getTimezone());
    }

    private boolean sameSnapshot(
            DinnerHouseholdMemberEntity candidate,
            DinnerHouseholdMemberEntity locked
    ) {
        return Objects.equals(candidate.getId(), locked.getId())
                && Objects.equals(candidate.getHouseholdId(), locked.getHouseholdId())
                && Objects.equals(candidate.getUserId(), locked.getUserId())
                && Objects.equals(candidate.getRole(), locked.getRole())
                && Objects.equals(candidate.getStatus(), locked.getStatus())
                && Objects.equals(candidate.getSeatNo(), locked.getSeatNo())
                && Objects.equals(candidate.getVersion(), locked.getVersion())
                && Objects.equals(
                        candidate.getHistoryVisibleFrom(), locked.getHistoryVisibleFrom());
    }

    private boolean isActiveActor(UserEntity actor, Long actorUserId) {
        return actor != null
                && Objects.equals(actorUserId, actor.getId())
                && ACTIVE.equals(actor.getStatus())
                && actor.getDeletedAt() == null;
    }

    private void persistResult(
            HouseholdOperationCommand command,
            Long householdId,
            TerminationPolicy policy,
            Long resultHouseholdVersion,
            LocalDateTime now
    ) {
        DinnerHouseholdOperationEntity operation = new DinnerHouseholdOperationEntity();
        operation.setHouseholdId(householdId);
        operation.setActorId(command.actorUserId());
        operation.setActorMembershipId(command.actorMembershipId());
        operation.setTargetMemberId(command.targetMembershipId());
        operation.setOperationType(command.operationType());
        operation.setIdempotencyKey(command.idempotencyKey());
        operation.setRequestFingerprint(command.fingerprint());
        operation.setResultSchemaVersion(1);
        operation.setResultHouseholdVersion(resultHouseholdVersion);
        operation.setResultPayload(policy.actorHasHousehold
                ? "{\"actorHasHousehold\":true}"
                : "{\"actorHasHousehold\":false}");
        operation.setCreatedAt(now);
        operation.setExpiresAt(now.plusDays(14));
        if (operationMapper.insert(operation) != 1) {
            throw new IllegalStateException("Household operation result was not stored");
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
    }

    private BusinessException householdVersionConflict() {
        return new BusinessException(ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT);
    }

    private enum TerminationPolicy {
        SELF_LEFT("LEFT", "SELF_LEFT", false),
        OWNER_REMOVED("REMOVED", "OWNER_REMOVED", true);

        private final String endedStatus;
        private final String endReason;
        private final boolean actorHasHousehold;

        TerminationPolicy(
                String endedStatus,
                String endReason,
                boolean actorHasHousehold
        ) {
            this.endedStatus = endedStatus;
            this.endReason = endReason;
            this.actorHasHousehold = actorHasHousehold;
        }

        private static TerminationPolicy from(String operationType) {
            return switch (operationType) {
                case DinnerHouseholdOperationService.MEMBER_LEAVE -> SELF_LEFT;
                case DinnerHouseholdOperationService.OWNER_REMOVE -> OWNER_REMOVED;
                default -> throw new IllegalArgumentException(
                        "Unsupported membership termination operation");
            };
        }
    }

    private record LockedTerminationContext(
            DinnerHouseholdEntity household,
            DinnerHouseholdMemberEntity target,
            TerminationPolicy policy
    ) {
    }
}
