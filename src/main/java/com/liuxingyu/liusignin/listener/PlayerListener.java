package com.liuxingyu.liusignin.listener;

import com.liuxingyu.liusignin.LiuSigninPlugin;
import com.liuxingyu.liusignin.api.event.SignSource;
import com.liuxingyu.liusignin.config.SigninConfig;
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

import java.util.UUID;

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
                    if (config.reminder().onJoin() && player.hasPermission("liusignin.sign")) {
                        player.getScheduler().runDelayed(plugin, task -> {
                            if (player.isOnline() && !plugin.service().isSignedToday(uuid)) {
                                sendReminder(player);
                            }
                        }, null, config.reminder().delayTicks());
                    }
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

    private void sendReminder(Player player) {
        Component hover = plugin.messages().raw("reminder-hover");
        Component message = plugin.messages().msg("reminder")
                .clickEvent(ClickEvent.runCommand("/signin"))
                .hoverEvent(HoverEvent.showText(hover));
        player.sendMessage(message);
    }
}
