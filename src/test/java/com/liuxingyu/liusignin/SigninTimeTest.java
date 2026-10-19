package com.liuxingyu.liusignin;

import com.liuxingyu.liusignin.time.SigninTime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SigninTimeTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void midnightResetUsesNaturalDay() {
        SigninTime time = new SigninTime(SHANGHAI, LocalTime.MIDNIGHT);
        // 2025-01-01 23:30 +08:00
        assertEquals(LocalDate.of(2025, 1, 1),
                time.today(Instant.parse("2025-01-01T15:30:00Z")));
        // 2025-01-02 00:30 +08:00
        assertEquals(LocalDate.of(2025, 1, 2),
                time.today(Instant.parse("2025-01-01T16:30:00Z")));
    }

    @Test
    void resetTimeMovesEarlyMorningToPreviousDay() {
        SigninTime time = new SigninTime(SHANGHAI, LocalTime.of(4, 0));
        // 2025-01-02 03:59 +08:00 → 仍算 1 月 1 日
        assertEquals(LocalDate.of(2025, 1, 1),
                time.today(Instant.parse("2025-01-01T19:59:00Z")));
        // 2025-01-02 04:00 +08:00 → 1 月 2 日
        assertEquals(LocalDate.of(2025, 1, 2),
                time.today(Instant.parse("2025-01-01T20:00:00Z")));
    }
}
