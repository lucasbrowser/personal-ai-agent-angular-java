package com.jarvis.ai.auth.controller;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.web.csrf.CsrfToken;
import com.jarvis.ai.auth.dto.LoginRequest;
import com.jarvis.ai.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    @Value("${jarvis.auth.secure-cookie:false}") private boolean secure;
    public AuthController(AuthService auth) { this.auth=auth; }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        var session=auth.login(request);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
            .body(Map.of("accessToken",session.accessToken(),"user",session.user()));
    }
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        String token=cookie(request,"jarvis_refresh");
        var session=auth.refresh(token == null ? "" : token);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
            .body(Map.of("accessToken",session.accessToken(),"user",session.user()));
    }
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        auth.logout(cookie(request,"jarvis_refresh"));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }
    @GetMapping("/me")
    public Object me(@AuthenticationPrincipal Jwt principal) { return auth.current(java.util.UUID.fromString(principal.getSubject())); }

    private ResponseCookie refreshCookie(String value) { return ResponseCookie.from("jarvis_refresh",value).httpOnly(true).secure(secure).sameSite("Strict").path("/api/v1/auth").maxAge(java.time.Duration.ofDays(7)).build(); }
    private ResponseCookie clearCookie() { return ResponseCookie.from("jarvis_refresh","").httpOnly(true).secure(secure).sameSite("Strict").path("/api/v1/auth").maxAge(0).build(); }
    private String cookie(HttpServletRequest request,String name) {
        if(request.getCookies()==null)return null;
        for(var c:request.getCookies())if(name.equals(c.getName()))return c.getValue();
        return null;
    }
}
