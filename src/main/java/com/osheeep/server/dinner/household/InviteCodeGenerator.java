package com.osheeep.server.dinner.household;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class InviteCodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final String PREFIX = "DINNER";
    private static final String INVALID_MESSAGE = "Invite code is invalid";

    private final SecureRandom secureRandom;

    public InviteCodeGenerator() {
        this(new SecureRandom());
    }

    InviteCodeGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    public String generate() {
        StringBuilder randomPart = new StringBuilder(8);
        for (int index = 0; index < 8; index++) {
            randomPart.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return display(randomPart.toString());
    }

    public static String normalize(String rawCode) {
        if (rawCode == null || rawCode.isEmpty()) {
            throw invalid();
        }
        for (int index = 0; index < rawCode.length(); index++) {
            if (rawCode.charAt(index) > 0x7f) {
                throw invalid();
            }
        }

        String upper = rawCode.toUpperCase(Locale.ROOT);
        if (!upper.startsWith(PREFIX)) {
            throw invalid();
        }
        StringBuilder compactRandomPart = new StringBuilder(8);
        for (int index = PREFIX.length(); index < upper.length(); index++) {
            char character = upper.charAt(index);
            if (character == ' ' || character == '-') {
                continue;
            }
            compactRandomPart.append(character);
        }

        String randomPart = compactRandomPart.toString();
        if (randomPart.length() == 4 && randomPart.chars().allMatch(Character::isDigit)) {
            return PREFIX + " " + randomPart;
        }
        if (randomPart.length() != 8
                || randomPart.chars().anyMatch(character -> ALPHABET.indexOf(character) < 0)) {
            throw invalid();
        }
        return display(randomPart);
    }

    static String compact(String normalizedCode) {
        return normalizedCode.replace(" ", "");
    }

    private static String display(String randomPart) {
        return PREFIX + " " + randomPart.substring(0, 4) + " " + randomPart.substring(4);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID_MESSAGE);
    }
}
