package com.liuxingyu.liusignin.command;

import com.liuxingyu.liusignin.LiuSigninPlugin;
import com.liuxingyu.liusignin.api.event.SignSource;
import com.liuxingyu.liusignin.data.LeaderboardEntry;
import com.liuxingyu.liusignin.data.PlayerSigninData;
import com.liuxingyu.liusignin.service.SigninService;
import com.liuxingyu.liusignin.util.Schedulers;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /signin 命令与 Tab 补全。 */
public final class SigninCommand implements CommandExecutor, TabCompleter {

    private final LiuSigninPlugin plugin;

    public SigninCommand(LiuSigninPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                if (!player.hasPermission("liusignin.gui")) {
                    plugin.messages().send(sender, "no-permission");
                    return true;
                }
                plugin.signinMenu().open(player);
            } else {
                sendHelp(sender);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> sendHelp(sender);
            case "claim", "sign", "checkin" -> claim(sender);
            case "menu", "gui", "calendar" -> menu(sender);
            case "makeup" -> makeup(sender, args);
            case "mail" -> mail(sender, args);
            case "top", "leaderboard" -> top(sender, args);
            case "info" -> info(sender, args);
            case "reload" -> reload(sender);
            case "card" -> card(sender, args);
            case "reset" -> reset(sender, args);
            case "setstreak" -> setStat(sender, args, true);
            case "settotal" -> setStat(sender, args, false);
            case "purge" -> purge(sender, args);
            case "dbtest" -> dbTest(sender);
            default -> plugin.messages().send(sender, "unknown-command");
        }
        return true;
    }

    // ==================================================================
    // 玩家命令
    // ==================================================================

    private void claim(CommandSender sender) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (!player.hasPermission("liusignin.sign")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        plugin.service().sign(player, SignSource.COMMAND).thenAccept(result ->
                Schedulers.global(plugin, () -> {
                    switch (result.status()) {
                        case SUCCESS -> {
                            // 成功消息由服务层发送
                        }
                        case ALREADY -> plugin.messages().send(sender, "already-signed");
                        case COOLDOWN -> plugin.messages().send(sender, "cooldown");
                        case NO_PERMISSION -> plugin.messages().send(sender, "no-permission");
                        case ERROR -> plugin.messages().send(sender, "error");
                    }
                }))
                .exceptionally(error -> {
                    fail(sender, error);
                    return null;
                });
    }

    private void menu(CommandSender sender) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (!player.hasPermission("liusignin.gui")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        plugin.signinMenu().open(player);
    }

    private void makeup(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        LocalDate today = plugin.time().today();
        LocalDate date;
        if (args.length < 2) {
            date = today.minusDays(1);
        } else {
            String raw = args[1].toLowerCase(Locale.ROOT);
            if ("yesterday".equals(raw) || "昨天".equals(raw)) {
                date = today.minusDays(1);
            } else {
                try {
                    date = LocalDate.parse(args[1]);
                } catch (DateTimeParseException error) {
                    plugin.messages().send(sender, "makeup-bad-date");
                    return;
                }
            }
        }
        plugin.service().makeup(player, date).thenAccept(result ->
                Schedulers.global(plugin, () -> {
                    switch (result.status()) {
                        case OK -> {
                            // 成功消息由服务层发送
                        }
                        case DISABLED -> plugin.messages().send(sender, "makeup-disabled");
                        case NO_PERMISSION -> plugin.messages().send(sender, "no-permission");
                        case NO_CARD -> plugin.messages().send(sender, "makeup-no-card",
                                "cost", String.valueOf(plugin.config().makeup().cardsPerMakeup()));
                        case ALREADY -> plugin.messages().send(sender, "makeup-already",
                                "date", date.toString());
                        case OUT_OF_RANGE -> plugin.messages().send(sender, "makeup-out-of-range",
                                "days", String.valueOf(plugin.config().makeup().maxDays()));
                        case FUTURE -> plugin.messages().send(sender, "makeup-future");
                        case ERROR -> plugin.messages().send(sender, "error");
                    }
                }))
                .exceptionally(error -> {
                    fail(sender, error);
                    return null;
                });
    }

    private void mail(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return;
        }
        if (!player.hasPermission("liusignin.mail")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        if (args.length >= 2 && ("all".equalsIgnoreCase(args[1]) || "领取".equals(args[1]))) {
            plugin.mailbox().claimAll(player).thenAccept(count ->
                    Schedulers.onPlayer(plugin, player, () -> {
                        if (count <= 0) {
                            plugin.messages().send(player, "mail-empty");
                        } else {
                            plugin.messages().send(player, "mail-claim-all-done",
                                    "count", String.valueOf(count));
                        }
                    })).exceptionally(error -> {
                fail(sender, error);
                return null;
            });
            return;
        }
        plugin.mailMenu().open(player);
    }

    private void top(CommandSender sender, String[] args) {
        if (sender instanceof Player player && !player.hasPermission("liusignin.leaderboard")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        String type = args.length >= 2 ? args[1] : "streak";
        boolean total = "total".equalsIgnoreCase(type) || "累计".equals(type);
        String key = total ? "total" : "streak";
        plugin.service().top(key).thenAccept(list -> Schedulers.global(plugin, () -> {
            plugin.messages().send(sender, "leaderboard-header", "type", total ? "累计" : "连续");
            if (list.isEmpty()) {
                plugin.messages().send(sender, "leaderboard-empty");
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                LeaderboardEntry entry = list.get(i);
                plugin.messages().send(sender, "leaderboard-entry",
                        "rank", String.valueOf(i + 1),
                        "player", entry.name() == null || entry.name().isBlank() ? "未知" : entry.name(),
                        "value", String.valueOf(total ? entry.total() : entry.streak()));
            }
        })).exceptionally(error -> {
            fail(sender, error);
            return null;
        });
    }

    /** 供签到界面调用。 */
    public void sendLeaderboard(Player player, String type) {
        if (player == null || !player.isOnline()) {
            return;
        }
        top(player, new String[]{"top", type});
    }

    private void info(CommandSender sender, String[] args) {
        String name = args.length >= 2 ? args[1] : (sender instanceof Player p ? p.getName() : null);
        if (name == null) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        OfflinePlayer target = resolve(name);
        if (target == null) {
            plugin.messages().send(sender, "player-not-found", "player", name);
            return;
        }
        UUID uuid = target.getUniqueId();
        plugin.service().state(uuid, true).thenAccept(state -> Schedulers.global(plugin, () -> {
            PlayerSigninData data = state.data();
            plugin.messages().send(sender, "info",
                    "player", name,
                    "streak", String.valueOf(data.streak()),
                    "total", String.valueOf(data.total()),
                    "best", String.valueOf(data.bestStreak()),
                    "cards", String.valueOf(data.makeupCards()));
        })).exceptionally(error -> {
            plugin.messages().send(sender, "error");
            return null;
        });
    }

    // ==================================================================
    // 管理命令
    // ==================================================================

    private void reload(CommandSender sender) {
        if (!admin(sender)) {
            return;
        }
        try {
            plugin.reloadEverything();
            plugin.messages().send(sender, "reload-success");
        } catch (Throwable error) {
            plugin.getLogger().warning("重载失败：" + error.getMessage());
            plugin.messages().send(sender, "reload-failed");
        }
    }

    private void card(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return;
        }
        if (args.length < 4) {
            plugin.messages().send(sender, "unknown-command");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        OfflinePlayer target = resolve(args[2]);
        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException error) {
            plugin.messages().send(sender, "unknown-command");
            return;
        }
        if (target == null) {
            plugin.messages().send(sender, "player-not-found", "player", args[2]);
            return;
        }
        boolean give = "give".equals(action) || "add".equals(action);
        boolean take = "take".equals(action) || "remove".equals(action);
        if (!give && !take && !"set".equals(action)) {
            plugin.messages().send(sender, "unknown-command");
            return;
        }
        if (amount < 0 || (give || take) && amount == 0) {
            plugin.messages().send(sender, "invalid-amount");
            return;
        }
        UUID uuid = target.getUniqueId();
        if (give) {
            plugin.service().addCardsAsync(uuid, amount)
                    .thenAccept(total -> Schedulers.global(plugin, () -> {
                        plugin.messages().send(sender, "card-given",
                                "player", args[2], "amount", String.valueOf(amount));
                        notifyIfOnline(uuid, "card-given", args[2], String.valueOf(amount));
                    }))
                    .exceptionally(error -> {
                        fail(sender, error);
                        return null;
                    });
        } else if (take) {
            plugin.service().takeCardsAsync(uuid, amount)
                    .thenAccept(success -> Schedulers.global(plugin, () -> {
                        if (success) {
                            plugin.messages().send(sender, "card-taken",
                                    "player", args[2], "amount", String.valueOf(amount));
                            notifyIfOnline(uuid, "card-taken", args[2], String.valueOf(amount));
                        } else {
                            plugin.messages().send(sender, "card-insufficient");
                        }
                    }))
                    .exceptionally(error -> {
                        fail(sender, error);
                        return null;
                    });
        } else {
            plugin.service().setCardsAsync(uuid, amount)
                    .thenAccept(total -> Schedulers.global(plugin, () ->
                            plugin.messages().send(sender, "card-set",
                                    "player", args[2], "amount", String.valueOf(total))))
                    .exceptionally(error -> {
                        fail(sender, error);
                        return null;
                    });
        }
    }

    private void reset(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "unknown-command");
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "player-not-found", "player", args[1]);
            return;
        }
        plugin.service().resetAsync(target.getUniqueId()).thenRun(() ->
                        Schedulers.global(plugin, () -> plugin.messages().send(sender, "reset-done",
                                "player", args[1])))
                .exceptionally(error -> {
                    fail(sender, error);
                    return null;
                });
    }

    private void setStat(CommandSender sender, String[] args, boolean streak) {
        if (!admin(sender)) {
            return;
        }
        if (args.length < 3) {
            plugin.messages().send(sender, "unknown-command");
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "player-not-found", "player", args[1]);
            return;
        }
        int value;
        try {
            value = Integer.parseInt(args[2]);
        } catch (NumberFormatException error) {
            plugin.messages().send(sender, "unknown-command");
            return;
        }
        UUID uuid = target.getUniqueId();
        var future = streak
                ? plugin.service().setStreakAsync(uuid, value)
                : plugin.service().setTotalAsync(uuid, value);
        future.thenRun(() -> Schedulers.global(plugin, () ->
                        plugin.messages().send(sender, "set-done",
                                "player", args[1],
                                "field", streak ? "连续天数" : "累计天数",
                                "value", String.valueOf(Math.max(0, value)))))
                .exceptionally(error -> {
                    fail(sender, error);
                    return null;
                });
    }

    private void purge(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return;
        }
        int days = plugin.config().logRetainDays();
        if (args.length >= 2) {
            try {
                days = Integer.parseInt(args[1]);
            } catch (NumberFormatException error) {
                plugin.messages().send(sender, "unknown-command");
                return;
            }
        }
        int finalDays = Math.max(1, days);
        LocalDate cutoff = plugin.time().today().minusDays(finalDays);
        plugin.service().purgeAsync(finalDays).thenAccept(count ->
                        Schedulers.global(plugin, () -> plugin.messages().send(sender, "purged",
                                "count", String.valueOf(count),
                                "date", cutoff.toString())))
                .exceptionally(error -> {
                    fail(sender, error);
                    return null;
                });
    }

    private void dbTest(CommandSender sender) {
        if (!admin(sender)) {
            return;
        }
        Schedulers.async(plugin, () -> plugin.store().ping()).thenAccept(ok ->
                Schedulers.global(plugin, () -> {
                    if (ok) {
                        plugin.messages().send(sender, "db-ok");
                    } else {
                        plugin.messages().send(sender, "db-fail", "error", "连接不可用");
                    }
                })).exceptionally(error -> {
            fail(sender, error);
            return null;
        });
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private void fail(CommandSender sender, Throwable error) {
        plugin.getLogger().warning("命令执行失败：" + rootMessage(error));
        Schedulers.global(plugin, () -> plugin.messages().send(sender, "error"));
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private void sendHelp(CommandSender sender) {
        plugin.messages().list("help").forEach(sender::sendMessage);
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        plugin.messages().send(sender, "player-only");
        return null;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("liusignin.admin")) {
            return true;
        }
        plugin.messages().send(sender, "no-permission");
        return false;
    }

    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            return offline.hasPlayedBefore() ? offline : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private void notifyIfOnline(UUID uuid, String key, String playerName, String amount) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            plugin.messages().send(online, key, "player", playerName, "amount", amount);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(rootCommands(sender), args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "card" -> filter(List.of("give", "take", "set"), args[1]);
                case "top", "leaderboard" -> filter(List.of("streak", "total"), args[1]);
                case "makeup" -> filter(makeupDates(), args[1]);
                case "reset", "setstreak", "settotal", "info" -> filter(onlineNames(), args[1]);
                case "purge" -> filter(List.of("30", "90", "180", "365"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && "card".equalsIgnoreCase(args[0])) {
            return filter(onlineNames(), args[2]);
        }
        return List.of();
    }

    private List<String> rootCommands(CommandSender sender) {
        List<String> out = new ArrayList<>(List.of("help", "claim", "menu", "makeup", "mail", "top", "info"));
        if (sender.hasPermission("liusignin.admin")) {
            out.addAll(List.of("reload", "card", "reset", "setstreak", "settotal", "purge", "dbtest"));
        }
        return out;
    }

    private List<String> filter(List<String> candidates, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(candidate);
            }
        }
        return out;
    }

    private List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private List<String> makeupDates() {
        LocalDate today = plugin.time().today();
        int max = plugin.config().makeup().maxDays();
        List<String> out = new ArrayList<>();
        out.add("昨天");
        for (int i = 2; i <= max; i++) {
            out.add(today.minusDays(i).toString());
        }
        return out;
    }
}
