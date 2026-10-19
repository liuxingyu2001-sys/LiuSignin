package com.liuxingyu.liusignin.hook;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Vault 经济钩子。
 *
 * <p>通过反射访问 Vault API：即使服务端没有安装 Vault，本类也能正常加载，
 * 只是在 {@link #available()} 返回 false，金币奖励转入签到邮箱。</p>
 */
public final class VaultHook {

    private final Object economy;
    private final Method depositPlayer;
    private final Method format;

    public VaultHook() {
        Object provider = null;
        Method deposit = null;
        Method formatMethod = null;
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
                Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
                @SuppressWarnings({"rawtypes", "unchecked"})
                RegisteredServiceProvider registration =
                        Bukkit.getServicesManager().getRegistration((Class) economyClass);
                if (registration != null) {
                    provider = registration.getProvider();
                    deposit = economyClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
                    formatMethod = economyClass.getMethod("format", double.class);
                }
            }
        } catch (Throwable ignored) {
            // 未安装 Vault / 经济插件时按不可用处理
        }
        this.economy = provider;
        this.depositPlayer = deposit;
        this.format = formatMethod;
    }

    public boolean available() {
        return economy != null && depositPlayer != null;
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (!available() || player == null || amount <= 0) {
            return false;
        }
        try {
            Object response = depositPlayer.invoke(economy, player, amount);
            if (response == null) {
                return false;
            }
            Method success = response.getClass().getMethod("transactionSuccess");
            return Boolean.TRUE.equals(success.invoke(response));
        } catch (Throwable error) {
            return false;
        }
    }

    public String format(double amount) {
        if (format == null || economy == null) {
            return String.format(Locale.ROOT, "%.0f", amount);
        }
        try {
            Object result = format.invoke(economy, amount);
            return result == null ? String.format(Locale.ROOT, "%.0f", amount) : String.valueOf(result);
        } catch (Throwable error) {
            return String.format(Locale.ROOT, "%.0f", amount);
        }
    }
}
