package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InviteCodeGeneratorTest {

    @Test
    void generatesEightCrockfordCharactersInDisplayGroups() {
        AtomicInteger next = new AtomicInteger();
        SecureRandom random = new SecureRandom() {
            @Override
            public int nextInt(int bound) {
                assertThat(bound).isEqualTo(32);
                return next.getAndIncrement();
            }
        };

        assertThat(new InviteCodeGenerator(random).generate())
                .isEqualTo("DINNER 0123 4567");
        assertThat(next).hasValue(8);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DINNER01234567",
            "dinner 0123 4567",
            "Dinner-0123-4567",
            "DINNER 01-23 45-67"
    })
    void normalizesNewCodesWithoutCorrectingCharacters(String input) {
        assertThat(InviteCodeGenerator.normalize(input))
                .isEqualTo("DINNER 0123 4567");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DINNER5268", "dinner 5268", "Dinner-52-68"})
    void keepsLegacyFourDigitCodesValid(String input) {
        assertThat(InviteCodeGenerator.normalize(input)).isEqualTo("DINNER 5268");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "DINNER 0123 456",
            "DINNER 0123 45678",
            "DINNER 0123 45I7",
            "DINNER 0123 45L7",
            "DINNER 0123 45O7",
            "DINNER 0123 45U7",
            "DINNER 0123\t4567",
            "DINNER 0123\u00A04567",
            "DINNER 0123_4567",
            "SUPPER 0123 4567",
            "ＤＩＮＮＥＲ 0123 4567"
    })
    void rejectsAmbiguousOrNonAsciiInput(String input) {
        assertThatThrownBy(() -> InviteCodeGenerator.normalize(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invite code is invalid");
    }

    @Test
    void rejectsNullInput() {
        assertThatThrownBy(() -> InviteCodeGenerator.normalize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invite code is invalid");
    }
}
