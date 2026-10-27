package com.liuxingyu.liusignin.listener;

import com.liuxingyu.liusignin.LiuSigninPlugin;
import com.liuxingyu.liusignin.api.event.SignSource;
import com.liuxingyu.liusignin.config.SigninConfig;
import com.liuxingyu.liusignin.data.PlayerSigninData;
import com.liuxingyu.liusignin.util.Schedulers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 进服加载缓存、自动签到、签到提醒、断线补偿与邮箱自动领取。 */
public final class PlayerListener implements Listener {

    private final LiuSigninPlugin plugin;

    public PlayerListener(LiuSigninPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        Schedulers.asyncRun(plugin, () -> plugin.store().setName(uuid, name));
        plugin.service().state(uuid, true)
                .thenAccept(state -> Schedulers.onPlayer(plugin, player, () -> {
                    plugin.service().recoverPending(player);
                    if (plugin.config().mailbox().autoClaimOnJoin()) {
                        plugin.mailbox().claimAll(player);
                    }
                    SigninConfig config = plugin.config();
                    if (config.autoSign().enabled() && player.hasPermission("liusignin.sign")) {
                        player.getScheduler().runDelayed(plugin, task -> {
                            if (player.isOnline() && !plugin.service().isSignedToday(uuid)) {
                                plugin.service().sign(player, SignSource.AUTO);
                            }
                        }, null, config.autoSign().delayTicks());
                    }
                    scheduleReminder(player, uuid, config);
                }))
                .exceptionally(error -> {
                    plugin.getLogger().warning("加载玩家签到数据失败（" + name + "）："
                            + error.getMessage());
                    return null;
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.service().invalidate(player.getUniqueId());
        plugin.signinMenu().clear(player.getUniqueId());
    }

    /**
     * 延迟发送进服提醒；{@code once-per-day} 开启时先向 MySQL 抢占当日提醒名额，
     * 多台子服之间只有一台会真正发出消息，切换子服不会重复提示。
     */
    private void scheduleReminder(Player player, UUID uuid, SigninConfig config) {
        if (!config.reminder().onJoin()
                || !player.hasPermission("liusignin.sign")
                || !player.hasPermission("liusignin.gui")) {
            return;
        }
        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline() || plugin.service().isSignedToday(uuid)) {
                return;
            }
            if (!config.reminder().oncePerDay()) {
                sendReminder(player);
                return;
            }
            LocalDate today = plugin.time().today();
            Schedulers.async(plugin, () -> {
                        // 跨服并发：本服缓存可能还是“未签到”，用权威日志再确认一次。
                        if (plugin.store().hasLog(uuid, today)) {
                            return false;
                        }
                        return plugin.store().claimReminder(uuid, today);
                    })
                    .thenCompose(claimed -> {
                        if (!claimed) {
                            return CompletableFuture.completedFuture(false);
                        }
                        // 抢到提醒名额后、真正发送前再查一次，避免另一子服刚签到却仍提醒。
                        return Schedulers.async(plugin, () -> !plugin.store().hasLog(uuid, today));
                    })
                    .thenAccept(shouldSend -> {
                        if (!shouldSend) {
                            return;
                        }
                        Schedulers.onPlayer(plugin, player, () -> {
                            if (player.isOnline() && !plugin.service().isSignedToday(uuid)) {
                                sendReminder(player);
                            }
                        });
                    })
                    .exceptionally(error -> {
                        plugin.getLogger().warning("签到提醒去重失败（" + player.getName() + "）："
                                + error.getMessage());
                        return null;
                    });
        }, null, config.reminder().delayTicks());
    }

    private void sendReminder(Player player) {
        PlayerSigninData data = plugin.service().data(player.getUniqueId());
        int streak = data == null ? 0 : data.streak();
        int total = data == null ? 0 : data.total();
        int cards = data == null ? 0 : data.makeupCards();
        boolean first = total == 0;
        String name = player.getName();

        String intro = first
                ? "亲爱的 <white><bold>" + name + "</bold></white>，欢迎来到服务器，今天是你签到的第一天！"
                : "亲爱的 <white><bold>" + name + "</bold></white>，新的一天也别忘了签到。";
        String progress = first
                ? "首次签到即可领取今日奖励，连续签到还有更多惊喜。"
                : "连续签到 <aqua>" + streak + "</aqua> 天 <dark_gray>|</dark_gray> 累计 <aqua>"
                        + total + "</aqua> 天 <dark_gray>|</dark_gray> 补签卡 <aqua>"
                        + cards + "</aqua> 张";
        String quote = plugin.messages().randomLine("reminder-quotes");
        if (quote == null || quote.isBlank()) {
            quote = "每天一点坚持，终会汇成星光。";
        }

        Component hover = plugin.messages().raw("reminder-hover");
        Component message = plugin.messages().msg("reminder",
                        "player", name,
                        "intro", intro,
                        "progress", progress,
                        "quote", quote)
                .clickEvent(ClickEvent.runCommand("/signin menu"))
                .hoverEvent(HoverEvent.showText(hover));
        player.sendMessage(message);
    }
}
