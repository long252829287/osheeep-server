package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InviteCodeHasherTest {

    @Test
    void normalizesAndHashesWithoutReturningPlaintext() {
        InviteCodeHasher hasher = new InviteCodeHasher("test-secret-at-least-32-characters");

        String hash = hasher.hash("dinner 5268");

        assertThat(hash).isEqualTo(hasher.hash("DINNER5268"));
        assertThat(hash).hasSize(64).doesNotContain("DINNER", "5268");
    }
}
