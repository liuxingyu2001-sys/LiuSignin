package com.liuxingyu.liusignin.service;

import com.liuxingyu.liulib.item.ItemService;
import com.liuxingyu.liusignin.LiuSigninPlugin;
import com.liuxingyu.liusignin.data.MailEntry;
import com.liuxingyu.liusignin.hook.VaultHook;
import com.liuxingyu.liusignin.reward.Reward;
import com.liuxingyu.liusignin.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 奖励发放。所有方法必须在玩家所在线程（Paper 主线程）调用。
 *
 * <p>物品放不下、Vault 不可用时写入签到邮箱，避免“奖励凭空消失”；
 * 命令、经验、补签卡立即生效。</p>
 */
public final class RewardService {

    /** 发放结果：可读摘要、转入邮箱的数量、原始奖励列表。 */
    public record GrantOutcome(List<String> summaries, int mailed, List<Reward> granted) {

        public String summary(String separator) {
            return String.join(separator, summaries);
        }
    }

    private final LiuSigninPlugin plugin;
    private final ItemService items;
    private final VaultHook vault;

    public RewardService(LiuSigninPlugin plugin, ItemService items, VaultHook vault) {
        this.plugin = plugin;
        this.items = items;
        this.vault = vault;
    }

    public GrantOutcome grant(Player player, List<Reward> rewards, int streak, int total, LocalDate date) {
        List<String> summaries = new ArrayList<>();
        List<Reward> granted = new ArrayList<>();
        int mailed = 0;

        if (rewards == null || rewards.isEmpty()) {
            return new GrantOutcome(summaries, mailed, granted);
        }

        for (Reward reward : rewards) {
            try {
                switch (reward.type()) {
                    case MONEY -> {
                        if (reward.amount() <= 0) {
                            continue;
                        }
                        if (vault.deposit(player, reward.amount())) {
                            summaries.add("金币 " + Reward.formatNumber(reward.amount()));
                        } else {
                            plugin.mailbox().addAsync(player.getUniqueId(), MailEntry.money(reward.amount()));
                            summaries.add("金币 " + Reward.formatNumber(reward.amount()) + "(邮件)");
                            mailed++;
                        }
                    }
                    case EXP -> {
                        int amount = (int) Math.round(reward.amount());
                        if (amount <= 0) {
                            continue;
                        }
                        player.giveExp(amount);
                        summaries.add("经验 " + amount);
                    }
                    case CARD -> {
                        int amount = (int) Math.round(reward.amount());
                        if (amount <= 0) {
                            continue;
                        }
                        plugin.service().addCardsAsync(player.getUniqueId(), amount)
                                .whenComplete((cards, error) -> {
                                    if (error != null) {
                                        plugin.getLogger().warning("补签卡发放失败："
                                                + error.getMessage());
                                    }
                                });
                        summaries.add("补签卡 " + amount);
                    }
                    case ITEM -> {
                        List<ItemStack> stacks = items.buildStacks(reward.value());
                        if (stacks.isEmpty()) {
                            plugin.getLogger().warning("签到奖励物品解析失败：" + reward.value());
                            continue;
                        }
                        Map<Integer, ItemStack> leftover =
                                player.getInventory().addItem(stacks.toArray(new ItemStack[0]));
                        for (ItemStack remain : leftover.values()) {
                            String encoded = items.codec().encode(remain);
                            if (encoded == null) {
                                plugin.getLogger().warning("奖励物品编码失败，已丢弃：" + remain.getType());
                                continue;
                            }
                            plugin.mailbox().addAsync(player.getUniqueId(), MailEntry.item(encoded));
                            mailed++;
                        }
                        summaries.add(reward.describe(items));
                    }
                    case COMMAND -> {
                        String command = Text.fill(reward.value(),
                                "player", player.getName(),
                                "uuid", player.getUniqueId().toString(),
                                "streak", String.valueOf(streak),
                                "total", String.valueOf(total),
                                "date", date == null ? "" : date.toString());
                        if (command.isBlank()) {
                            continue;
                        }
                        if ("player".equalsIgnoreCase(reward.mode())) {
                            player.performCommand(command);
                        } else {
                            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
                        }
                        summaries.add("命令奖励");
                    }
                }
                granted.add(reward);
            } catch (RuntimeException error) {
                plugin.getLogger().warning("发放签到奖励失败（" + reward.raw() + "）：" + error.getMessage());
            }
        }
        return new GrantOutcome(List.copyOf(summaries), mailed, List.copyOf(granted));
    }

    /** 给玩家发放单个物品；放不下时转入邮箱。 */
    public void giveOrMail(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (ItemStack remain : leftover.values()) {
            String encoded = items.codec().encode(remain);
            if (encoded != null) {
                plugin.mailbox().addAsync(uuid, MailEntry.item(encoded));
            }
        }
    }
}
