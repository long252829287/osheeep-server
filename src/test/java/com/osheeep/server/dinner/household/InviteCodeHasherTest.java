package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InviteCodeHasherTest {

    @Test
    void normalizesAndHashesWithoutReturningPlaintext() {
        InviteCodeHasher hasher = new InviteCodeHasher("test-secret-at-least-32-characters");

        String hash = hasher.hash("dinner 5268");

        assertThat(hash).isEqualTo(hasher.hash("DINNER5268"));
        assertThat(hash).hasSize(64).doesNotContain("DINNER", "5268");
    }

    @Test
    void preservesLegacyCompactHashInputWhileAcceptingNewDisplayFormat() {
        InviteCodeHasher hasher = new InviteCodeHasher("test-secret-at-least-32-characters");

        assertThat(hasher.hash("dinner 0123-4567"))
                .isEqualTo(hasher.hash("DINNER01234567"));
    }

    @Test
    void rejectsCharactersThatInviteNormalizationMustNotCorrect() {
        InviteCodeHasher hasher = new InviteCodeHasher("test-secret-at-least-32-characters");

        assertThatThrownBy(() -> hasher.hash("DINNER 0123 45O7"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invite code is invalid");
    }
}
