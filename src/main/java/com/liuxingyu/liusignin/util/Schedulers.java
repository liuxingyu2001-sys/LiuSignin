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
        if (plugin == null || !plugin.isEnabled()) {
            future.completeExceptionally(new IllegalStateException("插件已停用"));
            return future;
        }
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    public static CompletableFuture<Void> asyncRun(Plugin plugin, Runnable action) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (plugin == null || !plugin.isEnabled()) {
            future.completeExceptionally(new IllegalStateException("插件已停用"));
            return future;
        }
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    action.run();
                    future.complete(null);
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    /**
     * 回到玩家所在线程执行。
     *
     * <p>返回的 future 在动作完成后正常完成；玩家在任务执行前离线或切换子服时，
     * 通过实体调度器的 retired 回调异常完成，调用方可以据此结束自己的 future，
     * 避免服务层 future 永远挂起。菜单等忽略返回值的调用方不受影响。</p>
     */
    public static CompletableFuture<Void> onPlayer(Plugin plugin, Player player, Runnable action) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (plugin == null || !plugin.isEnabled()) {
            future.completeExceptionally(new IllegalStateException("插件已停用"));
            return future;
        }
        if (player == null || !player.isOnline()) {
            future.completeExceptionally(new IllegalStateException("玩家已离线"));
            return future;
        }
        try {
            player.getScheduler().run(plugin, task -> {
                try {
                    action.run();
                    future.complete(null);
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            }, () -> future.completeExceptionally(new IllegalStateException("玩家已离线")));
        } catch (Throwable error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    /** 全局主线程（无实体归属的任务，如广播、控制台命令）。 */
    public static void global(Plugin plugin, Runnable action) {
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> action.run());
    }
}
