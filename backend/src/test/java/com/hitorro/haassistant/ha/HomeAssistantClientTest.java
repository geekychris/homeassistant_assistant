package com.hitorro.haassistant.ha;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class HomeAssistantClientTest {

    @Test
    void backoff_doubles_until_60s_cap() {
        assertThat(HomeAssistantClient.backoffDelay(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(HomeAssistantClient.backoffDelay(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(HomeAssistantClient.backoffDelay(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(HomeAssistantClient.backoffDelay(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(HomeAssistantClient.backoffDelay(4)).isEqualTo(Duration.ofSeconds(16));
        assertThat(HomeAssistantClient.backoffDelay(5)).isEqualTo(Duration.ofSeconds(32));
        assertThat(HomeAssistantClient.backoffDelay(6)).isEqualTo(Duration.ofSeconds(60));
        assertThat(HomeAssistantClient.backoffDelay(20)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void backoff_handles_negative_attempt_defensively() {
        assertThat(HomeAssistantClient.backoffDelay(-1)).isEqualTo(Duration.ofSeconds(1));
    }
}
