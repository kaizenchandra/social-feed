package com.example.socialfeed;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.time.*;

import static org.assertj.core.api.Assertions.*;

class CoreTest {
    @Test
    void cursorRoundtrip() {
        var c = new Feeds.Cursor(UUID.randomUUID(), 999);
        assertThat(Feeds.Cursor.decode(c.encode())).isEqualTo(c);
    }

    @Test
    void invalidCursor() {
        for (String c : List.of("!", "abcd", Base64.getUrlEncoder().encodeToString((UUID.randomUUID() + ":-1").getBytes())))
            assertThatThrownBy(() -> Feeds.Cursor.decode(c)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cursorRejectsExtraFieldsAndNonDecimalPosition() {
        for (String suffix : List.of("1:", "1:ignored", "+1", "-0", "1001", "")) {
            String raw = Base64.getUrlEncoder().encodeToString((UUID.randomUUID() + ":" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> Feeds.Cursor.decode(raw)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rankingNormalizedAndDeterministic() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        double a = Ranking.score(now, now, 0, 0, false);
        assertThat(a).isEqualTo(.75);
        assertThat(Ranking.score(now.minusSeconds(86400), now, 0, 0, false)).isLessThan(a);
        assertThat(Ranking.score(now, now, Long.MAX_VALUE, 100, true)).isBetween(0., 1.);
    }

    @Test
    void deletedIsTerminal() {
        for (var state : Contracts.State.values())
            assertThat(Contracts.transition(Contracts.State.DELETED, state)).isEqualTo(state == Contracts.State.DELETED);
        assertThat(Contracts.transition(Contracts.State.PUBLISHED, Contracts.State.DRAFT)).isFalse();
    }
}
