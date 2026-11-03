package com.jarvis.ai.auth.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.jarvis.ai.auth.entity.AuthUser;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;
    public UserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<AuthUser> findByEmail(String email) {
        return jdbc.query("select id,name,email,password_hash,role,enabled from jarvis.users where email = ?",
            (rs, n) -> new AuthUser(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"), rs.getString("password_hash"), rs.getString("role"), rs.getBoolean("enabled")), email).stream().findFirst();
    }
    public Optional<AuthUser> findById(UUID id) {
        return jdbc.query("select id,name,email,password_hash,role,enabled from jarvis.users where id = ?",
            (rs, n) -> new AuthUser(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"), rs.getString("password_hash"), rs.getString("role"), rs.getBoolean("enabled")), id).stream().findFirst();
    }
    public void create(UUID id, String name, String email, String hash) {
        jdbc.update("insert into jarvis.users(id,name,email,password_hash,role,enabled) values(?,?,?,?, 'ADMIN', true)", id, name, email, hash);
    }
}
