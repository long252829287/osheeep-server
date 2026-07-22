package com.osheeep.server.dinner.moderation;

public final class DinnerTextSafetyUnavailableException extends RuntimeException {

    public DinnerTextSafetyUnavailableException() {
        super("Dinner text safety is temporarily unavailable");
    }
}
