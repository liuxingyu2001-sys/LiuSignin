package com.liuxingyu.liusignin.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * config.yml 的不可变快照。重载时整体替换，避免运行中读到半套新旧值。
 */
public record SigninConfig(
        ZoneId timezone,
        LocalTime resetTime,
        AutoSign autoSign,
        Broadcast broadcast,
        Reminder reminder,
        int cooldownMs,
        int leaderboardRefreshSeconds,
        Mailbox mailbox,
        boolean debug,
        String tablePrefix,
        int logRetainDays,
        Makeup makeup,
        boolean retryPending,
        List<String> fallbackRewards
) {

    public record AutoSign(boolean enabled, int delayTicks) {
    }

    public record Broadcast(boolean sign, boolean firstOfDay) {
    }

    public record Reminder(boolean onJoin, int delayTicks, boolean oncePerDay) {
    }

    public record Mailbox(boolean autoClaimOnJoin, int claimBatch) {
    }

    public record Makeup(boolean enabled, int maxDays, int cardsPerMakeup, boolean keepStreak,
                         boolean rewardFromDate) {
    }

    public static SigninConfig load(FileConfiguration yaml) {
        ZoneId zone = parseZone(yaml.getString("settings.timezone", "Asia/Shanghai"));
        LocalTime reset = parseTime(yaml.getString("settings.reset-time", "00:00"));

        return new SigninConfig(
                zone,
                reset,
                new AutoSign(
                        yaml.getBoolean("settings.auto-sign.enabled", false),
                        Math.max(1, yaml.getInt("settings.auto-sign.delay-ticks", 40))
                ),
                new Broadcast(
                        yaml.getBoolean("settings.broadcast.sign", false),
                        yaml.getBoolean("settings.broadcast.first-of-day", true)
                ),
                new Reminder(
                        yaml.getBoolean("settings.reminder.on-join", true),
                        Math.max(1, yaml.getInt("settings.reminder.delay-ticks", 60)),
                        yaml.getBoolean("settings.reminder.once-per-day", true)
                ),
                Math.max(0, yaml.getInt("settings.cooldown-ms", 1500)),
                Math.max(5, yaml.getInt("settings.leaderboard-refresh-seconds", 30)),
                new Mailbox(
                        yaml.getBoolean("settings.mailbox.auto-claim-on-join", true),
                        Math.max(1, Math.min(45, yaml.getInt("settings.mailbox.claim-batch", 45)))
                ),
                yaml.getBoolean("settings.debug", false),
                sanitizePrefix(yaml.getString("database.table-prefix", "liusignin_")),
                Math.max(0, yaml.getInt("database.log-retain-days", 180)),
                new Makeup(
                        yaml.getBoolean("makeup.enabled", true),
                        Math.max(1, yaml.getInt("makeup.max-days", 7)),
                        Math.max(1, yaml.getInt("makeup.cards-per-makeup", 1)),
                        yaml.getBoolean("makeup.keep-streak", true),
                        yaml.getBoolean("makeup.reward-from-date", true)
                ),
                yaml.getBoolean("recovery.retry-pending", true),
                yaml.getStringList("rewards.fallback")
        );
    }

    /** 安全的默认配置（配置损坏时兜底，避免 NPE）。 */
    public static SigninConfig defaults() {
        return new SigninConfig(
                ZoneId.of("Asia/Shanghai"),
                LocalTime.MIDNIGHT,
                new AutoSign(false, 40),
                new Broadcast(false, true),
                new Reminder(true, 60, true),
                1500,
                30,
                new Mailbox(true, 45),
                false,
                "liusignin_",
                180,
                new Makeup(true, 7, 1, true, true),
                true,
                List.of()
        );
    }

    private static ZoneId parseZone(String raw) {
        try {
            return ZoneId.of(raw == null ? "Asia/Shanghai" : raw.trim());
        } catch (RuntimeException error) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private static LocalTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalTime.MIDNIGHT;
        }
        try {
            return LocalTime.parse(raw.trim(), DateTimeFormatter.ofPattern("H:mm", Locale.ROOT));
        } catch (RuntimeException error) {
            return LocalTime.MIDNIGHT;
        }
    }

    /** 表前缀只允许字母、数字、下划线，避免配置写错导致 SQL 注入或建表失败。 */
    private static String sanitizePrefix(String raw) {
        String prefix = raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_]", "");
        return prefix.isEmpty() ? "liusignin_" : prefix;
    }

    /** 兼容旧配置读取方式。 */
    public static SigninConfig load(ConfigurationSection section) {
        if (section instanceof FileConfiguration file) {
            return load(file);
        }
        return defaults();
    }
}
