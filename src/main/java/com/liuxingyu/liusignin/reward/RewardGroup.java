package com.liuxingyu.liusignin.reward;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 一个奖励组：用权限区分普通 / VIP，用 mode 选择“月历”或“无限递增”。
 */
public final class RewardGroup {

    public enum Mode {
        CALENDAR,
        INFINITE
    }

    private final String id;
    private final String permission;
    private final int priority;
    private final Mode mode;
    private final Map<Integer, List<Reward>> calendar;
    private final List<Reward> calendarDefault;
    private final Infinite infinite;

    private RewardGroup(String id, String permission, int priority, Mode mode,
                        Map<Integer, List<Reward>> calendar, List<Reward> calendarDefault,
                        Infinite infinite) {
        this.id = id;
        this.permission = permission;
        this.priority = priority;
        this.mode = mode;
        this.calendar = calendar == null ? Map.of() : Map.copyOf(calendar);
        this.calendarDefault = calendarDefault == null ? List.of() : List.copyOf(calendarDefault);
        this.infinite = infinite;
    }

    public static RewardGroup calendar(String id, String permission, int priority,
                                       Map<Integer, List<Reward>> days, List<Reward> fallback) {
        return new RewardGroup(id, permission, priority, Mode.CALENDAR, days, fallback, null);
    }

    public static RewardGroup infinite(String id, String permission, int priority, Infinite infinite) {
        return new RewardGroup(id, permission, priority, Mode.INFINITE, Map.of(), List.of(), infinite);
    }

    public String id() {
        return id;
    }

    public String permission() {
        return permission;
    }

    public int priority() {
        return priority;
    }

    public Mode mode() {
        return mode;
    }

    public boolean requiresPermission() {
        return permission != null && !permission.isBlank();
    }

    /**
     * 计算某次签到的奖励。
     *
     * @param date   签到日期（月历模式按日期号取档；无限递增模式忽略）
     * @param streak 本次签到后的连续天数（无线模式按此递增）
     * @param total  本次签到后的累计天数
     */
    public List<Reward> rewardsFor(LocalDate date, int streak, int total) {
        if (mode == Mode.INFINITE) {
            return infinite == null ? List.of() : infinite.rewardsFor(streak);
        }
        return calendarRewards(date);
    }

    private List<Reward> calendarRewards(LocalDate date) {
        int day = Math.max(1, date.getDayOfMonth());
        List<Reward> direct = calendar.get(day);
        if (direct != null) {
            return direct;
        }
        if (!calendarDefault.isEmpty()) {
            return calendarDefault;
        }
        for (int i = day - 1; i >= 1; i--) {
            List<Reward> previous = calendar.get(i);
            if (previous != null) {
                return previous;
            }
        }
        return calendar.values().stream().findFirst().orElse(List.of());
    }

    /** 无限递增配置。 */
    public record Infinite(
            double moneyBase,
            double moneyPerDay,
            double moneyMax,
            int expBase,
            int expPerDay,
            int expMax,
            List<Reward> extras,
            NavigableMap<Integer, List<Reward>> tiers
    ) {

        public Infinite {
            extras = extras == null ? List.of() : List.copyOf(extras);
            tiers = tiers == null ? new TreeMap<>() : new TreeMap<>(tiers);
        }

        public List<Reward> rewardsFor(int streak) {
            int day = Math.max(1, streak);
            List<Reward> out = new ArrayList<>();
            double money = moneyBase + moneyPerDay * (day - 1L);
            if (moneyMax > 0) {
                money = Math.min(moneyMax, money);
            }
            if (money > 0) {
                out.add(Reward.money(money));
            }
            long exp = expBase + (long) expPerDay * (day - 1L);
            if (expMax > 0) {
                exp = Math.min(expMax, exp);
            }
            if (exp > 0) {
                out.add(Reward.exp((int) exp));
            }
            out.addAll(extras);
            tiers.headMap(day, true).values().forEach(out::addAll);
            return Collections.unmodifiableList(out);
        }
    }
}
