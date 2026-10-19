package com.liuxingyu.liusignin.hook;

import com.liuxingyu.liusignin.LiuSigninPlugin;
import com.liuxingyu.liusignin.data.PlayerSigninData;
import com.liuxingyu.liusignin.reward.Reward;
import com.liuxingyu.liusignin.reward.RewardGroup;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * PlaceholderAPI 变量：{@code %liusignin_<key>%}。
 *
 * <p>所有值都读内存缓存，不阻塞主线程；缓存未加载时返回 0 / false。</p>
 */
public final class PlaceholderHook {

    private PlaceholderHook() {
    }

    public static void register(LiuSigninPlugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            plugin.getLogger().info("未安装 PlaceholderAPI，跳过变量注册");
            return;
        }
        try {
            new Expansion(plugin).register();
            plugin.getLogger().info("PlaceholderAPI 变量已注册：%liusignin_<key>%");
        } catch (Throwable error) {
            plugin.getLogger().warning("注册 PlaceholderAPI 变量失败：" + error.getMessage());
        }
    }

    private static final class Expansion extends PlaceholderExpansion {

        private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

        private final LiuSigninPlugin plugin;

        private Expansion(LiuSigninPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public String getIdentifier() {
            return "liusignin";
        }

        @Override
        public String getAuthor() {
            return "liuxingyu2001";
        }

        @Override
        public String getVersion() {
            return plugin.getPluginMeta().getVersion();
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            if (player == null || params == null) {
                return "";
            }
            var uuid = player.getUniqueId();
            var service = plugin.service();
            String key = params.toLowerCase(Locale.ROOT);
            return switch (key) {
                case "signed", "signed_today" -> service.isSignedToday(uuid) ? "true" : "false";
                case "streak" -> String.valueOf(service.streak(uuid));
                case "total" -> String.valueOf(service.total(uuid));
                case "best" -> String.valueOf(service.best(uuid));
                case "cards" -> String.valueOf(service.cards(uuid));
                case "today_count" -> String.valueOf(service.todayCount());
                case "rank_streak" -> String.valueOf(service.rank(uuid, false));
                case "rank_total" -> String.valueOf(service.rank(uuid, true));
                case "month" -> YearMonth.now(plugin.time().zone()).format(MONTH);
                case "next_reward" -> nextReward(player);
                case "last_sign" -> {
                    PlayerSigninData data = service.data(uuid);
                    yield data == null || data.lastSignDate() == null
                            ? "" : data.lastSignDate().toString();
                }
                default -> null;
            };
        }

        private String nextReward(OfflinePlayer player) {
            PlayerSigninData data = plugin.service().data(player.getUniqueId());
            if (data == null) {
                return "";
            }
            LocalDate today = plugin.time().today();
            int nextStreak;
            if (data.hasSigned(today)) {
                nextStreak = data.streak() + 1;
            } else if (data.lastSignDate() != null && data.lastSignDate().plusDays(1).equals(today)) {
                nextStreak = data.streak() + 1;
            } else {
                nextStreak = 1;
            }
            Player online = player.isOnline() ? player.getPlayer() : null;
            RewardGroup group = plugin.rewards().groupFor(online);
            List<Reward> rewards = plugin.rewards()
                    .rewardsFor(group, today, nextStreak, data.total() + 1);
            if (rewards.isEmpty()) {
                return "无";
            }
            return rewards.stream()
                    .map(reward -> reward.describe(plugin.items()))
                    .collect(java.util.stream.Collectors.joining("，"));
        }
    }
}
