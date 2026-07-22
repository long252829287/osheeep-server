package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class HouseholdOperationFingerprinterTest {

    private static final String SECRET = "test-secret-at-least-32-characters";

    private final HouseholdOperationFingerprinter fingerprinter =
            new HouseholdOperationFingerprinter(SECRET);

    @Test
    void acceptsOnlyCanonicalUuidV4AndNormalizesHexCase() {
        assertThat(fingerprinter.normalizeIdempotencyKey(
                        "550E8400-E29B-41D4-A716-446655440000"))
                .isEqualTo("550e8400-e29b-41d4-a716-446655440000");

        assertThatThrownBy(() -> fingerprinter.normalizeIdempotencyKey(
                        "550e8400-e29b-11d4-a716-446655440000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency key must be a canonical UUID v4");
        assertThatThrownBy(() -> fingerprinter.normalizeIdempotencyKey(
                        "550e8400-e29b-41d4-7716-446655440000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency key must be a canonical UUID v4");
        assertThatThrownBy(() -> fingerprinter.normalizeIdempotencyKey(
                        " 550e8400-e29b-41d4-a716-446655440000 "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency key must be a canonical UUID v4");
        assertThatThrownBy(() -> fingerprinter.normalizeIdempotencyKey(
                        "550e8400-e29b-41d4-a716-44665544000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency key must be a canonical UUID v4");
    }

    @Test
    void fingerprintsTheStableLengthPrefixedSemanticPayloadWithDomainSeparation()
            throws GeneralSecurityException {
        String fingerprint = fingerprinter.fingerprint(
                "OWNER_REMOVE", 41L, 8L, 73L, 3L, null);

        String canonicalPayload = "household-operation:v1:"
                + "operationType:12:OWNER_REMOVE;"
                + "actorMembershipId:2:41;"
                + "expectedHouseholdVersion:1:8;"
                + "targetMemberId:2:73;"
                + "targetMembershipVersion:1:3;"
                + "confirmationName:-1:;";
        assertThat(fingerprint)
                .isEqualTo(hmac(canonicalPayload))
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .doesNotContain("OWNER_REMOVE", "41", "73");
    }

    @Test
    void everySemanticInputChangesTheFingerprintDeterministically() {
        String baseline = fingerprinter.fingerprint(
                "OWNER_REMOVE", 41L, 8L, 73L, 3L, null);

        assertThat(fingerprinter.fingerprint(
                        "OWNER_REMOVE", 41L, 8L, 73L, 3L, null))
                .isEqualTo(baseline);
        assertThat(fingerprinter.fingerprint(
                        "OWNER_REMOVE", 42L, 8L, 73L, 3L, null))
                .isNotEqualTo(baseline);
        assertThat(fingerprinter.fingerprint(
                        "OWNER_REMOVE", 41L, 9L, 73L, 3L, null))
                .isNotEqualTo(baseline);
        assertThat(fingerprinter.fingerprint(
                        "OWNER_REMOVE", 41L, 8L, 74L, 3L, null))
                .isNotEqualTo(baseline);
        assertThat(fingerprinter.fingerprint(
                        "OWNER_REMOVE", 41L, 8L, 73L, 4L, null))
                .isNotEqualTo(baseline);

        String dissolution = fingerprinter.fingerprint(
                "HOUSEHOLD_DISSOLUTION", 41L, 8L, null, null, "我们的小家");
        assertThat(fingerprinter.fingerprint(
                        "HOUSEHOLD_DISSOLUTION", 41L, 8L, null, null, "我们的小家2"))
                .isNotEqualTo(dissolution);
    }

    @Test
    void validatesOperationSpecificSemanticShapeAndPositiveVersions() {
        assertThatThrownBy(() -> fingerprinter.fingerprint(
                        "MEMBER_LEAVE", 0L, 8L, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fingerprinter.fingerprint(
                        "OWNER_REMOVE", 41L, 8L, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fingerprinter.fingerprint(
                        "MEMBER_LEAVE", 41L, 8L, 73L, 3L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fingerprinter.fingerprint(
                        "HOUSEHOLD_DISSOLUTION", 41L, 8L, null, null, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fingerprinter.fingerprint(
                        "UNKNOWN", 41L, 8L, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String hmac(String message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
}
