package com.osheeep.server.dinner.household;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyGateway;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyResult;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyUnavailableException;
import java.text.Normalizer;
import org.springframework.stereotype.Service;

@Service
public class DinnerHouseholdNameService {

    static final String DEFAULT_NAME = "我们的小家";
    private static final int MAX_CODE_POINTS = 30;

    private final WechatUserIdentityMapper identityMapper;
    private final DinnerTextSafetyGateway textSafetyGateway;

    public DinnerHouseholdNameService(
            WechatUserIdentityMapper identityMapper,
            DinnerTextSafetyGateway textSafetyGateway
    ) {
        this.identityMapper = identityMapper;
        this.textSafetyGateway = textSafetyGateway;
    }

    public String prepareForCreate(Long userId, String requestedName) {
        if (requestedName == null
                || requestedName.isEmpty()
                || containsOnlyTrimmableWhitespace(requestedName)) {
            return DEFAULT_NAME;
        }
        return moderate(userId, requestedName);
    }

    public String moderate(Long userId, String rawName) {
        String normalizedName = normalize(rawName);
        WechatUserIdentityEntity identity = identityMapper.selectOne(
                Wrappers.<WechatUserIdentityEntity>lambdaQuery()
                        .eq(WechatUserIdentityEntity::getUserId, userId)
                        .last("LIMIT 1"));
        if (identity == null
                || identity.getOpenid() == null
                || identity.getOpenid().isBlank()) {
            throw moderationUnavailable();
        }

        DinnerTextSafetyResult result;
        try {
            result = textSafetyGateway.check(
                    identity.getOpenid(), normalizedName, normalizedName);
        } catch (DinnerTextSafetyUnavailableException exception) {
            throw moderationUnavailable();
        }
        if (result == DinnerTextSafetyResult.REJECT) {
            throw new BusinessException(ErrorCode.DINNER_HOUSEHOLD_NAME_REJECTED);
        }
        if (result != DinnerTextSafetyResult.PASS) {
            throw moderationUnavailable();
        }
        return normalizedName;
    }

    public String normalize(String rawName) {
        if (rawName == null) {
            throw invalidName();
        }
        requireWellFormedUtf16(rawName);
        String normalized = Normalizer.normalize(rawName, Normalizer.Form.NFC);
        int start = 0;
        int end = normalized.length();
        while (start < end) {
            int codePoint = normalized.codePointAt(start);
            if (!isTrimmableWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = normalized.codePointBefore(end);
            if (!isTrimmableWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }

        String trimmed = normalized.substring(start, end);
        int codePointCount = trimmed.codePointCount(0, trimmed.length());
        if (codePointCount < 1 || codePointCount > MAX_CODE_POINTS) {
            throw invalidName();
        }
        trimmed.codePoints().forEach(this::requireVisibleCodePoint);
        return trimmed;
    }

    private void requireWellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalidName();
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw invalidName();
            }
        }
    }

    private void requireVisibleCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE) {
            throw invalidName();
        }
    }

    private boolean containsOnlyTrimmableWhitespace(String value) {
        return value.codePoints().allMatch(this::isTrimmableWhitespace);
    }

    private boolean isTrimmableWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private BusinessException invalidName() {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "Household name is invalid");
    }

    private BusinessException moderationUnavailable() {
        return new BusinessException(ErrorCode.DINNER_HOUSEHOLD_MODERATION_UNAVAILABLE);
    }
}
