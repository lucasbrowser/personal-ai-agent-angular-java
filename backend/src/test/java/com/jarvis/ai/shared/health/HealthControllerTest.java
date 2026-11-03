package com.jarvis.ai.shared.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HealthControllerTest {

    private final HealthController controller = new HealthController();

    @Test
    void shouldReturnApplicationHealth() {
        var response = controller.health();

        assertThat(response).containsEntry("application", "Jarvis Personal AI Agent");
        assertThat(response).containsEntry("status", "UP");
        assertThat(response).containsKey("timestamp");
    }
}
