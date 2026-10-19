package com.liuxingyu.liusignin.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 签到日计算。
 *
 * <p>“签到日”不等于服务器本地自然日：它由配置的时区和重置时间共同决定。
 * 例如 {@code reset-time: 04:00} 时，凌晨 3 点仍算前一天。多台子服必须使用
 * 相同的时区与重置时间，否则同一玩家会在不同服出现“能签 / 不能签”的分歧。</p>
 */
public final class SigninTime {

    private final ZoneId zone;
    private final LocalTime resetTime;

    public SigninTime(ZoneId zone, LocalTime resetTime) {
        this.zone = zone == null ? ZoneId.of("Asia/Shanghai") : zone;
        this.resetTime = resetTime == null ? LocalTime.MIDNIGHT : resetTime;
    }

    /** 当前签到日。 */
    public LocalDate today() {
        return today(Instant.now());
    }

    /** 指定时刻对应的签到日（便于测试）。 */
    public LocalDate today(Instant instant) {
        ZonedDateTime zoned = instant.atZone(zone);
        LocalDate date = zoned.toLocalDate();
        return zoned.toLocalTime().isBefore(resetTime) ? date.minusDays(1) : date;
    }

    /** 当前签到日的“昨天”，用于连续签到判定。 */
    public LocalDate yesterday() {
        return today().minusDays(1);
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalTime resetTime() {
        return resetTime;
    }
}
