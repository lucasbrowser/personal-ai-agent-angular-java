package com.jarvis.ai.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.jarvis.ai.auth.dto.LoginRequest;
import com.jarvis.ai.auth.dto.UserResponse;
import com.jarvis.ai.auth.entity.AuthUser;
import com.jarvis.ai.auth.repository.RefreshTokenRepository;
import com.jarvis.ai.auth.repository.UserRepository;

@Service
public class AuthService {
    public record Session(String accessToken, String refreshToken, UserResponse user) { }
    private final UserRepository users; private final RefreshTokenRepository tokens;
    private final PasswordEncoder passwords; private final JwtEncoder jwt;
    private final SecureRandom random = new SecureRandom();
    public AuthService(UserRepository users, RefreshTokenRepository tokens, PasswordEncoder passwords, JwtEncoder jwt) { this.users=users; this.tokens=tokens; this.passwords=passwords; this.jwt=jwt; }

    @Transactional
    public Session login(LoginRequest request) {
        AuthUser user = users.findByEmail(request.email().trim().toLowerCase()).orElse(null);
        if (user == null || !user.enabled() || !passwords.matches(request.password(), user.passwordHash())) throw new BadCredentialsException("Invalid credentials");
        return issue(user);
    }
    @Transactional
    public Session refresh(String raw) {
        String hash = hash(raw); Instant now = Instant.now();
        UUID userId = tokens.findActiveUser(hash, now).orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        if (!tokens.revoke(hash, now)) throw new BadCredentialsException("Invalid refresh token");
        AuthUser user = users.findById(userId).filter(AuthUser::enabled).orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        return issue(user);
    }
    @Transactional public void logout(String raw) { if (raw != null && !raw.isBlank()) tokens.revoke(hash(raw), Instant.now()); }
    public UserResponse current(UUID id) { return users.findById(id).filter(AuthUser::enabled).map(this::view).orElseThrow(() -> new BadCredentialsException("Invalid user")); }
    private Session issue(AuthUser user) {
        Instant now=Instant.now();
        var claims=JwtClaimsSet.builder().issuer("jarvis").issuedAt(now).expiresAt(now.plusSeconds(900)).subject(user.id().toString()).claim("email",user.email()).claim("name",user.name()).claim("roles", java.util.List.of(user.role())).build();
        String access=jwt.encode(JwtEncoderParameters.from(org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        byte[] bytes=new byte[48]; random.nextBytes(bytes); String refresh=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.create(UUID.randomUUID(), user.id(), hash(refresh), now.plusSeconds(604800));
        return new Session(access, refresh, view(user));
    }
    private UserResponse view(AuthUser user) { return new UserResponse(user.id(), user.name(), user.email(), user.role()); }
    private String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
