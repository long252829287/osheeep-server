package com.osheeep.server.dinner.household;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HouseholdOperationFingerprinter {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String DOMAIN_PREFIX = "household-operation:v1:";
    private static final String INVALID_KEY_MESSAGE =
            "Idempotency key must be a canonical UUID v4";
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-"
                    + "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private static final Set<String> TARGET_OPERATIONS = Set.of(
            "OWNER_REMOVE", "OWNERSHIP_TRANSFER");
    private static final Set<String> TARGETLESS_OPERATIONS = Set.of(
            "MEMBER_LEAVE", "HOUSEHOLD_DISSOLUTION");

    private final SecretKeySpec secretKey;

    public HouseholdOperationFingerprinter(
            @Value("${osheeep.dinner.invite-secret}") String secret
    ) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("Dinner invite secret is required");
        }
        this.secretKey = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !UUID_V4.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException(INVALID_KEY_MESSAGE);
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(INVALID_KEY_MESSAGE, exception);
        }
        if (parsed.version() != 4 || parsed.variant() != 2) {
            throw new IllegalArgumentException(INVALID_KEY_MESSAGE);
        }
        return parsed.toString();
    }

    public String fingerprint(
            String operationType,
            Long actorMembershipId,
            Long expectedHouseholdVersion,
            Long targetMemberId,
            Long targetMembershipVersion,
            String normalizedConfirmationName
    ) {
        validatePositive(actorMembershipId, "Actor membership id");
        validatePositive(expectedHouseholdVersion, "Expected household version");
        String confirmationName = validateOperationShape(
                operationType,
                targetMemberId,
                targetMembershipVersion,
                normalizedConfirmationName);

        StringBuilder payload = new StringBuilder(DOMAIN_PREFIX);
        appendField(payload, "operationType", operationType);
        appendField(payload, "actorMembershipId", actorMembershipId.toString());
        appendField(payload, "expectedHouseholdVersion", expectedHouseholdVersion.toString());
        appendField(payload, "targetMemberId", valueOf(targetMemberId));
        appendField(payload, "targetMembershipVersion", valueOf(targetMembershipVersion));
        appendField(payload, "confirmationName", confirmationName);
        return hmac(payload.toString());
    }

    private String validateOperationShape(
            String operationType,
            Long targetMemberId,
            Long targetMembershipVersion,
            String normalizedConfirmationName
    ) {
        if (!TARGET_OPERATIONS.contains(operationType)
                && !TARGETLESS_OPERATIONS.contains(operationType)) {
            throw new IllegalArgumentException("Household operation type is invalid");
        }

        if (TARGET_OPERATIONS.contains(operationType)) {
            validatePositive(targetMemberId, "Target member id");
            validatePositive(targetMembershipVersion, "Target membership version");
        } else if (targetMemberId != null || targetMembershipVersion != null) {
            throw new IllegalArgumentException("Target context is not allowed for this operation");
        }

        if ("HOUSEHOLD_DISSOLUTION".equals(operationType)) {
            if (normalizedConfirmationName == null
                    || normalizedConfirmationName.isBlank()) {
                throw new IllegalArgumentException(
                        "Normalized confirmation name is required for dissolution");
            }
            return Normalizer.normalize(normalizedConfirmationName, Normalizer.Form.NFC);
        }
        if (normalizedConfirmationName != null) {
            throw new IllegalArgumentException(
                    "Confirmation name is not allowed for this operation");
        }
        return null;
    }

    private void validatePositive(Long value, String label) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    private String valueOf(Long value) {
        return value == null ? null : value.toString();
    }

    private void appendField(StringBuilder payload, String name, String value) {
        payload.append(name).append(':');
        if (value == null) {
            payload.append("-1:;");
            return;
        }
        payload.append(value.getBytes(StandardCharsets.UTF_8).length)
                .append(':')
                .append(value)
                .append(';');
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKey);
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Unable to fingerprint dinner household operation", exception);
        }
    }
}
