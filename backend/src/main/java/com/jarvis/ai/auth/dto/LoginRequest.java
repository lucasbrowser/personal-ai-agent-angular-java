package com.jarvis.ai.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    @Override public String toString() { return "LoginRequest[email=" + email + ", password=[REDACTED]]"; }
}
