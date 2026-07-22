package com.osheeep.server.dinner.household;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.dinner.household.dto.HouseholdCreatedResponse;
import com.osheeep.server.dinner.household.dto.HouseholdInviteStatusResponse;
import com.osheeep.server.dinner.household.dto.HouseholdManagementResponse;
import com.osheeep.server.dinner.household.dto.HouseholdMemberResponse;
import com.osheeep.server.dinner.household.dto.HouseholdMutationResponse;
import com.osheeep.server.dinner.household.dto.HouseholdResponse;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestUserMapperConfig.class)
class DinnerHouseholdControllerTest {

    private static final Instant JOINED_AT = Instant.parse("2026-07-11T06:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-07-12T06:00:00Z");
    private static final String LEAVE_KEY = "00000000-0000-4000-8000-000000000031";
    private static final String REMOVAL_KEY = "00000000-0000-4000-8000-000000000032";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;

    @MockitoBean
    private DinnerHouseholdService householdService;

    @MockitoBean
    private DinnerHouseholdOperationService householdOperationService;

    private String token;

    @BeforeEach
    void setUp() {
        reset(householdService, householdOperationService);
        token = jwtService.generateToken(new CurrentUser(7L, "wx_user"));
    }

    @ParameterizedTest
    @MethodSource("protectedHouseholdEndpoints")
    void householdEndpointsRequireAuthentication(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void currentHouseholdReturnsExplicitNullDataWhenUserIsUnbound() throws Exception {
        when(householdService.current(7L)).thenReturn(null);

        mockMvc.perform(authenticated(get("/api/dinner/household")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void currentHouseholdReturnsRoleAndVersionFieldsWithoutUserProfileData() throws Exception {
        when(householdService.current(7L)).thenReturn(household(2, 7L, 4L, "OWNER", 31L, 3L));

        mockMvc.perform(authenticated(get("/api/dinner/household")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.name").value("我们的小家"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.memberCount").value(2))
                .andExpect(jsonPath("$.data.version").value(7))
                .andExpect(jsonPath("$.data.inviteRevision").value(4))
                .andExpect(jsonPath("$.data.myRole").value("OWNER"))
                .andExpect(jsonPath("$.data.myMembershipId").value(31))
                .andExpect(jsonPath("$.data.myMembershipVersion").value(3))
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.createdBy").doesNotExist())
                .andExpect(jsonPath("$.data.displayName").doesNotExist())
                .andExpect(jsonPath("$.data.nickname").doesNotExist())
                .andExpect(jsonPath("$.data.avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.data.inviteCode").doesNotExist())
                .andExpect(jsonPath("$.data.codeHash").doesNotExist());
    }

    @Test
    void managementReturnsOneSnapshotWithRelationshipLabelsAndNoInternalUserData() throws Exception {
        HouseholdManagementResponse response = new HouseholdManagementResponse(
                household(2, 7L, 4L, "OWNER", 31L, 3L),
                List.of(
                        new HouseholdMemberResponse(31L, 3L, "OWNER", "ME", JOINED_AT),
                        new HouseholdMemberResponse(
                                32L, 2L, "MEMBER", "PARTNER", Instant.parse("2026-07-12T08:30:00Z"))),
                new HouseholdInviteStatusResponse("ACTIVE", 4L, EXPIRES_AT, true));
        when(householdService.management(7L)).thenReturn(response);

        mockMvc.perform(authenticated(get("/api/dinner/household/members")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.household.version").value(7))
                .andExpect(jsonPath("$.data.household.inviteRevision").value(4))
                .andExpect(jsonPath("$.data.members.length()").value(2))
                .andExpect(jsonPath("$.data.members[0].membershipId").value(31))
                .andExpect(jsonPath("$.data.members[0].version").value(3))
                .andExpect(jsonPath("$.data.members[0].role").value("OWNER"))
                .andExpect(jsonPath("$.data.members[0].relation").value("ME"))
                .andExpect(jsonPath("$.data.members[0].joinedAt").value("2026-07-11T06:00:00Z"))
                .andExpect(jsonPath("$.data.members[1].relation").value("PARTNER"))
                .andExpect(jsonPath("$.data.invite.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.invite.inviteRevision").value(4))
                .andExpect(jsonPath("$.data.invite.expiresAt").value("2026-07-12T06:00:00Z"))
                .andExpect(jsonPath("$.data.invite.createdByMe").value(true))
                .andExpect(jsonPath("$.data.members[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data.members[0].displayName").doesNotExist())
                .andExpect(jsonPath("$.data.members[0].nickname").doesNotExist())
                .andExpect(jsonPath("$.data.members[0].avatarUrl").doesNotExist())
                .andExpect(jsonPath("$.data.invite.inviteCode").doesNotExist())
                .andExpect(jsonPath("$.data.invite.codeHash").doesNotExist());
    }

    @Test
    void managementMapsMissingHouseholdToRequiredConflict() throws Exception {
        when(householdService.management(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        mockMvc.perform(authenticated(get("/api/dinner/household/members")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DINNER_HOUSEHOLD_REQUIRED"));
    }

    @Test
    void renameForwardsExpectedVersionAndReturnsAdvancedHouseholdVersion() throws Exception {
        when(householdService.rename(7L, "暖暖的小家", 7L))
                .thenReturn(household("暖暖的小家", 2, 8L, 4L, "MEMBER", 32L, 2L));

        mockMvc.perform(authenticated(put("/api/dinner/household"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"暖暖的小家\",\"expectedVersion\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("暖暖的小家"))
                .andExpect(jsonPath("$.data.version").value(8))
                .andExpect(jsonPath("$.data.inviteRevision").value(4))
                .andExpect(jsonPath("$.data.myRole").value("MEMBER"));

        verify(householdService).rename(7L, "暖暖的小家", 7L);
    }

    @Test
    void renameMapsVersionConflict() throws Exception {
        when(householdService.rename(7L, "暖暖的小家", 6L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT));

        mockMvc.perform(authenticated(put("/api/dinner/household"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"暖暖的小家\",\"expectedVersion\":6}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DINNER_HOUSEHOLD_VERSION_CONFLICT"));
    }

    @Test
    void renameMapsContentRejection() throws Exception {
        when(householdService.rename(7L, "不安全名称", 7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_NAME_REJECTED));

        mockMvc.perform(authenticated(put("/api/dinner/household"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"不安全名称\",\"expectedVersion\":7}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("DINNER_HOUSEHOLD_NAME_REJECTED"));
    }

    @Test
    void renameMapsModerationUnavailable() throws Exception {
        when(householdService.rename(7L, "暖暖的小家", 7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MODERATION_UNAVAILABLE));

        mockMvc.perform(authenticated(put("/api/dinner/household"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"暖暖的小家\",\"expectedVersion\":7}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode")
                        .value("DINNER_HOUSEHOLD_MODERATION_UNAVAILABLE"));
    }

    @ParameterizedTest
    @MethodSource("invalidRenameRequests")
    void renameRejectsInvalidRequestBodies(String body) throws Exception {
        mockMvc.perform(authenticated(put("/api/dinner/household"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(householdService);
    }

    @Test
    void inviteStatusReturnsStateAndRevisionWithoutRecoveringPlaintext() throws Exception {
        when(householdService.inviteStatus(7L))
                .thenReturn(new HouseholdInviteStatusResponse("ACTIVE", 4L, EXPIRES_AT, false));

        mockMvc.perform(authenticated(get("/api/dinner/household/invite-code")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.inviteRevision").value(4))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-07-12T06:00:00Z"))
                .andExpect(jsonPath("$.data.createdByMe").value(false))
                .andExpect(jsonPath("$.data.inviteCode").doesNotExist())
                .andExpect(jsonPath("$.data.codeHash").doesNotExist())
                .andExpect(jsonPath("$.data.createdBy").doesNotExist());
    }

    @Test
    void inviteStatusMapsMissingHouseholdToRequiredConflict() throws Exception {
        when(householdService.inviteStatus(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        mockMvc.perform(authenticated(get("/api/dinner/household/invite-code")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DINNER_HOUSEHOLD_REQUIRED"));
    }

    @Test
    void createReturnsOneTimeInviteCodeAndMatchingRevisions() throws Exception {
        HouseholdResponse household = household(1, 1L, 1L, "OWNER", 31L, 1L);
        when(householdService.create(7L, "我们的小家")).thenReturn(new HouseholdCreatedResponse(
                household, "DINNER 0123 ABYZ", 1L, EXPIRES_AT));

        mockMvc.perform(authenticated(post("/api/dinner/households"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"我们的小家\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.household.id").value(11))
                .andExpect(jsonPath("$.data.household.version").value(1))
                .andExpect(jsonPath("$.data.household.inviteRevision").value(1))
                .andExpect(jsonPath("$.data.household.myRole").value("OWNER"))
                .andExpect(jsonPath("$.data.inviteCode").value("DINNER 0123 ABYZ"))
                .andExpect(jsonPath("$.data.inviteRevision").value(1))
                .andExpect(jsonPath("$.data.inviteExpiresAt").value("2026-07-12T06:00:00Z"));
    }

    @Test
    void createMapsAlreadyBoundUserToConflict() throws Exception {
        when(householdService.create(7L, "我们的小家"))
                .thenThrow(new BusinessException(ErrorCode.DINNER_ALREADY_IN_HOUSEHOLD));

        mockMvc.perform(authenticated(post("/api/dinner/households"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"我们的小家\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DINNER_ALREADY_IN_HOUSEHOLD"));
    }

    @Test
    void createRejectsOversizedRequestBeforeCallingService() throws Exception {
        String oversizedName = "家".repeat(101);

        mockMvc.perform(authenticated(post("/api/dinner/households"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + oversizedName + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(householdService);
    }

    @Test
    void refreshReturnsPlaintextOnceWithRevisionMatchingHousehold() throws Exception {
        HouseholdResponse household = household(1, 7L, 5L, "MEMBER", 32L, 2L);
        when(householdService.refreshInvite(7L)).thenReturn(new HouseholdCreatedResponse(
                household, "DINNER 0123 ABYZ", 5L, EXPIRES_AT));

        mockMvc.perform(authenticated(post("/api/dinner/households/invite-code/refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.household.memberCount").value(1))
                .andExpect(jsonPath("$.data.household.version").value(7))
                .andExpect(jsonPath("$.data.household.inviteRevision").value(5))
                .andExpect(jsonPath("$.data.inviteCode").value("DINNER 0123 ABYZ"))
                .andExpect(jsonPath("$.data.inviteRevision").value(5))
                .andExpect(jsonPath("$.data.inviteExpiresAt").value("2026-07-12T06:00:00Z"));
    }

    @Test
    void refreshMapsFullHouseholdToConflict() throws Exception {
        when(householdService.refreshInvite(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_FULL));

        mockMvc.perform(authenticated(post("/api/dinner/households/invite-code/refresh")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DINNER_HOUSEHOLD_FULL"));
    }

    @Test
    void revocationReturnsHashOnlyNoneStateWithoutPlaintext() throws Exception {
        when(householdService.revokeInvite(7L))
                .thenReturn(new HouseholdInviteStatusResponse("NONE", 5L, null, false));

        mockMvc.perform(authenticated(post("/api/dinner/household/invite-code/revocation")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("NONE"))
                .andExpect(jsonPath("$.data.inviteRevision").value(5))
                .andExpect(jsonPath("$.data.expiresAt").value(nullValue()))
                .andExpect(jsonPath("$.data.createdByMe").value(false))
                .andExpect(jsonPath("$.data.inviteCode").doesNotExist())
                .andExpect(jsonPath("$.data.codeHash").doesNotExist());
    }

    @Test
    void revocationMapsMissingHouseholdToRequiredConflict() throws Exception {
        when(householdService.revokeInvite(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        mockMvc.perform(authenticated(post("/api/dinner/household/invite-code/revocation")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DINNER_HOUSEHOLD_REQUIRED"));
    }

    @Test
    void leaveForwardsConcurrencyAndIdempotencyInputsAndReturnsMinimalMutationResult() throws Exception {
        when(householdOperationService.leave(7L, 32L, 7L, LEAVE_KEY))
                .thenReturn(new HouseholdMutationResponse("MEMBER_LEAVE", false, false, 8L));

        mockMvc.perform(authenticated(post("/api/dinner/household/members/me/leave"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorMembershipId\":32,\"expectedVersion\":7,"
                                + "\"idempotencyKey\":\"" + LEAVE_KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationType").value("MEMBER_LEAVE"))
                .andExpect(jsonPath("$.data.replayed").value(false))
                .andExpect(jsonPath("$.data.actorHasHousehold").value(false))
                .andExpect(jsonPath("$.data.householdVersion").value(8))
                .andExpect(jsonPath("$.data.actorMembershipId").doesNotExist())
                .andExpect(jsonPath("$.data.targetMembershipId").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());

        verify(householdOperationService).leave(7L, 32L, 7L, LEAVE_KEY);
    }

    @Test
    void removalForwardsPathTargetAndReturnsReplayMarker() throws Exception {
        when(householdOperationService.remove(7L, 31L, 7L, 32L, 2L, REMOVAL_KEY))
                .thenReturn(new HouseholdMutationResponse("OWNER_REMOVE", true, true, 8L));

        mockMvc.perform(authenticated(post("/api/dinner/household/members/32/removal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorMembershipId\":31,\"expectedVersion\":7,"
                                + "\"targetMembershipVersion\":2,"
                                + "\"idempotencyKey\":\"" + REMOVAL_KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationType").value("OWNER_REMOVE"))
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.actorHasHousehold").value(true))
                .andExpect(jsonPath("$.data.householdVersion").value(8))
                .andExpect(jsonPath("$.data.actorMembershipId").doesNotExist())
                .andExpect(jsonPath("$.data.targetMembershipId").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());

        verify(householdOperationService).remove(7L, 31L, 7L, 32L, 2L, REMOVAL_KEY);
    }

    @ParameterizedTest
    @MethodSource("invalidLeaveRequests")
    void leaveRejectsInvalidRequestBodiesBeforeCallingService(String body) throws Exception {
        mockMvc.perform(authenticated(post("/api/dinner/household/members/me/leave"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(householdOperationService);
    }

    @ParameterizedTest
    @MethodSource("invalidRemovalRequests")
    void removalRejectsInvalidRequestBodiesBeforeCallingService(String body) throws Exception {
        mockMvc.perform(authenticated(post("/api/dinner/household/members/32/removal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(householdOperationService);
    }

    @Test
    void removalRejectsNonPositiveTargetMembershipIdBeforeCallingService() throws Exception {
        mockMvc.perform(authenticated(post("/api/dinner/household/members/0/removal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorMembershipId\":31,\"expectedVersion\":7,"
                                + "\"targetMembershipVersion\":2,"
                                + "\"idempotencyKey\":\"" + REMOVAL_KEY + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(householdOperationService);
    }

    @Test
    void removalRejectsMalformedTargetMembershipIdBeforeCallingService() throws Exception {
        mockMvc.perform(authenticated(post(
                        "/api/dinner/household/members/not-a-number/removal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorMembershipId\":31,\"expectedVersion\":7,"
                                + "\"targetMembershipVersion\":2,"
                                + "\"idempotencyKey\":\"" + REMOVAL_KEY + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(householdOperationService);
    }

    @Test
    void leaveMapsStrictUuidV4RejectionFromOperationService() throws Exception {
        String versionThreeUuid = "00000000-0000-3000-8000-000000000031";
        when(householdOperationService.leave(7L, 32L, 7L, versionThreeUuid))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

        mockMvc.perform(authenticated(post("/api/dinner/household/members/me/leave"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorMembershipId\":32,\"expectedVersion\":7,"
                                + "\"idempotencyKey\":\"" + versionThreeUuid + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verify(householdOperationService).leave(7L, 32L, 7L, versionThreeUuid);
    }

    @Test
    void leaveMapsOwnerCannotLeaveToConflict() throws Exception {
        when(householdOperationService.leave(7L, 31L, 7L, LEAVE_KEY))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_OWNER_CANNOT_LEAVE));

        mockMvc.perform(authenticated(post("/api/dinner/household/members/me/leave"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorMembershipId\":31,\"expectedVersion\":7,"
                                + "\"idempotencyKey\":\"" + LEAVE_KEY + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode")
                        .value("DINNER_HOUSEHOLD_OWNER_CANNOT_LEAVE"));
    }

    @Test
    void leaveMapsHouseholdVersionConflict() throws Exception {
        when(householdOperationService.leave(7L, 32L, 6L, LEAVE_KEY))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_VERSION_CONFLICT));

        mockMvc.perform(authenticated(post("/api/dinner/household/members/me/leave"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorMembershipId\":32,\"expectedVersion\":6,"
                                + "\"idempotencyKey\":\"" + LEAVE_KEY + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode")
                        .value("DINNER_HOUSEHOLD_VERSION_CONFLICT"));
    }

    @Test
    void removalMapsOwnerPermissionFailureToForbidden() throws Exception {
        when(householdOperationService.remove(7L, 32L, 7L, 31L, 3L, REMOVAL_KEY))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_OWNER_REQUIRED));

        mockMvc.perform(authenticated(post("/api/dinner/household/members/31/removal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorMembershipId\":32,\"expectedVersion\":7,"
                                + "\"targetMembershipVersion\":3,"
                                + "\"idempotencyKey\":\"" + REMOVAL_KEY + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode")
                        .value("DINNER_HOUSEHOLD_OWNER_REQUIRED"));
    }

    @Test
    void removalMapsHiddenOrForeignTargetToNotFound() throws Exception {
        when(householdOperationService.remove(7L, 31L, 7L, 99L, 1L, REMOVAL_KEY))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_NOT_FOUND));

        mockMvc.perform(authenticated(post("/api/dinner/household/members/99/removal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorMembershipId\":31,\"expectedVersion\":7,"
                                + "\"targetMembershipVersion\":1,"
                                + "\"idempotencyKey\":\"" + REMOVAL_KEY + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode")
                        .value("DINNER_HOUSEHOLD_MEMBER_NOT_FOUND"));
    }

    @Test
    void removalMapsMembershipStateConflict() throws Exception {
        when(householdOperationService.remove(7L, 31L, 7L, 32L, 1L, REMOVAL_KEY))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT));

        mockMvc.perform(authenticated(post("/api/dinner/household/members/32/removal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorMembershipId\":31,\"expectedVersion\":7,"
                                + "\"targetMembershipVersion\":1,"
                                + "\"idempotencyKey\":\"" + REMOVAL_KEY + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode")
                        .value("DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT"));
    }

    @Test
    void removalMapsReusedKeyWithDifferentRequestToOperationConflict() throws Exception {
        when(householdOperationService.remove(7L, 31L, 7L, 32L, 2L, REMOVAL_KEY))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_OPERATION_CONFLICT));

        mockMvc.perform(authenticated(post("/api/dinner/household/members/32/removal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorMembershipId\":31,\"expectedVersion\":7,"
                                + "\"targetMembershipVersion\":2,"
                                + "\"idempotencyKey\":\"" + REMOVAL_KEY + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode")
                        .value("DINNER_HOUSEHOLD_OPERATION_CONFLICT"));
    }

    @Test
    void joinReturnsMemberRoleAndVersionsWithoutInviteOrUserProfileData() throws Exception {
        when(householdService.join(7L, "DINNER 0123 ABYZ"))
                .thenReturn(household(2, 8L, 5L, "MEMBER", 32L, 1L));

        mockMvc.perform(authenticated(post("/api/dinner/households/join"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"DINNER 0123 ABYZ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.memberCount").value(2))
                .andExpect(jsonPath("$.data.version").value(8))
                .andExpect(jsonPath("$.data.inviteRevision").value(5))
                .andExpect(jsonPath("$.data.myRole").value("MEMBER"))
                .andExpect(jsonPath("$.data.myMembershipId").value(32))
                .andExpect(jsonPath("$.data.myMembershipVersion").value(1))
                .andExpect(jsonPath("$.data.inviteCode").doesNotExist())
                .andExpect(jsonPath("$.data.codeHash").doesNotExist())
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.createdBy").doesNotExist())
                .andExpect(jsonPath("$.data.displayName").doesNotExist())
                .andExpect(jsonPath("$.data.avatarUrl").doesNotExist());
    }

    @Test
    void joinMapsInviteError() throws Exception {
        when(householdService.join(7L, "DINNER 0000"))
                .thenThrow(new BusinessException(ErrorCode.DINNER_INVITE_INVALID));

        mockMvc.perform(authenticated(post("/api/dinner/households/join"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"DINNER 0000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("DINNER_INVITE_INVALID"));
    }

    @Test
    void joinMapsExpiredInviteError() throws Exception {
        when(householdService.join(7L, "DINNER 1234"))
                .thenThrow(new BusinessException(ErrorCode.DINNER_INVITE_EXPIRED));

        mockMvc.perform(authenticated(post("/api/dinner/households/join"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"DINNER 1234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("DINNER_INVITE_EXPIRED"));
    }

    @Test
    void joinRejectsBlankInviteBeforeCallingService() throws Exception {
        mockMvc.perform(authenticated(post("/api/dinner/households/join"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(householdService);
    }

    private static Stream<Arguments> protectedHouseholdEndpoints() {
        return Stream.of(
                Arguments.of(get("/api/dinner/household")),
                Arguments.of(get("/api/dinner/household/members")),
                Arguments.of(put("/api/dinner/household")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"我们的小家\",\"expectedVersion\":1}")),
                Arguments.of(get("/api/dinner/household/invite-code")),
                Arguments.of(post("/api/dinner/households")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"我们的小家\"}")),
                Arguments.of(post("/api/dinner/households/invite-code/refresh")),
                Arguments.of(post("/api/dinner/household/invite-code/revocation")),
                Arguments.of(post("/api/dinner/household/members/me/leave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorMembershipId\":32,\"expectedVersion\":7,"
                                + "\"idempotencyKey\":\"" + LEAVE_KEY + "\"}")),
                Arguments.of(post("/api/dinner/household/members/32/removal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorMembershipId\":31,\"expectedVersion\":7,"
                                + "\"targetMembershipVersion\":2,"
                                + "\"idempotencyKey\":\"" + REMOVAL_KEY + "\"}")),
                Arguments.of(post("/api/dinner/households/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"DINNER 1234\"}")));
    }

    private static Stream<Arguments> invalidRenameRequests() {
        return Stream.of(
                Arguments.of("{\"name\":\"\",\"expectedVersion\":7}"),
                Arguments.of("{\"name\":\"我们的小家\",\"expectedVersion\":0}"),
                Arguments.of("{\"name\":\"我们的小家\"}"));
    }

    private static Stream<Arguments> invalidLeaveRequests() {
        return Stream.of(
                Arguments.of("{\"actorMembershipId\":0,\"expectedVersion\":7,"
                        + "\"idempotencyKey\":\"" + LEAVE_KEY + "\"}"),
                Arguments.of("{\"actorMembershipId\":32,\"expectedVersion\":0,"
                        + "\"idempotencyKey\":\"" + LEAVE_KEY + "\"}"),
                Arguments.of("{\"expectedVersion\":7,"
                        + "\"idempotencyKey\":\"" + LEAVE_KEY + "\"}"),
                Arguments.of("{\"actorMembershipId\":32,"
                        + "\"idempotencyKey\":\"" + LEAVE_KEY + "\"}"),
                Arguments.of("{\"actorMembershipId\":32,\"expectedVersion\":7,"
                        + "\"idempotencyKey\":\"   \"}"),
                Arguments.of("{\"actorMembershipId\":32,\"expectedVersion\":7,"
                        + "\"idempotencyKey\":\"00000000-0000-4000-8000-00000000003\"}"));
    }

    private static Stream<Arguments> invalidRemovalRequests() {
        return Stream.of(
                Arguments.of("{\"actorMembershipId\":0,\"expectedVersion\":7,"
                        + "\"targetMembershipVersion\":2,"
                        + "\"idempotencyKey\":\"" + REMOVAL_KEY + "\"}"),
                Arguments.of("{\"actorMembershipId\":31,\"expectedVersion\":0,"
                        + "\"targetMembershipVersion\":2,"
                        + "\"idempotencyKey\":\"" + REMOVAL_KEY + "\"}"),
                Arguments.of("{\"actorMembershipId\":31,\"expectedVersion\":7,"
                        + "\"targetMembershipVersion\":0,"
                        + "\"idempotencyKey\":\"" + REMOVAL_KEY + "\"}"),
                Arguments.of("{\"actorMembershipId\":31,\"expectedVersion\":7,"
                        + "\"idempotencyKey\":\"" + REMOVAL_KEY + "\"}"),
                Arguments.of("{\"actorMembershipId\":31,\"expectedVersion\":7,"
                        + "\"targetMembershipVersion\":2,\"idempotencyKey\":\"\"}"),
                Arguments.of("{\"actorMembershipId\":31,\"expectedVersion\":7,"
                        + "\"targetMembershipVersion\":2,"
                        + "\"idempotencyKey\":\"00000000-0000-4000-8000-0000000000320\"}"));
    }

    private HouseholdResponse household(
            int memberCount,
            Long version,
            Long inviteRevision,
            String myRole,
            Long myMembershipId,
            Long myMembershipVersion
    ) {
        return household(
                "我们的小家",
                memberCount,
                version,
                inviteRevision,
                myRole,
                myMembershipId,
                myMembershipVersion);
    }

    private HouseholdResponse household(
            String name,
            int memberCount,
            Long version,
            Long inviteRevision,
            String myRole,
            Long myMembershipId,
            Long myMembershipVersion
    ) {
        return new HouseholdResponse(
                11L,
                name,
                "Asia/Shanghai",
                memberCount,
                version,
                inviteRevision,
                myRole,
                myMembershipId,
                myMembershipVersion);
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
