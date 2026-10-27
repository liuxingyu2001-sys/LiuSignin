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
                        List<Long> claimedIds = new ArrayList<>();
                        for (MailEntry entry : entries) {
                            if (!deliver(player, entry)) {
                                break;
                            }
                            claimed++;
                            claimedIds.add(entry.id());
                        }
                        if (claimedIds.isEmpty()) {
                            claiming.remove(uuid);
                            future.complete(0);
                            return;
                        }
                        // 一条 DELETE ... IN (...) 完成清理；删完再释放 claiming，避免重复领取
                        final int delivered = claimed;
                        final List<Long> ids = List.copyOf(claimedIds);
                        Schedulers.asyncRun(plugin, () -> plugin.store().deleteMails(uuid, ids))
                                .whenComplete((ignored, error) -> {
                                    if (error != null) {
                                        plugin.getLogger().warning("清理已领取邮件失败（"
                                                + uuid + "）：" + error.getMessage());
                                    }
                                    claiming.remove(uuid);
                                    future.complete(delivered);
                                });
                    }).whenComplete((ignored, error) -> {
                        if (error != null) {
                            claiming.remove(uuid);
                            future.completeExceptionally(error);
                        }
                    });
                    return future;
                })
                .whenComplete((ignored, error) -> claiming.remove(uuid));
    }

    public CompletableFuture<Integer> claimAll(Player player) {
        return claimAll(player, 0);
    }

    /**
     * 递归领取直到邮箱为空、背包满或达到批次数上限。
     * claim-batch 只是单次 SQL 上限；真正的“一键领取”不能只领 45 条。
     */
    private CompletableFuture<Integer> claimAll(Player player, int depth) {
        if (player == null || !player.isOnline() || depth >= 20) {
            return CompletableFuture.completedFuture(0);
        }
        int batch = plugin.config().mailbox().claimBatch();
        return claim(player, null).thenCompose(count -> {
            if (count <= 0 || count < batch || !player.isOnline()) {
                return CompletableFuture.completedFuture(count);
            }
            return claimAll(player, depth + 1).thenApply(next -> count + next);
        });
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
                    // 整条放入或整条保留：不把装不下的部分异步回写邮箱。
                    // 异步回写+删除原记录会在崩溃/断服时造成物品丢失或重复。
                    ItemStack[] before = snapshot(player.getInventory().getStorageContents());
                    Map<Integer, ItemStack> leftover;
                    try {
                        leftover = player.getInventory().addItem(stack);
                    } catch (RuntimeException error) {
                        restore(player, before);
                        throw error;
                    }
                    if (!leftover.isEmpty()) {
                        restore(player, before);
                        plugin.messages().send(player, "mail-full");
                        return false;
                    }
                    return true;
                }
                case "money" -> {
                    double amount = Double.parseDouble(entry.value());
                    if (!Double.isFinite(amount) || amount <= 0) {
                        plugin.getLogger().warning("签到邮箱金币数额无效，已跳过: " + entry.id());
                        return true;
                    }
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
                    if (amount <= 0) {
                        plugin.getLogger().warning("签到邮箱经验数额无效，已跳过: " + entry.id());
                        return true;
                    }
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

    private static ItemStack[] snapshot(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private static void restore(Player player, ItemStack[] before) {
        player.getInventory().setStorageContents(before);
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
