package com.osheeep.server.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.osheeep.server.common.error.ErrorCode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String errorCode,
        String message,
        T data,
        String requestId
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, null, "OK", data, RequestIdFilter.currentRequestId());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, errorCode.name(), message, null, RequestIdFilter.currentRequestId());
    }
}
