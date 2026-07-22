package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdOperationService.HouseholdOperationCommand;
import com.osheeep.server.dinner.household.dto.HouseholdMutationResponse;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class DinnerHouseholdOperationServiceTest {

    private static final Long ACTOR_ID = 7L;
    private static final Long ACTOR_MEMBERSHIP_ID = 41L;
    private static final Long HOUSEHOLD_VERSION = 8L;
    private static final Long TARGET_MEMBERSHIP_ID = 73L;
    private static final Long TARGET_MEMBERSHIP_VERSION = 3L;
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final Instant NOW = Instant.parse("2026-07-22T12:34:56.789Z");
    private static final LocalDateTime UTC_NOW =
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock private DinnerHouseholdOperationMapper operationMapper;
    @Mock private DinnerHouseholdOperationRetentionService retentionService;
    @Mock private DinnerMembershipTerminationService terminationService;

    private final HouseholdOperationFingerprinter fingerprinter =
            new HouseholdOperationFingerprinter("test-secret-at-least-32-characters");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DinnerHouseholdOperationService service;

    @BeforeEach
    void setUp() {
        service = new DinnerHouseholdOperationService(
                operationMapper,
                fingerprinter,
                retentionService,
                terminationService,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "not-a-uuid",
            "550e8400-e29b-11d4-a716-446655440000",
            "550e8400-e29b-41d4-7716-446655440000",
            " 550e8400-e29b-41d4-a716-446655440000 "
    })
    void malformedOrNonV4IdempotencyKeyIsRejectedBeforeCleanupOrDatabaseWork(
            String idempotencyKey
    ) {
        assertBusinessError(
                () -> service.leave(
                        ACTOR_ID,
                        ACTOR_MEMBERSHIP_ID,
                        HOUSEHOLD_VERSION,
                        idempotencyKey),
                ErrorCode.VALIDATION_ERROR);

        verifyNoInteractions(operationMapper, retentionService, terminationService);
    }

    @Test
    void firstRequestMissDelegatesNormalizedSemanticCommandToTermination() {
        String uppercaseKey = "550E8400-E29B-41D4-A716-446655440000";
        HouseholdMutationResponse fresh = freshLeaveResponse();
        when(operationMapper.selectByActorAndIdempotencyKey(ACTOR_ID, KEY))
                .thenReturn(null);
        when(terminationService.terminate(any(HouseholdOperationCommand.class)))
                .thenReturn(fresh);

        assertThat(service.leave(
                ACTOR_ID,
                ACTOR_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                uppercaseKey)).isSameAs(fresh);

        ArgumentCaptor<HouseholdOperationCommand> commandCaptor =
                ArgumentCaptor.forClass(HouseholdOperationCommand.class);
        verify(terminationService).terminate(commandCaptor.capture());
        HouseholdOperationCommand command = commandCaptor.getValue();
        assertThat(command.actorUserId()).isEqualTo(ACTOR_ID);
        assertThat(command.actorMembershipId()).isEqualTo(ACTOR_MEMBERSHIP_ID);
        assertThat(command.expectedHouseholdVersion()).isEqualTo(HOUSEHOLD_VERSION);
        assertThat(command.targetMembershipId()).isNull();
        assertThat(command.targetMembershipVersion()).isNull();
        assertThat(command.operationType()).isEqualTo("MEMBER_LEAVE");
        assertThat(command.idempotencyKey()).isEqualTo(KEY);
        assertThat(command.fingerprint())
                .isEqualTo(leaveFingerprint(HOUSEHOLD_VERSION))
                .matches("[0-9a-f]{64}");
        verify(retentionService).cleanupExpiredBatch();
    }

    @Test
    void sameKeyAndSameSemanticRequestReplaysStoredResultWithoutTermination() {
        DinnerHouseholdOperationEntity existing = validLeaveOperation();
        when(operationMapper.selectByActorAndIdempotencyKey(ACTOR_ID, KEY))
                .thenReturn(existing);

        HouseholdMutationResponse result = service.leave(
                ACTOR_ID,
                ACTOR_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                KEY);

        assertThat(result.operationType()).isEqualTo("MEMBER_LEAVE");
        assertThat(result.replayed()).isTrue();
        assertThat(result.actorHasHousehold()).isFalse();
        assertThat(result.householdVersion()).isEqualTo(9L);
        verify(terminationService, never()).terminate(any());
    }

    @Test
    void sameKeyWithDifferentSemanticRequestIsAConflictWithoutTermination() {
        DinnerHouseholdOperationEntity existing = validLeaveOperation();
        existing.setRequestFingerprint(leaveFingerprint(HOUSEHOLD_VERSION + 1));
        when(operationMapper.selectByActorAndIdempotencyKey(ACTOR_ID, KEY))
                .thenReturn(existing);

        assertBusinessError(
                () -> service.leave(
                        ACTOR_ID,
                        ACTOR_MEMBERSHIP_ID,
                        HOUSEHOLD_VERSION,
                        KEY),
                ErrorCode.DINNER_HOUSEHOLD_OPERATION_CONFLICT);

        verify(terminationService, never()).terminate(any());
    }

    @Test
    void resultExpiringExactlyNowDoesNotReplayAndEntersTermination() {
        DinnerHouseholdOperationEntity expired = validLeaveOperation();
        expired.setExpiresAt(UTC_NOW);
        HouseholdMutationResponse fresh = freshLeaveResponse();
        when(operationMapper.selectByActorAndIdempotencyKey(ACTOR_ID, KEY))
                .thenReturn(expired);
        when(terminationService.terminate(any(HouseholdOperationCommand.class)))
                .thenReturn(fresh);

        assertThat(service.leave(
                ACTOR_ID,
                ACTOR_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                KEY)).isSameAs(fresh);

        verify(terminationService).terminate(any(HouseholdOperationCommand.class));
    }

    @Test
    void knownOperationKeyRaceRequeriesAndReplaysTheWinner() {
        DinnerHouseholdOperationEntity winner = validLeaveOperation();
        DuplicateKeyException duplicate = operationKeyDuplicate();
        when(operationMapper.selectByActorAndIdempotencyKey(ACTOR_ID, KEY))
                .thenReturn(null, winner);
        when(terminationService.terminate(any(HouseholdOperationCommand.class)))
                .thenThrow(duplicate);

        HouseholdMutationResponse result = service.leave(
                ACTOR_ID,
                ACTOR_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.actorHasHousehold()).isFalse();
        assertThat(result.householdVersion()).isEqualTo(9L);
        verify(operationMapper, times(2))
                .selectByActorAndIdempotencyKey(ACTOR_ID, KEY);
    }

    @Test
    void knownOperationKeyRaceWithDifferentWinnerFingerprintIsAConflict() {
        DinnerHouseholdOperationEntity winner = validLeaveOperation();
        winner.setRequestFingerprint(leaveFingerprint(HOUSEHOLD_VERSION + 1));
        when(operationMapper.selectByActorAndIdempotencyKey(ACTOR_ID, KEY))
                .thenReturn(null, winner);
        when(terminationService.terminate(any(HouseholdOperationCommand.class)))
                .thenThrow(operationKeyDuplicate());

        assertBusinessError(
                () -> service.leave(
                        ACTOR_ID,
                        ACTOR_MEMBERSHIP_ID,
                        HOUSEHOLD_VERSION,
                        KEY),
                ErrorCode.DINNER_HOUSEHOLD_OPERATION_CONFLICT);

        verify(operationMapper, times(2))
                .selectByActorAndIdempotencyKey(ACTOR_ID, KEY);
    }

    @Test
    void unknownDuplicateConstraintIsPropagatedWithoutASecondLookup() {
        DuplicateKeyException unknown = new DuplicateKeyException(
                "Duplicate entry for key 'uk_unrelated_table'");
        when(operationMapper.selectByActorAndIdempotencyKey(ACTOR_ID, KEY))
                .thenReturn(null);
        when(terminationService.terminate(any(HouseholdOperationCommand.class)))
                .thenThrow(unknown);

        assertThatThrownBy(() -> service.leave(
                ACTOR_ID,
                ACTOR_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                KEY)).isSameAs(unknown);

        verify(operationMapper).selectByActorAndIdempotencyKey(ACTOR_ID, KEY);
    }

    @ParameterizedTest(name = "rejects corrupt replay result: {0}")
    @MethodSource("invalidReplayResults")
    void replayStrictlyRejectsInvalidSchemaVersionHouseholdVersionOrPayload(
            String description,
            Integer schemaVersion,
            Long resultHouseholdVersion,
            String payload
    ) {
        DinnerHouseholdOperationEntity existing = validLeaveOperation();
        existing.setResultSchemaVersion(schemaVersion);
        existing.setResultHouseholdVersion(resultHouseholdVersion);
        existing.setResultPayload(payload);
        when(operationMapper.selectByActorAndIdempotencyKey(ACTOR_ID, KEY))
                .thenReturn(existing);

        assertThatThrownBy(() -> service.leave(
                ACTOR_ID,
                ACTOR_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                KEY)).isInstanceOf(IllegalStateException.class);

        verify(terminationService, never()).terminate(any());
    }

    @Test
    void validRemovalReplayRequiresAndReturnsActorStillInHousehold() {
        DinnerHouseholdOperationEntity existing = operation(
                "OWNER_REMOVE",
                ACTOR_MEMBERSHIP_ID,
                TARGET_MEMBERSHIP_ID,
                fingerprinter.fingerprint(
                        "OWNER_REMOVE",
                        ACTOR_MEMBERSHIP_ID,
                        HOUSEHOLD_VERSION,
                        TARGET_MEMBERSHIP_ID,
                        TARGET_MEMBERSHIP_VERSION,
                        null),
                1,
                9L,
                "{\"actorHasHousehold\":true}",
                UTC_NOW.plusDays(1));
        when(operationMapper.selectByActorAndIdempotencyKey(ACTOR_ID, KEY))
                .thenReturn(existing);

        HouseholdMutationResponse result = service.remove(
                ACTOR_ID,
                ACTOR_MEMBERSHIP_ID,
                HOUSEHOLD_VERSION,
                TARGET_MEMBERSHIP_ID,
                TARGET_MEMBERSHIP_VERSION,
                KEY);

        assertThat(result.operationType()).isEqualTo("OWNER_REMOVE");
        assertThat(result.replayed()).isTrue();
        assertThat(result.actorHasHousehold()).isTrue();
        assertThat(result.householdVersion()).isEqualTo(9L);
        verify(terminationService, never()).terminate(any());
    }

    @Test
    void concurrentRequestsCanBothPassInitialPrecheckAndDuplicateLoserReplaysWinner()
            throws Exception {
        DinnerHouseholdOperationEntity winner = validLeaveOperation();
        CountDownLatch bothPrechecks = new CountDownLatch(2);
        CountDownLatch bothTerminations = new CountDownLatch(2);
        AtomicInteger lookupCount = new AtomicInteger();
        AtomicInteger terminationCount = new AtomicInteger();

        when(operationMapper.selectByActorAndIdempotencyKey(ACTOR_ID, KEY))
                .thenAnswer(invocation -> {
                    int lookup = lookupCount.incrementAndGet();
                    if (lookup <= 2) {
                        bothPrechecks.countDown();
                        assertThat(bothPrechecks.await(5, TimeUnit.SECONDS)).isTrue();
                        return null;
                    }
                    return winner;
                });
        when(terminationService.terminate(any(HouseholdOperationCommand.class)))
                .thenAnswer(invocation -> {
                    int termination = terminationCount.incrementAndGet();
                    bothTerminations.countDown();
                    assertThat(bothTerminations.await(5, TimeUnit.SECONDS)).isTrue();
                    if (termination == 1) {
                        return freshLeaveResponse();
                    }
                    throw operationKeyDuplicate();
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HouseholdMutationResponse> first = executor.submit(() -> service.leave(
                    ACTOR_ID,
                    ACTOR_MEMBERSHIP_ID,
                    HOUSEHOLD_VERSION,
                    KEY));
            Future<HouseholdMutationResponse> second = executor.submit(() -> service.leave(
                    ACTOR_ID,
                    ACTOR_MEMBERSHIP_ID,
                    HOUSEHOLD_VERSION,
                    KEY));

            List<HouseholdMutationResponse> results =
                    List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

            assertThat(results).extracting(HouseholdMutationResponse::replayed)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(results).allSatisfy(result -> {
                assertThat(result.operationType()).isEqualTo("MEMBER_LEAVE");
                assertThat(result.actorHasHousehold()).isFalse();
                assertThat(result.householdVersion()).isEqualTo(9L);
            });
            assertThat(lookupCount).hasValue(3);
            assertThat(terminationCount).hasValue(2);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Stream<Arguments> invalidReplayResults() {
        return Stream.of(
                Arguments.of("missing schema version", null, 9L,
                        "{\"actorHasHousehold\":false}"),
                Arguments.of("unknown schema version", 2, 9L,
                        "{\"actorHasHousehold\":false}"),
                Arguments.of("missing household version", 1, null,
                        "{\"actorHasHousehold\":false}"),
                Arguments.of("non-positive household version", 1, 0L,
                        "{\"actorHasHousehold\":false}"),
                Arguments.of("missing payload", 1, 9L, null),
                Arguments.of("JSON null", 1, 9L, "null"),
                Arguments.of("array payload", 1, 9L, "[]"),
                Arguments.of("missing field", 1, 9L, "{}"),
                Arguments.of("wrong field type", 1, 9L,
                        "{\"actorHasHousehold\":\"false\"}"),
                Arguments.of("extra field", 1, 9L,
                        "{\"actorHasHousehold\":false,\"extra\":true}"),
                Arguments.of("malformed JSON", 1, 9L, "{"),
                Arguments.of("operation-inconsistent value", 1, 9L,
                        "{\"actorHasHousehold\":true}"));
    }

    private DinnerHouseholdOperationEntity validLeaveOperation() {
        return operation(
                "MEMBER_LEAVE",
                ACTOR_MEMBERSHIP_ID,
                null,
                leaveFingerprint(HOUSEHOLD_VERSION),
                1,
                9L,
                "{\"actorHasHousehold\":false}",
                UTC_NOW.plusDays(1));
    }

    private DinnerHouseholdOperationEntity operation(
            String operationType,
            Long actorMembershipId,
            Long targetMembershipId,
            String fingerprint,
            Integer schemaVersion,
            Long resultHouseholdVersion,
            String payload,
            LocalDateTime expiresAt
    ) {
        DinnerHouseholdOperationEntity operation = new DinnerHouseholdOperationEntity();
        operation.setId(101L);
        operation.setHouseholdId(11L);
        operation.setActorId(ACTOR_ID);
        operation.setActorMembershipId(actorMembershipId);
        operation.setTargetMemberId(targetMembershipId);
        operation.setOperationType(operationType);
        operation.setIdempotencyKey(KEY);
        operation.setRequestFingerprint(fingerprint);
        operation.setResultSchemaVersion(schemaVersion);
        operation.setResultHouseholdVersion(resultHouseholdVersion);
        operation.setResultPayload(payload);
        operation.setCreatedAt(UTC_NOW.minusDays(1));
        operation.setExpiresAt(expiresAt);
        return operation;
    }

    private String leaveFingerprint(Long expectedHouseholdVersion) {
        return fingerprinter.fingerprint(
                "MEMBER_LEAVE",
                ACTOR_MEMBERSHIP_ID,
                expectedHouseholdVersion,
                null,
                null,
                null);
    }

    private HouseholdMutationResponse freshLeaveResponse() {
        return new HouseholdMutationResponse("MEMBER_LEAVE", false, false, 9L);
    }

    private DuplicateKeyException operationKeyDuplicate() {
        return new DuplicateKeyException(
                "insert household operation failed",
                new IllegalStateException(
                        "Duplicate entry for key "
                                + "'uk_dinner_household_operations_actor_key'"));
    }

    private void assertBusinessError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(errorCode));
    }
}
