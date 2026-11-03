package com.jarvis.ai.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.jarvis.ai.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.test.util.ReflectionTestUtils;

class AuthControllerCookieTest {
    @Test
    void refreshCookieIsHttpOnlySecureAndStrictSameSite() {
        var controller = new AuthController(mock(AuthService.class));
        ReflectionTestUtils.setField(controller, "secure", true);

        ResponseCookie cookie = ReflectionTestUtils.invokeMethod(controller, "refreshCookie", "opaque-token");

        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
    }
}
