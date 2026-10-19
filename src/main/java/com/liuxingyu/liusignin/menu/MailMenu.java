package com.liuxingyu.liusignin.menu;

import com.liuxingyu.liulib.ui.SparrowUi;
import com.liuxingyu.liusignin.LiuSigninPlugin;
import com.liuxingyu.liusignin.data.MailEntry;
import com.liuxingyu.liusignin.util.Schedulers;
import com.liuxingyu.liusignin.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

/** 签到邮箱界面。 */
public final class MailMenu {

    private final LiuSigninPlugin plugin;

    public MailMenu(LiuSigninPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!player.hasPermission("liusignin.mail")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        plugin.mailbox().list(player.getUniqueId())
                .thenAccept(entries -> Schedulers.onPlayer(plugin, player, () -> render(player, entries)))
                .exceptionally(error -> {
                    plugin.getLogger().warning("读取签到邮箱失败：" + error.getMessage());
                    plugin.messages().send(player, "error");
                    return null;
                });
    }

    private void render(Player player, List<MailEntry> entries) {
        if (!player.isOnline()) {
            return;
        }
        SparrowUi ui = plugin.sparrow();
        if (ui == null || !ui.available()) {
            plugin.messages().send(player, "error");
            return;
        }
        MenuConfig.Mail config = plugin.menus().mail();
        SparrowUi.MenuBuilder menu = ui.menu(
                plugin.items().names().parse(Text.fill(config.title(), Map.of())), config.rows());

        ItemStack filler = config.empty().build(plugin.items(), "", List.of());
        menu.fillEmpty(filler);

        if (entries.isEmpty()) {
            plugin.messages().send(player, "mail-empty");
        }

        int index = 0;
        for (MailEntry entry : entries) {
            if (index >= config.itemSlots().size()) {
                break;
            }
            int slot = config.itemSlots().get(index++);
            if (slot < 0 || slot >= config.rows() * 9) {
                continue;
            }
            ItemStack icon = icon(config, entry);
            menu.set(slot, icon, click -> claimOne(player, entry));
        }

        menu.set(config.claimAllSlot(), config.claimAll().build(plugin.items(),
                Text.fill(config.claimAll().name(), Map.of()),
                config.claimAll().lore()), click -> claimAll(player));
        menu.set(config.closeSlot(), config.close().build(plugin.items(),
                Text.fill(config.close().name(), Map.of()),
                config.close().lore()), click -> {
            plugin.sparrow().close(player);
            player.closeInventory();
        });

        menu.open(player);
    }

    private ItemStack icon(MenuConfig.Mail config, MailEntry entry) {
        ItemStack base;
        String displayName;
        String amount;
        switch (entry.kind()) {
            case "item" -> {
                ItemStack decoded = plugin.items().codec().decode(entry.value());
                if (decoded == null) {
                    base = config.empty().build(plugin.items(), "", List.of());
                    displayName = "已损坏的物品";
                    amount = "0";
                } else {
                    base = decoded.clone();
                    displayName = plugin.items().label(decoded);
                    amount = String.valueOf(decoded.getAmount());
                }
            }
            case "money" -> {
                base = new ItemStack(Material.GOLD_INGOT);
                displayName = "金币";
                amount = entry.value();
            }
            case "exp" -> {
                base = new ItemStack(Material.EXPERIENCE_BOTTLE);
                displayName = "经验";
                amount = entry.value();
            }
            default -> {
                base = config.empty().build(plugin.items(), "", List.of());
                displayName = "未知奖励";
                amount = "0";
            }
        }
        return decorate(base, config.entryName(), config.entryLore(), displayName, amount);
    }

    private ItemStack decorate(ItemStack base, String nameTemplate, List<String> loreTemplate,
                               String name, String amount) {
        ItemStack stack = base.clone();
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        String display = Text.fill(nameTemplate, "name", name, "amount", amount);
        if (!display.isBlank()) {
            meta.displayName(plugin.items().names().parse(display));
        }
        if (loreTemplate != null && !loreTemplate.isEmpty()) {
            meta.lore(loreTemplate.stream()
                    .map(line -> Text.fill(line, "name", name, "amount", amount))
                    .map(plugin.items().names()::parse)
                    .toList());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private void claimOne(Player player, MailEntry entry) {
        plugin.mailbox().claim(player, entry.id()).thenAccept(count ->
                Schedulers.onPlayer(plugin, player, () -> {
                    if (count > 0) {
                        reopenLater(player);
                    }
                }));
    }

    private void claimAll(Player player) {
        plugin.mailbox().claimAll(player).thenAccept(count ->
                Schedulers.onPlayer(plugin, player, () -> {
                    if (count > 0) {
                        plugin.messages().send(player, "mail-claim-all-done",
                                "count", String.valueOf(count));
                    }
                    reopenLater(player);
                }));
    }

    private void reopenLater(Player player) {
        if (!player.isOnline()) {
            return;
        }
        player.getScheduler().runDelayed(plugin, task -> open(player), null, 2L);
    }
}
