package com.jarvis.ai.auth.bootstrap;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.jarvis.ai.auth.repository.UserRepository;

@Component
public class AdminUserInitializer implements ApplicationRunner {
    private final UserRepository users; private final PasswordEncoder encoder;
    @Value("${jarvis.admin.email:}") private String email;
    @Value("${jarvis.admin.password:}") private String password;
    public AdminUserInitializer(UserRepository users, PasswordEncoder encoder) { this.users=users; this.encoder=encoder; }
    @Override @Transactional public void run(ApplicationArguments args) {
        if(email.isBlank() || password.isBlank()) throw new IllegalStateException("JARVIS_ADMIN_EMAIL and JARVIS_ADMIN_PASSWORD are required");
        if(password.length()<12) throw new IllegalStateException("JARVIS_ADMIN_PASSWORD must contain at least 12 characters");
        String normalized=email.trim().toLowerCase(java.util.Locale.ROOT);
        if(users.findByEmail(normalized).isEmpty()) users.create(UUID.randomUUID(), "Jarvis Administrator", normalized, encoder.encode(password));
    }
}
