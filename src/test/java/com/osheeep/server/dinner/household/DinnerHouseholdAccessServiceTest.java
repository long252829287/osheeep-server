package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Runtime contract for the shared household authorization boundary.
 *
 * <p>The production type is deliberately loaded by name so this RED test compiles while the new
 * service is still absent. Once present, the tests exercise its public contract normally and
 * unwrap reflective calls back to the original business exception.</p>
 */
@ExtendWith(MockitoExtension.class)
class DinnerHouseholdAccessServiceTest {

    private static final String SERVICE_CLASS =
            "com.osheeep.server.dinner.household.DinnerHouseholdAccessService";
    private static final LocalDateTime JOINED_AT = LocalDateTime.of(2026, 7, 11, 6, 0);

    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerHouseholdMapper householdMapper;

    @ParameterizedTest
    @ValueSource(strings = {"LEFT", "REMOVED"})
    void endedMembershipIsNeverCurrentAndCannotAuthorizeItsOldHousehold(String status)
            throws Throwable {
        DinnerHouseholdMemberEntity ended = membership(31L, 11L, 7L, "MEMBER", status, 3L);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(ended);

        Object service = newService();

        assertThat(invoke(service, "findActiveMembership", 7L)).isNull();
        assertBusinessError(
                () -> invoke(service, "requireActiveHousehold", 7L),
                "DINNER_HOUSEHOLD_REQUIRED");
        verifyNoInteractions(householdMapper);
    }

    @Test
    void requireActiveHouseholdRejectsActiveMembershipWhoseHouseholdIsMissing()
            throws Throwable {
        DinnerHouseholdMemberEntity active = membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(active);
        when(householdMapper.selectById(11L)).thenReturn(null);

        Object service = newService();

        assertBusinessError(
                () -> invoke(service, "requireActiveHousehold", 7L),
                "DINNER_HOUSEHOLD_REQUIRED");
    }

    @Test
    void requireActiveHouseholdRejectsActiveMembershipWhoseHouseholdIsInactive()
            throws Throwable {
        DinnerHouseholdMemberEntity active = membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        DinnerHouseholdEntity dissolved = household(11L, "DISSOLVED", 8L);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(active);
        when(householdMapper.selectById(11L)).thenReturn(dissolved);

        Object service = newService();

        assertBusinessError(
                () -> invoke(service, "requireActiveHousehold", 7L),
                "DINNER_HOUSEHOLD_REQUIRED");
    }

    @Test
    void requireActiveHouseholdMapsMissingMembershipToHouseholdRequired() throws Throwable {
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(null);

        Object service = newService();

        assertBusinessError(
                () -> invoke(service, "requireActiveHousehold", 7L),
                "DINNER_HOUSEHOLD_REQUIRED");
        verifyNoInteractions(householdMapper);
    }

    @Test
    void activeMembershipLookupFailsClosedForWrongUserOrMissingRowIdentity() throws Throwable {
        DinnerHouseholdMemberEntity wrongUser =
                membership(41L, 11L, 8L, "OWNER", "ACTIVE", 4L);
        DinnerHouseholdMemberEntity missingMembershipId =
                membership(null, 11L, 7L, "OWNER", "ACTIVE", 4L);
        DinnerHouseholdMemberEntity missingHouseholdId =
                membership(41L, null, 7L, "OWNER", "ACTIVE", 4L);
        when(memberMapper.selectActiveByUserId(7L))
                .thenReturn(wrongUser, missingMembershipId, missingHouseholdId);

        Object service = newService();

        assertThat(invoke(service, "findActiveMembership", 7L)).isNull();
        assertThat(invoke(service, "findActiveMembership", 7L)).isNull();
        assertThat(invoke(service, "findActiveMembership", 7L)).isNull();
        verifyNoInteractions(householdMapper);
    }

    @Test
    void activeHouseholdAccessFailsClosedForIncompleteMembershipSnapshot() throws Throwable {
        DinnerHouseholdMemberEntity missingHistory =
                membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        missingHistory.setHistoryVisibleFrom(null);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(missingHistory);

        Object service = newService();

        assertBusinessError(
                () -> invoke(service, "requireActiveHousehold", 7L),
                "DINNER_HOUSEHOLD_REQUIRED");
        verifyNoInteractions(householdMapper);
    }

    @Test
    void activeHouseholdAccessFailsClosedForMismatchedOrIncompleteHouseholdSnapshot()
            throws Throwable {
        DinnerHouseholdMemberEntity active = membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        DinnerHouseholdEntity mismatched = household(12L, "ACTIVE", 8L);
        DinnerHouseholdEntity missingTimezone = household(11L, "ACTIVE", 8L);
        missingTimezone.setTimezone(" ");
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(active);
        when(householdMapper.selectById(11L)).thenReturn(mismatched, missingTimezone);

        Object service = newService();

        assertBusinessError(
                () -> invoke(service, "requireActiveHousehold", 7L),
                "DINNER_HOUSEHOLD_REQUIRED");
        assertBusinessError(
                () -> invoke(service, "requireActiveHousehold", 7L),
                "DINNER_HOUSEHOLD_REQUIRED");
    }

    @Test
    void deterministicActiveLookupWinsOverAnOlderHistoryRow() throws Throwable {
        DinnerHouseholdMemberEntity oldHistory =
                membership(21L, 10L, 7L, "MEMBER", "LEFT", 2L);
        DinnerHouseholdMemberEntity current =
                membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        lenient().when(memberMapper.selectOne(any())).thenReturn(oldHistory);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(current);
        when(householdMapper.selectById(11L)).thenReturn(household(11L, "ACTIVE", 8L));

        Object service = newService();
        Object access = invoke(service, "requireActiveHousehold", 7L);

        assertThat(component(access, "membershipId")).isEqualTo(41L);
        assertThat(component(access, "householdId")).isEqualTo(11L);
        verify(memberMapper).selectActiveByUserId(7L);
        verify(memberMapper, never()).selectOne(any());
    }

    @Test
    void activeOwnerAccessContainsOnlyTheAuthorizedSnapshotAndPassesOwnerCheck()
            throws Throwable {
        DinnerHouseholdMemberEntity owner = membership(41L, 11L, 7L, "OWNER", "ACTIVE", 4L);
        DinnerHouseholdEntity household = household(11L, "ACTIVE", 8L);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(owner);
        when(householdMapper.selectById(11L)).thenReturn(household);

        Object service = newService();
        Object access = invoke(service, "requireActiveHousehold", 7L);
        Object ownerAccess = invoke(service, "requireOwner", access);

        assertThat(ownerAccess).isSameAs(access);
        assertThat(component(access, "userId")).isEqualTo(7L);
        assertThat(component(access, "householdId")).isEqualTo(11L);
        assertThat(component(access, "membershipId")).isEqualTo(41L);
        assertThat(component(access, "membershipVersion")).isEqualTo(4L);
        assertThat(component(access, "role")).isEqualTo("OWNER");
        assertThat(component(access, "historyVisibleFrom")).isEqualTo(JOINED_AT);
        assertThat(component(access, "householdVersion")).isEqualTo(8L);
        assertThat(component(access, "timezone")).isEqualTo("Asia/Shanghai");
    }

    @Test
    void requireOwnerRejectsAnActiveMember() throws Throwable {
        DinnerHouseholdMemberEntity member = membership(42L, 11L, 7L, "MEMBER", "ACTIVE", 2L);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(member);
        when(householdMapper.selectById(11L)).thenReturn(household(11L, "ACTIVE", 8L));

        Object service = newService();
        Object access = invoke(service, "requireActiveHousehold", 7L);

        assertBusinessError(() -> invoke(service, "requireOwner", access), "FORBIDDEN");
    }

    private Object newService() throws ReflectiveOperationException {
        Class<?> serviceType = Class.forName(SERVICE_CLASS);
        Constructor<?> constructor = Arrays.stream(serviceType.getDeclaredConstructors())
                .filter(candidate -> candidate.getParameterCount() == 2)
                .filter(candidate -> Arrays.asList(candidate.getParameterTypes())
                        .containsAll(Arrays.asList(
                                DinnerHouseholdMemberMapper.class, DinnerHouseholdMapper.class)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "DinnerHouseholdAccessService must inject member and household mappers"));
        constructor.setAccessible(true);
        Object[] arguments = Arrays.stream(constructor.getParameterTypes())
                .map(type -> type == DinnerHouseholdMemberMapper.class ? memberMapper : householdMapper)
                .toArray();
        return constructor.newInstance(arguments);
    }

    private Object invoke(Object target, String methodName, Object argument) throws Throwable {
        Method method = Arrays.stream(target.getClass().getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .filter(candidate -> candidate.getParameterCount() == 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing public method " + methodName));
        try {
            return method.invoke(target, argument);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private Object component(Object access, String componentName) throws Throwable {
        Method accessor = access.getClass().getMethod(componentName);
        try {
            return accessor.invoke(access);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private void assertBusinessError(ThrowingCallable call, String expectedCode) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode().name()).isEqualTo(expectedCode));
    }

    private DinnerHouseholdMemberEntity membership(
            Long id,
            Long householdId,
            Long userId,
            String role,
            String status,
            Long version
    ) {
        DinnerHouseholdMemberEntity membership = new DinnerHouseholdMemberEntity();
        membership.setId(id);
        membership.setHouseholdId(householdId);
        membership.setUserId(userId);
        membership.setRole(role);
        membership.setStatus(status);
        membership.setVersion(version);
        membership.setHistoryVisibleFrom(JOINED_AT);
        membership.setJoinedAt(JOINED_AT);
        return membership;
    }

    private DinnerHouseholdEntity household(Long id, String status, Long version) {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(id);
        household.setName("我们的小家");
        household.setTimezone("Asia/Shanghai");
        household.setStatus(status);
        household.setVersion(version);
        return household;
    }
}
