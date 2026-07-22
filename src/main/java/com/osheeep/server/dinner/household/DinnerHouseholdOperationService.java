package com.osheeep.server.dinner.household;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.dto.HouseholdMutationResponse;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class DinnerHouseholdOperationService {

    static final String MEMBER_LEAVE = "MEMBER_LEAVE";
    static final String OWNER_REMOVE = "OWNER_REMOVE";
    private static final String OPERATION_KEY_CONSTRAINT =
            "uk_dinner_household_operations_actor_key";
    private static final Logger LOGGER =
            LoggerFactory.getLogger(DinnerHouseholdOperationService.class);

    private final DinnerHouseholdOperationMapper operationMapper;
    private final HouseholdOperationFingerprinter fingerprinter;
    private final DinnerHouseholdOperationRetentionService retentionService;
    private final DinnerMembershipTerminationService terminationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public DinnerHouseholdOperationService(
            DinnerHouseholdOperationMapper operationMapper,
            HouseholdOperationFingerprinter fingerprinter,
            DinnerHouseholdOperationRetentionService retentionService,
            DinnerMembershipTerminationService terminationService,
            ObjectMapper objectMapper
    ) {
        this(
                operationMapper,
                fingerprinter,
                retentionService,
                terminationService,
                objectMapper,
                Clock.systemUTC());
    }

    DinnerHouseholdOperationService(
            DinnerHouseholdOperationMapper operationMapper,
            HouseholdOperationFingerprinter fingerprinter,
            DinnerHouseholdOperationRetentionService retentionService,
            DinnerMembershipTerminationService terminationService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.operationMapper = operationMapper;
        this.fingerprinter = fingerprinter;
        this.retentionService = retentionService;
        this.terminationService = terminationService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public HouseholdMutationResponse leave(
            Long actorUserId,
            Long actorMembershipId,
            Long expectedHouseholdVersion,
            String idempotencyKey
    ) {
        return execute(command(
                actorUserId,
                actorMembershipId,
                expectedHouseholdVersion,
                null,
                null,
                MEMBER_LEAVE,
                idempotencyKey));
    }

    public HouseholdMutationResponse remove(
            Long actorUserId,
            Long actorMembershipId,
            Long expectedHouseholdVersion,
            Long targetMembershipId,
            Long targetMembershipVersion,
            String idempotencyKey
    ) {
        return execute(command(
                actorUserId,
                actorMembershipId,
                expectedHouseholdVersion,
                targetMembershipId,
                targetMembershipVersion,
                OWNER_REMOVE,
                idempotencyKey));
    }

    private HouseholdMutationResponse execute(HouseholdOperationCommand command) {
        cleanupExpiredBestEffort();
        DinnerHouseholdOperationEntity existing =
                operationMapper.selectByActorAndIdempotencyKey(
                        command.actorUserId(), command.idempotencyKey());
        if (existing != null && !isExpired(existing, utcNow())) {
            return replay(command, existing, objectMapper);
        }

        try {
            return terminationService.terminate(command);
        } catch (DuplicateKeyException exception) {
            if (!causedByOperationKey(exception)) {
                throw exception;
            }
            DinnerHouseholdOperationEntity winner =
                    operationMapper.selectByActorAndIdempotencyKey(
                            command.actorUserId(), command.idempotencyKey());
            if (winner == null || isExpired(winner, utcNow())) {
                throw exception;
            }
            return replay(command, winner, objectMapper);
        }
    }

    private HouseholdOperationCommand command(
            Long actorUserId,
            Long actorMembershipId,
            Long expectedHouseholdVersion,
            Long targetMembershipId,
            Long targetMembershipVersion,
            String operationType,
            String idempotencyKey
    ) {
        if (actorUserId == null || actorUserId < 1) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            String normalizedKey = fingerprinter.normalizeIdempotencyKey(idempotencyKey);
            String fingerprint = fingerprinter.fingerprint(
                    operationType,
                    actorMembershipId,
                    expectedHouseholdVersion,
                    targetMembershipId,
                    targetMembershipVersion,
                    null);
            return new HouseholdOperationCommand(
                    actorUserId,
                    actorMembershipId,
                    expectedHouseholdVersion,
                    targetMembershipId,
                    targetMembershipVersion,
                    operationType,
                    normalizedKey,
                    fingerprint);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    ErrorCode.VALIDATION_ERROR.defaultMessage());
        }
    }

    private void cleanupExpiredBestEffort() {
        try {
            retentionService.cleanupExpiredBatch();
        } catch (RuntimeException exception) {
            LOGGER.warn("Household operation retention cleanup failed");
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
    }

    static boolean isExpired(
            DinnerHouseholdOperationEntity operation,
            LocalDateTime now
    ) {
        if (operation.getExpiresAt() == null || now == null) {
            throw new IllegalStateException("Household operation expiry is invalid");
        }
        return !operation.getExpiresAt().isAfter(now);
    }

    static HouseholdMutationResponse replay(
            HouseholdOperationCommand command,
            DinnerHouseholdOperationEntity operation,
            ObjectMapper objectMapper
    ) {
        if (!Objects.equals(command.operationType(), operation.getOperationType())
                || !Objects.equals(
                        command.actorMembershipId(), operation.getActorMembershipId())
                || !Objects.equals(command.targetMembershipId(), operation.getTargetMemberId())
                || !Objects.equals(command.fingerprint(), operation.getRequestFingerprint())) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_OPERATION_CONFLICT);
        }
        if (!Integer.valueOf(1).equals(operation.getResultSchemaVersion())
                || !isValidResultHouseholdVersion(operation)) {
            throw new IllegalStateException("Household operation result schema is invalid");
        }

        boolean actorHasHousehold = parseActorHasHousehold(
                operation.getResultPayload(), objectMapper);
        boolean expectedActorHasHousehold = switch (operation.getOperationType()) {
            case MEMBER_LEAVE, "HOUSEHOLD_DISSOLUTION" -> false;
            case OWNER_REMOVE, "OWNERSHIP_TRANSFER" -> true;
            default -> throw new IllegalStateException("Household operation type is invalid");
        };
        if (actorHasHousehold != expectedActorHasHousehold) {
            throw new IllegalStateException("Household operation result is inconsistent");
        }
        return new HouseholdMutationResponse(
                operation.getOperationType(),
                true,
                actorHasHousehold,
                operation.getResultHouseholdVersion());
    }

    private static boolean isValidResultHouseholdVersion(
            DinnerHouseholdOperationEntity operation
    ) {
        Long version = operation.getResultHouseholdVersion();
        if ("HOUSEHOLD_DISSOLUTION".equals(operation.getOperationType())) {
            return version == null;
        }
        return version != null && version >= 1;
    }

    private static boolean parseActorHasHousehold(
            String payload,
            ObjectMapper objectMapper
    ) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null
                    || !root.isObject()
                    || root.size() != 1
                    || !root.has("actorHasHousehold")
                    || !root.get("actorHasHousehold").isBoolean()) {
                throw new IllegalStateException("Household operation result payload is invalid");
            }
            return root.get("actorHasHousehold").booleanValue();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Household operation result payload is invalid", exception);
        }
    }

    private boolean causedByOperationKey(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT).contains(OPERATION_KEY_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    record HouseholdOperationCommand(
            Long actorUserId,
            Long actorMembershipId,
            Long expectedHouseholdVersion,
            Long targetMembershipId,
            Long targetMembershipVersion,
            String operationType,
            String idempotencyKey,
            String fingerprint
    ) {
    }
}
