package com.liuxingyu.liusignin.menu;

import com.liuxingyu.liulib.ui.SparrowUi;
import com.liuxingyu.liusignin.LiuSigninPlugin;
import com.liuxingyu.liusignin.api.event.SignSource;
import com.liuxingyu.liusignin.config.SigninConfig;
import com.liuxingyu.liusignin.data.PlayerSigninData;
import com.liuxingyu.liusignin.reward.Reward;
import com.liuxingyu.liusignin.reward.RewardGroup;
import com.liuxingyu.liusignin.service.PlayerState;
import com.liuxingyu.liusignin.service.SigninService;
import com.liuxingyu.liusignin.util.Schedulers;
import com.liuxingyu.liusignin.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 月历签到界面（Sparrow UI）。
 *
 * <p>布局：左侧 7 列为日期格子，右侧 2 列放连续天数、累计天数、补签卡、今日奖励、
 * 排行、邮箱、翻月与关闭按钮。具体槽位与物品全部来自 menus.yml。</p>
 */
public final class SigninMenu {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final LiuSigninPlugin plugin;
    private final Map<UUID, YearMonth> views = new ConcurrentHashMap<>();

    public SigninMenu(LiuSigninPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        plugin.service().state(player.getUniqueId(), false)
                .thenAccept(state -> Schedulers.onPlayer(plugin, player, () -> render(player, state)))
                .exceptionally(error -> {
                    plugin.getLogger().warning("加载签到界面失败：" + error.getMessage());
                    plugin.messages().send(player, "error");
                    return null;
                });
    }

    public void clear(UUID uuid) {
        views.remove(uuid);
    }

    public void render(Player player, PlayerState state) {
        if (!player.isOnline()) {
            return;
        }
        MenuConfig.Calendar config = plugin.menus().calendar();
        SparrowUi ui = plugin.sparrow();
        if (ui == null || !ui.available()) {
            plugin.messages().send(player, "error");
            return;
        }

        LocalDate today = plugin.time().today();
        YearMonth view = views.computeIfAbsent(player.getUniqueId(), ignored -> YearMonth.from(today));
        PlayerSigninData data = state.data();
        int rows = config.rows();
        int dayColumns = config.dayColumns();

        Map<String, String> base = basePlaceholders(player, state, view);

        SparrowUi.MenuBuilder menu = ui.menu(plugin.items().names().parse(
                Text.fill(config.title(), base)), rows);
        ItemStack background = config.background().build(plugin.items(), "", List.of());
        menu.fillEmpty(background);

        LocalDate first = view.atDay(1);
        int weekStart = config.weekStart() == DayOfWeek.MONDAY ? 1 : 7;
        int offset = (first.getDayOfWeek().getValue() - weekStart + 7) % 7;
        boolean showReward = config.states().values().stream().anyMatch(template ->
                template.name().contains("{reward}")
                        || template.lore().stream().anyMatch(line -> line.contains("{reward}")));

        for (int day = 1; day <= view.lengthOfMonth(); day++) {
            int index = offset + day - 1;
            int row = index / dayColumns;
            int column = index % dayColumns;
            if (row < 0 || row >= rows || column >= 9) {
                continue;
            }
            int slot = row * 9 + column;
            LocalDate date = view.atDay(day);
            Map<String, String> placeholders = new LinkedHashMap<>(base);
            placeholders.put("day", String.valueOf(day));
            placeholders.put("date", date.format(DATE_FORMAT));
            placeholders.put("reward", showReward
                    ? rewardSummary(player, state, date, today) : "");
            placeholders.put("cost", String.valueOf(plugin.config().makeup().cardsPerMakeup()));

            String stateKey = stateKey(state, date, today);
            ItemTemplate template = config.state(stateKey);
            if (template == null) {
                template = new ItemTemplate("", Material.GRAY_STAINED_GLASS_PANE, "", List.of(), false);
            }
            ItemStack stack = template.build(plugin.items(),
                    Text.fill(template.name(), placeholders), fillLore(template.lore(), placeholders));
            menu.set(slot, stack, click -> handleDayClick(player, state, date, today));
        }

        for (Map.Entry<String, ItemTemplate> entry : config.info().entrySet()) {
            String key = entry.getKey();
            int slot = config.slot(key, -1);
            if (slot < 0 || slot >= rows * 9) {
                continue;
            }
            ItemTemplate template = entry.getValue();
            ItemStack stack = template.build(plugin.items(),
                    Text.fill(template.name(), base), fillLore(template.lore(), base));
            menu.set(slot, stack, click -> handleButtonClick(player, state, key, view));
        }

        menu.open(player);
    }

    private String stateKey(PlayerState state, LocalDate date, LocalDate today) {
        boolean signed = state.hasSigned(date);
        if (signed) {
            return date.equals(today) ? "signed-today" : "signed";
        }
        if (date.equals(today)) {
            return "today";
        }
        if (date.isBefore(today)) {
            SigninConfig.Makeup makeup = plugin.config().makeup();
            boolean within = !date.isBefore(today.minusDays(makeup.maxDays()));
            return within ? "missed" : "missed-locked";
        }
        return "future";
    }

    private void handleDayClick(Player player, PlayerState state, LocalDate date, LocalDate today) {
        if (state.hasSigned(date)) {
            if (date.equals(today)) {
                plugin.messages().send(player, "already-signed");
            }
            return;
        }
        if (date.equals(today)) {
            plugin.service().sign(player, SignSource.GUI).thenAccept(result ->
                    Schedulers.onPlayer(plugin, player, () -> {
                        switch (result.status()) {
                            case SUCCESS -> {
                                plugin.signinMenu().open(player);
                            }
                            case ALREADY -> plugin.messages().send(player, "already-signed");
                            case COOLDOWN -> plugin.messages().send(player, "cooldown");
                            case NO_PERMISSION -> plugin.messages().send(player, "no-permission");
                            case ERROR -> plugin.messages().send(player, "error");
                        }
                    }));
            return;
        }
        if (!date.isBefore(today)) {
            return;
        }
        SigninConfig.Makeup makeup = plugin.config().makeup();
        if (!makeup.enabled()) {
            plugin.messages().send(player, "makeup-disabled");
            return;
        }
        if (date.isBefore(today.minusDays(makeup.maxDays()))) {
            plugin.messages().send(player, "makeup-out-of-range",
                    "days", String.valueOf(makeup.maxDays()));
            return;
        }
        plugin.makeupMenu().open(player, date, state);
    }

    private void handleButtonClick(Player player, PlayerState state, String key, YearMonth view) {
        switch (key) {
            case "leaderboard" -> plugin.command().sendLeaderboard(player, "streak");
            case "mail" -> plugin.mailMenu().open(player);
            case "prev-month" -> {
                views.put(player.getUniqueId(), view.minusMonths(1));
                render(player, state);
            }
            case "next-month" -> {
                views.put(player.getUniqueId(), view.plusMonths(1));
                render(player, state);
            }
            case "close" -> {
                plugin.sparrow().close(player);
                player.closeInventory();
            }
            default -> {
                // streak / total / cards / next-reward：纯展示
            }
        }
    }

    private Map<String, String> basePlaceholders(Player player, PlayerState state, YearMonth view) {
        PlayerSigninData data = state.data();
        LocalDate today = plugin.time().today();
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("streak", String.valueOf(data.streak()));
        placeholders.put("total", String.valueOf(data.total()));
        placeholders.put("best", String.valueOf(data.bestStreak()));
        placeholders.put("cards", String.valueOf(data.makeupCards()));
        placeholders.put("month", view.format(MONTH_FORMAT));
        placeholders.put("month-name", view.getYear() + " 年 " + view.getMonthValue() + " 月");
        placeholders.put("year", String.valueOf(view.getYear()));
        placeholders.put("today", today.format(DATE_FORMAT));
        placeholders.put("cost", String.valueOf(plugin.config().makeup().cardsPerMakeup()));
        placeholders.put("reward", rewardSummary(player, state, today, today));
        return placeholders;
    }

    /** 某天的奖励预览文本。 */
    public String rewardSummary(Player player, PlayerState state, LocalDate date, LocalDate today) {
        RewardGroup group = plugin.rewards().groupFor(player);
        PlayerSigninData data = state.data();
        int streak = projectedStreak(data, date, today);
        List<Reward> rewards = plugin.rewards().rewardsFor(group, date, streak, data.total() + 1);
        if (rewards.isEmpty()) {
            return "无";
        }
        return rewards.stream()
                .map(reward -> reward.describeForDisplay(plugin.items()))
                .collect(java.util.stream.Collectors.joining("，"));
    }

    private int projectedStreak(PlayerSigninData data, LocalDate date, LocalDate today) {
        LocalDate last = data.lastSignDate();
        if (last != null && last.equals(date)) {
            return Math.max(1, data.streak());
        }
        if (date.equals(today)) {
            return last != null && last.plusDays(1).equals(today) ? data.streak() + 1 : 1;
        }
        return Math.max(1, data.streak());
    }

    private List<String> fillLore(List<String> lines, Map<String, String> placeholders) {
        return lines.stream().map(line -> Text.fill(line, placeholders)).toList();
    }
}
