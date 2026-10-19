package com.liuxingyu.liusignin.reward;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * rewards.yml 的解析结果：按 priority 从高到低排列的奖励组。
 */
public final class RewardTable {

    private final List<RewardGroup> groups;
    private final List<Reward> fallback;
    private final Logger logger;

    private RewardTable(List<RewardGroup> groups, List<Reward> fallback, Logger logger) {
        this.groups = groups;
        this.fallback = fallback;
        this.logger = logger;
    }

    public static RewardTable load(FileConfiguration yaml, List<String> fallbackRaw, Logger logger) {
        List<Reward> fallback = parseList(fallbackRaw, logger, "rewards.fallback");
        List<RewardGroup> groups = new ArrayList<>();

        ConfigurationSection root = yaml.getConfigurationSection("groups");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                try {
                    RewardGroup group = parseGroup(id, section, logger);
                    if (group != null) {
                        groups.add(group);
                    }
                } catch (RuntimeException error) {
                    logger.warning("奖励组 " + id + " 解析失败：" + error.getMessage());
                }
            }
        }
        groups.sort(Comparator.comparingInt(RewardGroup::priority).reversed());

        if (groups.isEmpty()) {
            logger.warning("rewards.yml 中没有可用的奖励组，使用 rewards.fallback 兜底");
            groups.add(RewardGroup.calendar("default", "", 0, Map.of(), fallback));
        }
        return new RewardTable(List.copyOf(groups), List.copyOf(fallback), logger);
    }

    private static RewardGroup parseGroup(String id, ConfigurationSection section, Logger logger) {
        String permission = section.getString("permission", "");
        int priority = section.getInt("priority", 0);
        String modeRaw = section.getString("mode", "calendar");
        RewardGroup.Mode mode = "infinite".equalsIgnoreCase(modeRaw)
                ? RewardGroup.Mode.INFINITE
                : RewardGroup.Mode.CALENDAR;

        if (mode == RewardGroup.Mode.INFINITE) {
            ConfigurationSection infinite = section.getConfigurationSection("infinite");
            if (infinite == null) {
                logger.warning("奖励组 " + id + " 选择了 infinite 模式但缺少 infinite 段，跳过");
                return null;
            }
            RewardGroup.Infinite data = new RewardGroup.Infinite(
                    infinite.getDouble("money.base", 0),
                    infinite.getDouble("money.per-day", 0),
                    infinite.getDouble("money.max", 0),
                    infinite.getInt("exp.base", 0),
                    infinite.getInt("exp.per-day", 0),
                    infinite.getInt("exp.max", 0),
                    parseList(infinite.getStringList("extras"), logger, id + ".infinite.extras"),
                    parseTiers(infinite.getConfigurationSection("tiers"), logger, id)
            );
            return RewardGroup.infinite(id, permission, priority, data);
        }

        ConfigurationSection calendar = section.getConfigurationSection("calendar");
        if (calendar == null) {
            logger.warning("奖励组 " + id + " 选择了 calendar 模式但缺少 calendar 段，跳过");
            return null;
        }
        Map<Integer, List<Reward>> days = new LinkedHashMap<>();
        List<Reward> fallback = List.of();
        for (String key : calendar.getKeys(false)) {
            if ("default".equalsIgnoreCase(key)) {
                fallback = parseList(calendar.getStringList(key), logger, id + ".calendar.default");
                continue;
            }
            int day;
            try {
                day = Integer.parseInt(key.trim());
            } catch (NumberFormatException error) {
                logger.warning("奖励组 " + id + " 的日期键不是数字：" + key);
                continue;
            }
            if (day < 1 || day > 31) {
                logger.warning("奖励组 " + id + " 的日期超出 1~31：" + day);
                continue;
            }
            days.put(day, parseList(calendar.getStringList(key), logger, id + ".calendar." + day));
        }
        return RewardGroup.calendar(id, permission, priority, days, fallback);
    }

    private static NavigableMap<Integer, List<Reward>> parseTiers(ConfigurationSection section,
                                                                  Logger logger, String groupId) {
        NavigableMap<Integer, List<Reward>> tiers = new TreeMap<>();
        if (section == null) {
            return tiers;
        }
        for (String key : section.getKeys(false)) {
            int day;
            try {
                day = Integer.parseInt(key.trim());
            } catch (NumberFormatException error) {
                logger.warning("奖励组 " + groupId + " 的 tiers 键不是数字：" + key);
                continue;
            }
            tiers.put(day, parseList(section.getStringList(key), logger, groupId + ".tiers." + day));
        }
        return tiers;
    }

    private static List<Reward> parseList(List<String> raw, Logger logger, String path) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Reward> rewards = new ArrayList<>();
        for (String line : raw) {
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                rewards.add(Reward.parse(line));
            } catch (IllegalArgumentException error) {
                logger.warning("奖励配置 " + path + " 中的条目无效（" + line + "）：" + error.getMessage());
            }
        }
        return List.copyOf(rewards);
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /** 按权限匹配奖励组：priority 最高且拥有对应权限（或无需权限）的组。 */
    public RewardGroup groupFor(Player player) {
        if (player == null) {
            return groupFor(permission -> false);
        }
        return groupFor(player::hasPermission);
    }

    public RewardGroup groupFor(Predicate<String> hasPermission) {
        for (RewardGroup group : groups) {
            if (!group.requiresPermission() || hasPermission.test(group.permission())) {
                return group;
            }
        }
        return groups.get(groups.size() - 1);
    }

    public List<Reward> rewardsFor(Player player, LocalDate date, int streak, int total) {
        return rewardsFor(groupFor(player), date, streak, total);
    }

    /** 用已选定的奖励组计算，并在结果为空时回退到 rewards.fallback。 */
    public List<Reward> rewardsFor(RewardGroup group, LocalDate date, int streak, int total) {
        List<Reward> rewards = group.rewardsFor(date, streak, total);
        return rewards.isEmpty() ? fallback : rewards;
    }

    /** 按 id 查组（崩溃恢复时使用）。 */
    public Optional<RewardGroup> groupById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return groups.stream().filter(group -> group.id().equals(id)).findFirst();
    }

    public List<Reward> fallbackRewards() {
        return fallback;
    }

    public List<RewardGroup> groups() {
        return groups;
    }

    public Logger logger() {
        return logger;
    }
}
