package com.jarvis.ai.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import com.jarvis.ai.auth.dto.LoginRequest;
import com.jarvis.ai.auth.entity.AuthUser;
import com.jarvis.ai.auth.repository.RefreshTokenRepository;
import com.jarvis.ai.auth.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock UserRepository users;
    @Mock RefreshTokenRepository tokens;
    @Mock PasswordEncoder passwords;
    @Mock JwtEncoder jwt;
    @InjectMocks AuthService service;

    @Test
    void rejectsUnknownEmailWithGenericAuthenticationFailure() {
        when(users.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("unknown@example.com", "secret")))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(passwords, jwt, tokens);
    }

    @Test
    void rejectsDisabledUser() {
        var user = new AuthUser(UUID.randomUUID(), "Disabled", "disabled@example.com", "hash", "ADMIN", false);
        when(users.findByEmail(user.email())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(new LoginRequest(user.email(), "secret")))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(passwords, jwt, tokens);
    }
}
