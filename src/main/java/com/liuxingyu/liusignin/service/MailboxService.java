package com.liuxingyu.liusignin.service;

import com.liuxingyu.liulib.item.ItemService;
import com.liuxingyu.liusignin.LiuSigninPlugin;
import com.liuxingyu.liusignin.data.MailEntry;
import com.liuxingyu.liusignin.hook.VaultHook;
import com.liuxingyu.liusignin.util.Schedulers;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 签到邮箱：背包装不下的物品、Vault 不可用时的金币会暂存在数据库，
 * 玩家上线（或打开邮箱界面）时领取。
 */
public final class MailboxService {

    private final LiuSigninPlugin plugin;
    private final ItemService items;
    private final VaultHook vault;
    private final Set<UUID> claiming = ConcurrentHashMap.newKeySet();

    public MailboxService(LiuSigninPlugin plugin, ItemService items, VaultHook vault) {
        this.plugin = plugin;
        this.items = items;
        this.vault = vault;
    }

    public void addAsync(UUID uuid, String payload) {
        if (uuid == null || payload == null || payload.isBlank()) {
            return;
        }
        Schedulers.asyncRun(plugin, () -> plugin.store().addMail(uuid, payload))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        plugin.getLogger().warning("写入签到邮箱失败：" + error.getMessage());
                    }
                });
    }

    public CompletableFuture<List<MailEntry>> list(UUID uuid) {
        int limit = plugin.config().mailbox().claimBatch();
        return Schedulers.async(plugin, () -> plugin.store().mail(uuid, limit));
    }

    /**
     * 领取邮箱。
     *
     * @param onlyId 只领取指定条目；null 表示全部
     */
    public CompletableFuture<Integer> claim(Player player, Long onlyId) {
        UUID uuid = player.getUniqueId();
        if (!claiming.add(uuid)) {
            return CompletableFuture.completedFuture(0);
        }
        if (!player.isOnline()) {
            claiming.remove(uuid);
            return CompletableFuture.completedFuture(0);
        }
        return Schedulers.async(plugin, () -> {
                    List<MailEntry> entries = new ArrayList<>(plugin.store().mail(uuid, plugin.config().mailbox().claimBatch()));
                    if (onlyId != null) {
                        entries.removeIf(entry -> entry.id() != onlyId);
                    }
                    return entries;
                })
                .thenCompose(entries -> {
                    if (entries.isEmpty() || !player.isOnline()) {
                        claiming.remove(uuid);
                        return CompletableFuture.completedFuture(0);
                    }
                    CompletableFuture<Integer> future = new CompletableFuture<>();
                    Schedulers.onPlayer(plugin, player, () -> {
                        int claimed = 0;
                        for (MailEntry entry : entries) {
                            if (!deliver(player, entry)) {
                                break;
                            }
                            claimed++;
                            long id = entry.id();
                            Schedulers.asyncRun(plugin, () -> plugin.store().deleteMail(id, uuid));
                        }
                        claiming.remove(uuid);
                        future.complete(claimed);
                    });
                    return future;
                })
                .whenComplete((ignored, error) -> claiming.remove(uuid));
    }

    public CompletableFuture<Integer> claimAll(Player player) {
        return claim(player, null);
    }

    /** 玩家所在线程调用。返回 false 表示本条未领取（背包满 / 经济不可用），保留在邮箱。 */
    private boolean deliver(Player player, MailEntry entry) {
        String kind = entry.kind();
        try {
            switch (kind) {
                case "item" -> {
                    ItemStack stack = items.codec().decode(entry.value());
                    if (stack == null) {
                        plugin.getLogger().warning("签到邮箱中的物品已损坏，已跳过: " + entry.id());
                        return true;
                    }
                    Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
                    if (!leftover.isEmpty()) {
                        // 装不下的部分重新写回邮箱，避免“部分放入、整条重领”的刷物品漏洞
                        for (ItemStack remain : leftover.values()) {
                            String encoded = items.codec().encode(remain);
                            if (encoded == null) {
                                plugin.getLogger().warning("邮箱物品回写编码失败：" + remain.getType());
                                continue;
                            }
                            addAsync(player.getUniqueId(), MailEntry.item(encoded));
                        }
                        plugin.messages().send(player, "mail-notice",
                                "count", String.valueOf(leftover.size()));
                    }
                    return true;
                }
                case "money" -> {
                    double amount = Double.parseDouble(entry.value());
                    if (!vault.available()) {
                        plugin.messages().send(player, "vault-missing");
                        return false;
                    }
                    if (!vault.deposit(player, amount)) {
                        return false;
                    }
                    plugin.messages().send(player, "mail-claimed",
                            "reward", "金币 " + RewardFormat.number(amount));
                    return true;
                }
                case "exp" -> {
                    int amount = Integer.parseInt(entry.value());
                    player.giveExp(amount);
                    plugin.messages().send(player, "mail-claimed",
                            "reward", "经验 " + amount);
                    return true;
                }
                default -> {
                    plugin.getLogger().warning("未知的邮箱条目类型: " + entry.payload());
                    return true;
                }
            }
        } catch (RuntimeException error) {
            plugin.getLogger().warning("领取邮箱条目失败（" + entry.payload() + "）：" + error.getMessage());
            return false;
        }
    }

    /** 避免依赖 Reward 的格式化方法造成循环。 */
    private static final class RewardFormat {
        static String number(double amount) {
            return amount == Math.rint(amount)
                    ? String.valueOf((long) amount)
                    : String.format(java.util.Locale.ROOT, "%.2f", amount);
        }
    }
}
