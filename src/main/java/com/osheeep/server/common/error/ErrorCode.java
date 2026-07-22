package com.osheeep.server.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    WECHAT_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "WeChat login failed"),
    ACCOUNT_DELETION_IDENTITY_MISMATCH(
            HttpStatus.FORBIDDEN, "WeChat identity does not match current account"),
    DINNER_INVITE_INVALID(HttpStatus.BAD_REQUEST, "Invite code is invalid"),
    DINNER_INVITE_EXPIRED(HttpStatus.BAD_REQUEST, "Invite code has expired"),
    DINNER_HOUSEHOLD_FULL(HttpStatus.CONFLICT, "Household already has two members"),
    DINNER_ALREADY_IN_HOUSEHOLD(HttpStatus.CONFLICT, "User already belongs to a household"),
    DINNER_HOUSEHOLD_REQUIRED(HttpStatus.CONFLICT, "An active household is required"),
    DINNER_HOUSEHOLD_VERSION_CONFLICT(
            HttpStatus.CONFLICT, "Household state changed while the request was running"),
    DINNER_HOUSEHOLD_OWNER_REQUIRED(
            HttpStatus.FORBIDDEN, "Household owner permission is required"),
    DINNER_HOUSEHOLD_OWNER_CANNOT_LEAVE(
            HttpStatus.CONFLICT, "Household owner must transfer ownership or dissolve the household"),
    DINNER_HOUSEHOLD_MEMBER_NOT_FOUND(
            HttpStatus.NOT_FOUND, "Household member was not found"),
    DINNER_HOUSEHOLD_MEMBER_STATE_CONFLICT(
            HttpStatus.CONFLICT, "Household member state changed while the request was running"),
    DINNER_HOUSEHOLD_NAME_MISMATCH(
            HttpStatus.UNPROCESSABLE_ENTITY, "Household name does not match"),
    DINNER_HOUSEHOLD_NAME_REJECTED(
            HttpStatus.UNPROCESSABLE_ENTITY, "Household name was rejected"),
    DINNER_HOUSEHOLD_MODERATION_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Household name moderation is temporarily unavailable"),
    DINNER_HOUSEHOLD_IDENTITY_MISMATCH(
            HttpStatus.FORBIDDEN, "WeChat identity does not match current account"),
    DINNER_HOUSEHOLD_OPERATION_CONFLICT(
            HttpStatus.CONFLICT, "Household operation key was already used differently"),
    DINNER_MENU_EMPTY(HttpStatus.BAD_REQUEST, "Dinner menu must contain at least one recipe"),
    DINNER_MENU_VERSION_CONFLICT(HttpStatus.CONFLICT, "Dinner menu was updated by another member"),
    DINNER_MENU_NOT_CONFIRMED(HttpStatus.CONFLICT, "Dinner menu must be confirmed before completion"),
    DINNER_MENU_COMPLETED(HttpStatus.CONFLICT, "Dinner menu is already completed"),
    DINNER_RECIPE_NOT_FOUND(HttpStatus.NOT_FOUND, "Dinner recipe was not found"),
    DINNER_RECIPE_VERSION_CONFLICT(HttpStatus.CONFLICT, "Dinner recipe was updated elsewhere"),
    DINNER_RECIPE_VALIDATION_FAILED(
            HttpStatus.UNPROCESSABLE_ENTITY, "Dinner recipe is incomplete"),
    DINNER_RECIPE_IMAGE_INVALID(
            HttpStatus.UNPROCESSABLE_ENTITY, "Dinner recipe image is unavailable"),
    DINNER_RECIPE_CONTENT_REJECTED(
            HttpStatus.UNPROCESSABLE_ENTITY, "Dinner recipe content was rejected"),
    DINNER_RECIPE_MODERATION_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Dinner recipe moderation is temporarily unavailable"),
    DINNER_RECIPE_INVALID(HttpStatus.BAD_REQUEST, "Dinner recipe is invalid"),
    DINNER_INGREDIENT_INVALID(HttpStatus.BAD_REQUEST, "Dinner ingredient is invalid"),
    DINNER_INVENTORY_VERSION_CONFLICT(
            HttpStatus.CONFLICT, "Dinner inventory was updated by another member"),
    DINNER_INVENTORY_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "Dinner inventory item was not found"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access is denied"),
    BUSINESS_ERROR(HttpStatus.CONFLICT, "Business rule violation"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
