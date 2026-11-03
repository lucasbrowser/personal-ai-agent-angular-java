package com.jarvis.ai.auth.repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshTokenRepository {
    private final JdbcTemplate jdbc;
    public RefreshTokenRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void create(UUID id, UUID userId, String hash, Instant expires) {
        jdbc.update("insert into jarvis.refresh_tokens(id,user_id,token_hash,expires_at) values(?,?,?,?)", id, userId, hash, Timestamp.from(expires));
    }
    public Optional<UUID> findActiveUser(String hash, Instant now) {
        return jdbc.query("select user_id from jarvis.refresh_tokens where token_hash=? and revoked_at is null and expires_at > ?",
            (rs,n) -> rs.getObject(1, UUID.class), hash, Timestamp.from(now)).stream().findFirst();
    }
    public boolean revoke(String hash, Instant now) {
        return jdbc.update("update jarvis.refresh_tokens set revoked_at=? where token_hash=? and revoked_at is null and expires_at > ?", Timestamp.from(now), hash, Timestamp.from(now)) == 1;
    }
    public void revokeAll(UUID userId, Instant now) {
        jdbc.update("update jarvis.refresh_tokens set revoked_at=? where user_id=? and revoked_at is null", Timestamp.from(now), userId);
    }
}
