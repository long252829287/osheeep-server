package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osheeep.server.dinner.household.DinnerHouseholdOperationRetentionService.RetentionWindow;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DinnerHouseholdOperationRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T12:34:56.789Z");
    private static final LocalDateTime UTC_NOW = LocalDateTime.parse("2026-07-22T12:34:56.789");

    @Test
    void createsAnExactFourteenDayUtcRetentionWindowFromTheInjectedClock() {
        DinnerHouseholdOperationMapper mapper = org.mockito.Mockito.mock(
                DinnerHouseholdOperationMapper.class);
        Clock shanghaiClock = Clock.fixed(NOW, ZoneId.of("Asia/Shanghai"));
        DinnerHouseholdOperationRetentionService service =
                new DinnerHouseholdOperationRetentionService(mapper, shanghaiClock, 100, 10);

        RetentionWindow window = service.newRetentionWindow();

        assertThat(window.createdAt()).isEqualTo(UTC_NOW);
        assertThat(window.expiresAt()).isEqualTo(UTC_NOW.plusDays(14));
    }

    @Test
    void deletesOneInclusiveExpiredBatchInExpiryAndIdOrder() {
        DinnerHouseholdOperationMapper mapper = org.mockito.Mockito.mock(
                DinnerHouseholdOperationMapper.class);
        DinnerHouseholdOperationRetentionService service = service(mapper);
        when(mapper.selectList(any())).thenReturn(List.of(operation(11L), operation(12L)));
        when(mapper.deleteByIds(any(Collection.class))).thenReturn(2);

        assertThat(service.cleanupExpiredBatch()).isEqualTo(2);

        ArgumentCaptor<Wrapper<DinnerHouseholdOperationEntity>> query = wrapperCaptor();
        verify(mapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment())
                .containsPattern("expires_at\\s*<=")
                .contains("ORDER BY expires_at ASC,id ASC")
                .endsWith("LIMIT 100");
        assertThat(parameterValues(query.getValue())).containsValue(UTC_NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(mapper).deleteByIds(ids.capture());
        assertThat(ids.getValue()).containsExactly(11L, 12L);
    }

    @Test
    void emptyBatchDoesNotIssueDelete() {
        DinnerHouseholdOperationMapper mapper = org.mockito.Mockito.mock(
                DinnerHouseholdOperationMapper.class);
        DinnerHouseholdOperationRetentionService service = service(mapper);
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThat(service.cleanupExpiredBatch()).isZero();

        verify(mapper, never()).deleteByIds(any(Collection.class));
    }

    @Test
    void scheduledCleanupUsesBoundedBatchesAndStopsAfterAShortBatch() {
        DinnerHouseholdOperationMapper mapper = org.mockito.Mockito.mock(
                DinnerHouseholdOperationMapper.class);
        DinnerHouseholdOperationRetentionService service =
                new DinnerHouseholdOperationRetentionService(
                        mapper, Clock.fixed(NOW, ZoneId.of("UTC")), 2, 10);
        when(mapper.selectList(any()))
                .thenReturn(
                        List.of(operation(1L), operation(2L)),
                        List.of(operation(3L)));
        when(mapper.deleteByIds(any(Collection.class))).thenReturn(2, 1);

        assertThat(service.cleanupExpired()).isEqualTo(3);

        verify(mapper, times(2)).selectList(any());
        verify(mapper, times(2)).deleteByIds(any(Collection.class));
    }

    @Test
    void scheduledCleanupCannotExceedConfiguredMaximumBatchCount() {
        DinnerHouseholdOperationMapper mapper = org.mockito.Mockito.mock(
                DinnerHouseholdOperationMapper.class);
        DinnerHouseholdOperationRetentionService service =
                new DinnerHouseholdOperationRetentionService(
                        mapper, Clock.fixed(NOW, ZoneId.of("UTC")), 1, 3);
        when(mapper.selectList(any())).thenReturn(List.of(operation(1L)));
        when(mapper.deleteByIds(any(Collection.class))).thenReturn(1);

        assertThat(service.cleanupExpired()).isEqualTo(3);

        verify(mapper, times(3)).selectList(any());
        verify(mapper, times(3)).deleteByIds(any(Collection.class));
    }

    @Test
    void schedulerDelegatesWithoutReceivingOrLoggingOperationIds() {
        DinnerHouseholdOperationRetentionService service = org.mockito.Mockito.mock(
                DinnerHouseholdOperationRetentionService.class);
        DinnerHouseholdOperationRetentionScheduler scheduler =
                new DinnerHouseholdOperationRetentionScheduler(service);
        when(service.cleanupExpired()).thenReturn(4);

        scheduler.cleanupExpiredOperations();

        verify(service).cleanupExpired();
    }

    private DinnerHouseholdOperationRetentionService service(
            DinnerHouseholdOperationMapper mapper) {
        return new DinnerHouseholdOperationRetentionService(
                mapper, Clock.fixed(NOW, ZoneId.of("UTC")), 100, 10);
    }

    private DinnerHouseholdOperationEntity operation(Long id) {
        DinnerHouseholdOperationEntity operation = new DinnerHouseholdOperationEntity();
        operation.setId(id);
        return operation;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private static Map<String, Object> parameterValues(Wrapper<?> wrapper) {
        try {
            Object values = wrapper.getClass()
                    .getMethod("getParamNameValuePairs")
                    .invoke(wrapper);
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) values;
            return parameters;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to inspect query parameters", exception);
        }
    }
}
