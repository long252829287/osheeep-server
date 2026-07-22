DROP PROCEDURE IF EXISTS assert_dinner_household_management_v8_preconditions;

DELIMITER //

CREATE PROCEDURE assert_dinner_household_management_v8_preconditions()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM dinner_households h
        LEFT JOIN dinner_household_members m ON m.household_id = h.id
        GROUP BY h.id
        HAVING COUNT(m.id) = 0
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MYSQL_ERRNO = 1644,
                MESSAGE_TEXT = 'V8 household management migration rejected a household without members';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM dinner_household_members m
        GROUP BY m.household_id
        HAVING COUNT(*) > 2
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MYSQL_ERRNO = 1644,
                MESSAGE_TEXT = 'V8 household management migration rejected a household with more than two members';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM dinner_households h
        WHERE h.status <> 'ACTIVE'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MYSQL_ERRNO = 1644,
                MESSAGE_TEXT = 'V8 household management migration rejected a non-active household';
    END IF;
END//

DELIMITER ;

CALL assert_dinner_household_management_v8_preconditions();
DROP PROCEDURE assert_dinner_household_management_v8_preconditions;

ALTER TABLE dinner_households
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1 AFTER status,
    ADD COLUMN invite_revision BIGINT NOT NULL DEFAULT 0 AFTER version,
    ADD COLUMN admin_changed_at DATETIME(3) NULL AFTER invite_revision,
    ADD CONSTRAINT chk_dinner_households_active CHECK (CAST(status AS BINARY) = 'ACTIVE'),
    ADD CONSTRAINT chk_dinner_households_version CHECK (version >= 1),
    ADD CONSTRAINT chk_dinner_households_invite_revision CHECK (invite_revision >= 0);

ALTER TABLE dinner_household_members
    ADD COLUMN role VARCHAR(16) NULL AFTER user_id,
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' AFTER role,
    ADD COLUMN seat_no TINYINT NULL AFTER status,
    ADD COLUMN history_visible_from DATETIME(3) NULL AFTER joined_at,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1 AFTER history_visible_from,
    ADD COLUMN ended_at DATETIME(3) NULL AFTER version,
    ADD COLUMN ended_by BIGINT NULL AFTER ended_at,
    ADD COLUMN end_reason VARCHAR(24) NULL AFTER ended_by,
    ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) AFTER end_reason;

CREATE TEMPORARY TABLE dinner_household_member_v8_backfill (
    id BIGINT NOT NULL PRIMARY KEY,
    assigned_role VARCHAR(16) NOT NULL,
    assigned_seat TINYINT NOT NULL
) ENGINE=InnoDB;

INSERT INTO dinner_household_member_v8_backfill (id, assigned_role, assigned_seat)
SELECT ranked.id,
       CASE
           WHEN ranked.user_id = ranked.created_by THEN 'OWNER'
           WHEN ranked.creator_is_member = 0 AND ranked.join_order = 1 THEN 'OWNER'
           ELSE 'MEMBER'
       END AS assigned_role,
       CASE
           WHEN ranked.user_id = ranked.created_by THEN 1
           WHEN ranked.creator_is_member = 0 AND ranked.join_order = 1 THEN 1
           ELSE 2
       END AS assigned_seat
FROM (
    SELECT m.id,
           m.user_id,
           h.created_by,
           ROW_NUMBER() OVER (
               PARTITION BY m.household_id
               ORDER BY m.joined_at, m.id
           ) AS join_order,
           MAX(CASE WHEN m.user_id = h.created_by THEN 1 ELSE 0 END) OVER (
               PARTITION BY m.household_id
           ) AS creator_is_member
    FROM dinner_household_members m
    JOIN dinner_households h ON h.id = m.household_id
) ranked;

UPDATE dinner_household_members m
JOIN dinner_household_member_v8_backfill b ON b.id = m.id
SET m.role = b.assigned_role,
    m.seat_no = b.assigned_seat,
    m.history_visible_from = '1970-01-01 00:00:00.000';

DROP TEMPORARY TABLE dinner_household_member_v8_backfill;

ALTER TABLE dinner_household_members
    MODIFY role VARCHAR(16) NOT NULL,
    MODIFY seat_no TINYINT NOT NULL,
    MODIFY history_visible_from DATETIME(3) NOT NULL,
    ADD COLUMN active_user_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN user_id ELSE NULL END
    ) STORED,
    ADD COLUMN active_owner_household_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' AND role = 'OWNER' THEN household_id ELSE NULL END
    ) STORED,
    ADD COLUMN active_seat_no TINYINT GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN seat_no ELSE NULL END
    ) STORED,
    ADD KEY idx_dinner_household_members_user_history (user_id, joined_at, id),
    ADD KEY idx_dinner_household_members_active_role (household_id, status, role, id),
    ADD KEY idx_dinner_household_members_ended_by (ended_by),
    ADD CONSTRAINT fk_dinner_household_members_ended_by
        FOREIGN KEY (ended_by) REFERENCES users (id);

ALTER TABLE dinner_household_members
    DROP INDEX uk_dinner_household_members_user_id,
    DROP INDEX uk_dinner_household_members_household_user,
    ADD UNIQUE KEY uk_dinner_household_members_active_user (active_user_id),
    ADD UNIQUE KEY uk_dinner_household_members_active_owner (active_owner_household_id),
    ADD UNIQUE KEY uk_dinner_household_members_active_seat (household_id, active_seat_no),
    ADD CONSTRAINT chk_dinner_household_members_role
        CHECK (CAST(role AS BINARY) IN ('OWNER', 'MEMBER')),
    ADD CONSTRAINT chk_dinner_household_members_status
        CHECK (CAST(status AS BINARY) IN ('ACTIVE', 'LEFT', 'REMOVED')),
    ADD CONSTRAINT chk_dinner_household_members_seat
        CHECK (seat_no IN (1, 2)),
    ADD CONSTRAINT chk_dinner_household_members_version
        CHECK (version >= 1),
    ADD CONSTRAINT chk_dinner_household_members_end_reason
        CHECK (end_reason IS NULL OR CAST(end_reason AS BINARY) IN ('SELF_LEFT', 'OWNER_REMOVED')),
    ADD CONSTRAINT chk_dinner_household_members_end_state
        CHECK (
            (status = 'ACTIVE'
                AND ended_at IS NULL
                AND ended_by IS NULL
                AND end_reason IS NULL)
            OR
            (status <> 'ACTIVE'
                AND ended_at IS NOT NULL
                AND ended_by IS NOT NULL
                AND end_reason IS NOT NULL)
        );

ALTER TABLE dinner_invite_codes
    ADD COLUMN consumed_at DATETIME(3) NULL AFTER expires_at,
    ADD COLUMN consumed_by BIGINT NULL AFTER consumed_at,
    ADD COLUMN revocation_reason VARCHAR(32) NULL AFTER revoked_at;

UPDATE dinner_invite_codes
SET revocation_reason = 'LEGACY_REVOKED'
WHERE revoked_at IS NOT NULL
  AND revocation_reason IS NULL;

UPDATE dinner_invite_codes i
JOIN (
    SELECT household_id
    FROM dinner_household_members
    WHERE status = 'ACTIVE'
    GROUP BY household_id
    HAVING COUNT(*) = 2
) full_household ON full_household.household_id = i.household_id
SET i.revoked_at = UTC_TIMESTAMP(3),
    i.revocation_reason = 'MIGRATION_SUPERSEDED'
WHERE i.revoked_at IS NULL
  AND i.consumed_at IS NULL;

UPDATE dinner_invite_codes i
JOIN (
    SELECT ranked.id
    FROM (
        SELECT open_invite.id,
               ROW_NUMBER() OVER (
                   PARTITION BY open_invite.household_id
                   ORDER BY
                       CASE WHEN open_invite.expires_at > UTC_TIMESTAMP(3) THEN 1 ELSE 0 END DESC,
                       open_invite.created_at DESC,
                       open_invite.id DESC
               ) AS invite_order
        FROM dinner_invite_codes open_invite
        JOIN (
            SELECT household_id
            FROM dinner_household_members
            WHERE status = 'ACTIVE'
            GROUP BY household_id
            HAVING COUNT(*) = 1
        ) single_household ON single_household.household_id = open_invite.household_id
        WHERE open_invite.revoked_at IS NULL
          AND open_invite.consumed_at IS NULL
    ) ranked
    WHERE ranked.invite_order > 1
) superseded ON superseded.id = i.id
SET i.revoked_at = UTC_TIMESTAMP(3),
    i.revocation_reason = 'MIGRATION_SUPERSEDED';

UPDATE dinner_households h
SET h.invite_revision = 1
WHERE EXISTS (
    SELECT 1
    FROM dinner_invite_codes i
    WHERE i.household_id = h.id
      AND i.revoked_at IS NULL
      AND i.consumed_at IS NULL
);

ALTER TABLE dinner_invite_codes
    ADD COLUMN open_household_id BIGINT GENERATED ALWAYS AS (
        CASE
            WHEN consumed_at IS NULL AND revoked_at IS NULL THEN household_id
            ELSE NULL
        END
    ) STORED,
    ADD UNIQUE KEY uk_dinner_invite_codes_open_household (open_household_id),
    ADD KEY idx_dinner_invite_codes_consumed_by (consumed_by),
    ADD CONSTRAINT fk_dinner_invite_codes_consumed_by
        FOREIGN KEY (consumed_by) REFERENCES users (id),
    ADD CONSTRAINT chk_dinner_invite_codes_consumed_pair
        CHECK (
            (consumed_at IS NULL AND consumed_by IS NULL)
            OR (consumed_at IS NOT NULL AND consumed_by IS NOT NULL)
        ),
    ADD CONSTRAINT chk_dinner_invite_codes_terminal_state
        CHECK (NOT (consumed_at IS NOT NULL AND revoked_at IS NOT NULL)),
    ADD CONSTRAINT chk_dinner_invite_codes_revoked_pair
        CHECK (
            (revoked_at IS NULL AND revocation_reason IS NULL)
            OR (revoked_at IS NOT NULL AND revocation_reason IS NOT NULL)
        ),
    ADD CONSTRAINT chk_dinner_invite_codes_revocation_reason
        CHECK (
            revocation_reason IS NULL
            OR CAST(revocation_reason AS BINARY) IN (
                'LEGACY_REVOKED',
                'MIGRATION_SUPERSEDED',
                'REFRESHED',
                'MEMBER_REVOKED',
                'MEMBERSHIP_CHANGED'
            )
        );

CREATE TABLE dinner_household_operations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    household_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    actor_membership_id BIGINT NOT NULL,
    target_member_id BIGINT NULL,
    operation_type VARCHAR(32) NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_schema_version SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    result_household_version BIGINT NULL,
    result_payload JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_household_operations_actor_key (actor_id, idempotency_key),
    KEY idx_dinner_household_operations_expiry (expires_at, id),
    KEY idx_dinner_household_operations_household (household_id, id),
    KEY idx_dinner_household_operations_target (target_member_id, id),
    CONSTRAINT fk_dinner_household_operations_actor
        FOREIGN KEY (actor_id) REFERENCES users (id),
    CONSTRAINT chk_dinner_household_operations_type
        CHECK (
            CAST(operation_type AS BINARY) IN (
                'MEMBER_LEAVE',
                'OWNER_REMOVE',
                'OWNERSHIP_TRANSFER',
                'HOUSEHOLD_DISSOLUTION'
            )
        ),
    CONSTRAINT chk_dinner_household_operations_target
        CHECK (
            (operation_type IN ('OWNER_REMOVE', 'OWNERSHIP_TRANSFER')
                AND target_member_id IS NOT NULL)
            OR
            (operation_type IN ('MEMBER_LEAVE', 'HOUSEHOLD_DISSOLUTION')
                AND target_member_id IS NULL)
        ),
    CONSTRAINT chk_dinner_household_operations_result_schema
        CHECK (result_schema_version = 1),
    CONSTRAINT chk_dinner_household_operations_result_version
        CHECK (result_household_version IS NULL OR result_household_version >= 1),
    CONSTRAINT chk_dinner_household_operations_result_payload
        CHECK (
            JSON_TYPE(result_payload) = 'OBJECT'
            AND JSON_LENGTH(result_payload) = 1
            AND JSON_CONTAINS_PATH(result_payload, 'one', '$.actorHasHousehold') = 1
            AND JSON_TYPE(JSON_EXTRACT(result_payload, '$.actorHasHousehold')) = 'BOOLEAN'
        ),
    CONSTRAINT chk_dinner_household_operations_expiry
        CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
