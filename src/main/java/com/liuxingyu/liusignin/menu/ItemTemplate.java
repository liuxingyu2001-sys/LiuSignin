package com.liuxingyu.liusignin.menu;

import com.liuxingyu.liulib.item.ItemService;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * 菜单物品模板：支持 LiuLib 物品规格（CE / base64）或原版材质，
 * 名称与 lore 使用 MiniMessage 解析。
 */
public record ItemTemplate(
        String item,
        Material material,
        String name,
        List<String> lore,
        boolean glow
) {

    public ItemStack build(ItemService items, String nameText, List<String> loreText) {
        ItemStack stack = null;
        if (item != null && !item.isBlank()) {
            stack = items.buildOrNull(item);
        }
        if (stack == null) {
            stack = new ItemStack(material == null ? Material.STONE : material);
        } else {
            stack = stack.clone();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (nameText != null && !nameText.isBlank()) {
            meta.displayName(items.names().parse(nameText));
        }
        if (loreText != null && !loreText.isEmpty()) {
            meta.lore(loreText.stream().map(items.names()::parse).toList());
        }
        if (glow) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_DYE, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        stack.setItemMeta(meta);
        return stack;
    }
}
