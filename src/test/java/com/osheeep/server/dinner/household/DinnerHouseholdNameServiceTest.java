package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.auth.wechat.WechatUserIdentityEntity;
import com.osheeep.server.auth.wechat.WechatUserIdentityMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyGateway;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyResult;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyUnavailableException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class DinnerHouseholdNameServiceTest {

    @Mock private WechatUserIdentityMapper identityMapper;
    @Mock private DinnerTextSafetyGateway gateway;

    @Test
    void normalizesNfcAndTrimsBothUnicodeWhitespaceFamilies() {
        DinnerHouseholdNameService service = service();

        assertThat(service.normalize("\u2007e\u0301家\u00a0")).isEqualTo("é家");
        assertThat(service.normalize("Ａ的小家")).isEqualTo("Ａ的小家");
    }

    @Test
    void countsEmojiByUnicodeCodePointRatherThanUtf16Length() {
        DinnerHouseholdNameService service = service();
        String thirtyEmoji = "😀".repeat(30);

        assertThat(service.normalize(thirtyEmoji)).isEqualTo(thirtyEmoji);
        assertValidationError(() -> service.normalize(thirtyEmoji + "😀"));
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void rejectsBlankMalformedOrInvisibleNames(String input) {
        assertValidationError(() -> service().normalize(input));
    }

    @Test
    void customCreateNameIsModeratedAfterNormalization() {
        DinnerHouseholdNameService service = service();
        when(identityMapper.selectOne(any())).thenReturn(identity("openid-7"));
        when(gateway.check("openid-7", "é家", "é家"))
                .thenReturn(DinnerTextSafetyResult.PASS);

        assertThat(service.prepareForCreate(7L, "  e\u0301家  ")).isEqualTo("é家");

        verify(gateway).check("openid-7", "é家", "é家");
    }

    @Test
    void omittedCreateNameUsesStaticDefaultWithoutExternalModeration() {
        DinnerHouseholdNameService service = service();

        assertThat(service.prepareForCreate(7L, null)).isEqualTo("我们的小家");
        assertThat(service.prepareForCreate(7L, "")).isEqualTo("我们的小家");
        assertThat(service.prepareForCreate(7L, " \t\u3000")).isEqualTo("我们的小家");

        verifyNoInteractions(identityMapper, gateway);
    }

    @Test
    void rejectedNameMapsToHouseholdSpecificWireError() {
        DinnerHouseholdNameService service = service();
        when(identityMapper.selectOne(any())).thenReturn(identity("openid-7"));
        when(gateway.check(any(), any(), any())).thenReturn(DinnerTextSafetyResult.REJECT);

        assertBusinessError(
                () -> service.moderate(7L, "新名字"),
                ErrorCode.DINNER_HOUSEHOLD_NAME_REJECTED);
    }

    @Test
    void genericGatewayFailureMapsToHouseholdSpecificUnavailableError() {
        DinnerHouseholdNameService service = service();
        when(identityMapper.selectOne(any())).thenReturn(identity("openid-7"));
        when(gateway.check(any(), any(), any()))
                .thenThrow(new DinnerTextSafetyUnavailableException());

        assertBusinessError(
                () -> service.moderate(7L, "新名字"),
                ErrorCode.DINNER_HOUSEHOLD_MODERATION_UNAVAILABLE);
    }

    @Test
    void missingOrBlankWechatIdentityFailsClosedBeforeGateway() {
        DinnerHouseholdNameService service = service();
        when(identityMapper.selectOne(any())).thenReturn(null, identity("  "));

        assertBusinessError(
                () -> service.moderate(7L, "新名字"),
                ErrorCode.DINNER_HOUSEHOLD_MODERATION_UNAVAILABLE);
        assertBusinessError(
                () -> service.moderate(7L, "新名字"),
                ErrorCode.DINNER_HOUSEHOLD_MODERATION_UNAVAILABLE);

        verify(gateway, never()).check(any(), any(), any());
    }

    @Test
    void householdNameErrorsUseStableHttpContracts() {
        assertThat(ErrorCode.DINNER_HOUSEHOLD_NAME_REJECTED.httpStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ErrorCode.DINNER_HOUSEHOLD_MODERATION_UNAVAILABLE.httpStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private DinnerHouseholdNameService service() {
        return new DinnerHouseholdNameService(identityMapper, gateway);
    }

    private WechatUserIdentityEntity identity(String openid) {
        WechatUserIdentityEntity identity = new WechatUserIdentityEntity();
        identity.setUserId(7L);
        identity.setOpenid(openid);
        return identity;
    }

    private static Stream<String> invalidNames() {
        return Stream.of(
                "",
                "   ",
                String.valueOf((char) 0) + "家",
                "家\u200b",
                "家\u202e名",
                "家" + Character.toString(0x2028) + "名",
                "家" + Character.toString(0x2029) + "名",
                String.valueOf((char) 0xd83d),
                String.valueOf((char) 0xde00));
    }

    private void assertValidationError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertBusinessError(call, ErrorCode.VALIDATION_ERROR);
    }

    private void assertBusinessError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(errorCode));
    }
}
