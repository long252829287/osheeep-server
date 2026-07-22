package com.osheeep.server.dinner.household;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdOperationEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdOperationMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerHouseholdOperationRetentionService {

    static final int DEFAULT_BATCH_SIZE = 100;
    static final int DEFAULT_MAX_BATCHES_PER_RUN = 10;
    private static final int RETENTION_DAYS = 14;

    private final DinnerHouseholdOperationMapper operationMapper;
    private final Clock clock;
    private final int batchSize;
    private final int maxBatchesPerRun;

    @Autowired
    public DinnerHouseholdOperationRetentionService(
            DinnerHouseholdOperationMapper operationMapper
    ) {
        this(
                operationMapper,
                Clock.systemUTC(),
                DEFAULT_BATCH_SIZE,
                DEFAULT_MAX_BATCHES_PER_RUN);
    }

    DinnerHouseholdOperationRetentionService(
            DinnerHouseholdOperationMapper operationMapper,
            Clock clock,
            int batchSize,
            int maxBatchesPerRun
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Operation cleanup batch size must be positive");
        }
        if (maxBatchesPerRun < 1) {
            throw new IllegalArgumentException(
                    "Operation cleanup maximum batch count must be positive");
        }
        this.operationMapper = operationMapper;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    public RetentionWindow newRetentionWindow() {
        LocalDateTime createdAt = utcNow();
        return new RetentionWindow(createdAt, createdAt.plusDays(RETENTION_DAYS));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cleanupExpiredBatch() {
        return deleteExpiredBatch().deletedCount();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cleanupExpired() {
        int totalDeleted = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            CleanupBatch result = deleteExpiredBatch();
            totalDeleted += result.deletedCount();
            if (result.selectedCount() < batchSize) {
                break;
            }
        }
        return totalDeleted;
    }

    private CleanupBatch deleteExpiredBatch() {
        List<DinnerHouseholdOperationEntity> expired = operationMapper.selectList(
                new QueryWrapper<DinnerHouseholdOperationEntity>()
                        .select("id")
                        .le("expires_at", utcNow())
                        .orderByAsc("expires_at", "id")
                        .last("LIMIT " + batchSize));
        if (expired.isEmpty()) {
            return new CleanupBatch(0, 0);
        }
        List<Long> ids = expired.stream()
                .map(DinnerHouseholdOperationEntity::getId)
                .toList();
        if (ids.stream().anyMatch(id -> id == null)) {
            throw new IllegalStateException("Operation cleanup selected a row without an id");
        }
        return new CleanupBatch(ids.size(), operationMapper.deleteByIds(ids));
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
    }

    public record RetentionWindow(LocalDateTime createdAt, LocalDateTime expiresAt) {
        public RetentionWindow {
            if (createdAt == null || expiresAt == null
                    || !expiresAt.equals(createdAt.plusDays(RETENTION_DAYS))) {
                throw new IllegalArgumentException(
                        "Household operation retention must be exactly 14 days");
            }
        }
    }

    private record CleanupBatch(int selectedCount, int deletedCount) {
    }
}
