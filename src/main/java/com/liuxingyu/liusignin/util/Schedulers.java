package com.liuxingyu.liusignin.util;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 线程调度封装：Paper / Folia 通用的异步、全局与玩家实体调度。
 *
 * <p>Paper 1.20+ 在非 Folia 服务端同样提供 AsyncScheduler / GlobalRegionScheduler /
 * EntityScheduler，因此无需反射判断 Folia。</p>
 */
public final class Schedulers {

    private Schedulers() {
    }

    public static <T> CompletableFuture<T> async(Plugin plugin, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    public static CompletableFuture<Void> asyncRun(Plugin plugin, Runnable action) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    /** 回到玩家所在线程执行；玩家离线时静默丢弃。 */
    public static void onPlayer(Plugin plugin, Player player, Runnable action) {
        if (player == null || !player.isOnline() || !plugin.isEnabled()) {
            return;
        }
        player.getScheduler().run(plugin, task -> action.run(), null);
    }

    /** 全局主线程（无实体归属的任务，如广播、控制台命令）。 */
    public static void global(Plugin plugin, Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> action.run());
    }
}
