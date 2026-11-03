package com.jarvis.ai.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.crypto.SecretKey;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        var decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            boolean validSubject;
            try { java.util.UUID.fromString(jwt.getSubject()); validSubject = true; }
            catch (RuntimeException ex) { validSubject = false; }
            Object roles = jwt.getClaims().get("roles");
            boolean validRoles = roles instanceof java.util.Collection<?> values && !values.isEmpty()
                    && values.stream().allMatch(value -> value instanceof String role && !role.isBlank());
            boolean validProfile = jwt.getClaimAsString("email") != null && !jwt.getClaimAsString("email").isBlank()
                    && jwt.getClaimAsString("name") != null && !jwt.getClaimAsString("name").isBlank();
            return validSubject && validRoles && validProfile
                    ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
                    : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                            new OAuth2Error("invalid_token", "Required JWT claims are missing or invalid", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("jarvis"), requiredClaims));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, @Value("${jarvis.frontend-origin}") String origin) throws Exception {
        var csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookiePath("/");
        http.cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.csrfTokenRepository(csrfRepo).csrfTokenRequestHandler(new org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler())
                .requireCsrfProtectionMatcher(request -> "POST".equals(request.getMethod()) &&
                    (request.getRequestURI().endsWith("/refresh") || request.getRequestURI().endsWith("/logout"))))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/api/v1/health", "/error", "/actuator/health/**").permitAll()
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/csrf").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> writeError(res, 401, "UNAUTHORIZED", "Authentication required"))
                .accessDeniedHandler((req,res,ex) -> writeError(res, 403, "FORBIDDEN", "Access denied")))
            .addFilterBefore(new OriginValidationFilter(origin), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles"); authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter(); converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message) throws java.io.IOException {
        response.setStatus(status); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":{\"code\":\""+code+"\",\"message\":\""+message+"\"}}");
    }
    static class OriginValidationFilter extends OncePerRequestFilter {
        private final String expected;
        OriginValidationFilter(String expected) { this.expected = expected; }
        @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
            boolean authPost = req.getRequestURI().startsWith("/api/v1/auth/") && "POST".equals(req.getMethod());
            String origin = req.getHeader("Origin");
            if (authPost && (origin == null || !expected.equals(origin))) { writeError(res, 403, "INVALID_ORIGIN", "Request origin is not allowed"); return; }
            chain.doFilter(req,res);
        }
    }
}
