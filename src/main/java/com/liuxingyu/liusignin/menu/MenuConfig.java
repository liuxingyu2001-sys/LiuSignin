package com.liuxingyu.liusignin.menu;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * menus.yml 的解析结果。所有段缺失时都有代码内的默认值，避免配置删改后界面空白。
 */
public record MenuConfig(Calendar calendar, Makeup makeup, Mail mail) {

    public record Calendar(
            String title,
            int rows,
            int dayColumns,
            DayOfWeek weekStart,
            ItemTemplate background,
            Map<String, Integer> slots,
            Map<String, ItemTemplate> info,
            Map<String, ItemTemplate> states
    ) {
        public int slot(String key, int fallback) {
            Integer value = slots.get(key);
            return value == null ? fallback : value;
        }

        public ItemTemplate state(String key) {
            ItemTemplate template = states.get(key);
            return template == null ? states.getOrDefault("future", states.get("signed")) : template;
        }
    }

    public record Makeup(
            String title,
            int rows,
            int infoSlot,
            int confirmSlot,
            int cancelSlot,
            ItemTemplate info,
            ItemTemplate confirm,
            ItemTemplate cancel
    ) {
    }

    public record Mail(
            String title,
            int rows,
            List<Integer> itemSlots,
            int claimAllSlot,
            int closeSlot,
            ItemTemplate claimAll,
            ItemTemplate close,
            ItemTemplate empty,
            String entryName,
            List<String> entryLore
    ) {
    }

    public static MenuConfig load(FileConfiguration yaml) {
        ConfigurationSection calendar = yaml.getConfigurationSection("calendar");
        ConfigurationSection makeup = yaml.getConfigurationSection("makeup");
        ConfigurationSection mail = yaml.getConfigurationSection("mail");

        return new MenuConfig(loadCalendar(calendar), loadMakeup(makeup), loadMail(mail));
    }

    private static Calendar loadCalendar(ConfigurationSection section) {
        String title = section == null ? "每日签到"
                : section.getString("title", "每日签到");
        int rows = clamp(section == null ? 6 : section.getInt("rows", 6), 1, 6);
        int dayColumns = clamp(section == null ? 7 : section.getInt("day-columns", 7), 1, 9);
        String weekStartRaw = section == null ? "monday" : section.getString("week-start", "monday");
        DayOfWeek weekStart = "sunday".equalsIgnoreCase(weekStartRaw) ? DayOfWeek.SUNDAY : DayOfWeek.MONDAY;

        String backgroundName = section == null ? "BLACK_STAINED_GLASS_PANE"
                : section.getString("background", "BLACK_STAINED_GLASS_PANE");
        Material backgroundMaterial = Material.matchMaterial(backgroundName);
        ItemTemplate background = new ItemTemplate("", backgroundMaterial == null
                ? Material.BLACK_STAINED_GLASS_PANE : backgroundMaterial, "", List.of(), false);

        Map<String, Integer> slots = new LinkedHashMap<>();
        ConfigurationSection slotSection = section == null ? null : section.getConfigurationSection("slots");
        if (slotSection != null) {
            for (String key : slotSection.getKeys(false)) {
                slots.put(key.toLowerCase(), slotSection.getInt(key));
            }
        }
        slots.putIfAbsent("streak", 7);
        slots.putIfAbsent("total", 8);
        slots.putIfAbsent("cards", 16);
        slots.putIfAbsent("next-reward", 17);
        slots.putIfAbsent("leaderboard", 25);
        slots.putIfAbsent("mail", 26);
        slots.putIfAbsent("prev-month", 43);
        slots.putIfAbsent("next-month", 44);
        slots.putIfAbsent("close", 53);

        Map<String, ItemTemplate> info = new LinkedHashMap<>();
        ConfigurationSection infoSection = section == null ? null : section.getConfigurationSection("info");
        if (infoSection != null) {
            for (String key : infoSection.getKeys(false)) {
                ItemTemplate template = template(infoSection.getConfigurationSection(key),
                        null, "STONE", "", List.of(), false);
                if (template != null) {
                    info.put(key.toLowerCase(), template);
                }
            }
        }
        info.putIfAbsent("streak", template(null, null, "CLOCK", "<yellow>连续签到</yellow>",
                List.of("<white>{streak} 天</white>"), false));
        info.putIfAbsent("total", template(null, null, "BOOK", "<aqua>累计签到</aqua>",
                List.of("<white>{total} 天</white>"), false));
        info.putIfAbsent("cards", template(null, null, "PAPER", "<light_purple>补签卡</light_purple>",
                List.of("<white>{cards} 张</white>"), false));
        info.putIfAbsent("next-reward", template(null, null, "CHEST", "<gold>今日奖励</gold>",
                List.of("<white>{reward}</white>"), false));
        info.putIfAbsent("leaderboard", template(null, null, "GOLD_INGOT", "<gold>签到排行</gold>",
                List.of("<gray>点击查看</gray>"), false));
        info.putIfAbsent("mail", template(null, null, "CHEST_MINECART", "<aqua>签到邮箱</aqua>",
                List.of("<gray>点击领取</gray>"), false));
        info.putIfAbsent("prev-month", template(null, null, "ARROW", "<white>上个月</white>", List.of(), false));
        info.putIfAbsent("next-month", template(null, null, "ARROW", "<white>下个月</white>", List.of(), false));
        info.putIfAbsent("close", template(null, null, "BARRIER", "<red>关闭</red>", List.of(), false));

        Map<String, ItemTemplate> states = new LinkedHashMap<>();
        ConfigurationSection stateSection = section == null ? null : section.getConfigurationSection("states");
        if (stateSection != null) {
            for (String key : stateSection.getKeys(false)) {
                ItemTemplate template = template(stateSection.getConfigurationSection(key),
                        null, "GRAY_STAINED_GLASS_PANE", "", List.of(), false);
                if (template != null) {
                    states.put(key.toLowerCase(), template);
                }
            }
        }
        states.putIfAbsent("signed", template(null, null, "LIME_STAINED_GLASS_PANE",
                "<green>{day} 日 · 已签到</green>",
                List.of("<gray>{date}</gray>", "<dark_gray>奖励</dark_gray> <white>{reward}</white>"), false));
        states.putIfAbsent("signed-today", template(null, null, "EMERALD",
                "<gold>今天 · 已签到</gold>",
                List.of("<gray>{date}</gray>", "<dark_gray>奖励</dark_gray> <white>{reward}</white>"), false));
        states.putIfAbsent("today", template(null, null, "EMERALD",
                "<gold>今天 · 点击签到</gold>",
                List.of("<gray>{date}</gray>", "<dark_gray>奖励</dark_gray> <white>{reward}</white>",
                        "<yellow>▶ 点击签到</yellow>"), false));
        states.putIfAbsent("missed", template(null, null, "RED_STAINED_GLASS_PANE",
                "<red>{day} 日 · 漏签</red>",
                List.of("<gray>{date}</gray>", "<yellow>▶ 点击补签（消耗 {cost} 张补签卡）</yellow>"), false));
        states.putIfAbsent("missed-locked", template(null, null, "ORANGE_STAINED_GLASS_PANE",
                "<red>{day} 日 · 漏签</red>",
                List.of("<gray>{date}</gray>", "<dark_gray>已超出补签期限</dark_gray>"), false));
        states.putIfAbsent("future", template(null, null, "GRAY_STAINED_GLASS_PANE",
                "<gray>{day} 日</gray>",
                List.of("<gray>{date}</gray>", "<dark_gray>奖励</dark_gray> <white>{reward}</white>"), false));

        return new Calendar(title, rows, dayColumns, weekStart, background,
                Map.copyOf(slots), Map.copyOf(info), Map.copyOf(states));
    }

    private static Makeup loadMakeup(ConfigurationSection section) {
        String title = section == null ? "补签确认" : section.getString("title", "补签确认");
        int rows = clamp(section == null ? 3 : section.getInt("rows", 3), 1, 6);
        int info = section == null ? 13 : section.getInt("info-slot", 13);
        int confirm = section == null ? 11 : section.getInt("confirm-slot", 11);
        int cancel = section == null ? 15 : section.getInt("cancel-slot", 15);
        return new Makeup(title, rows, info, confirm, cancel,
                template(section == null ? null : section.getConfigurationSection("info"),
                        null, "WRITABLE_BOOK", "<yellow>补签 {date}</yellow>",
                        List.of("<gray>消耗 <white>{cost}</white> 张补签卡</gray>"), false),
                template(section == null ? null : section.getConfigurationSection("confirm"),
                        null, "LIME_CONCRETE", "<green>确认补签</green>", List.of(), false),
                template(section == null ? null : section.getConfigurationSection("cancel"),
                        null, "RED_CONCRETE", "<red>取消</red>", List.of(), false));
    }

    private static Mail loadMail(ConfigurationSection section) {
        String title = section == null ? "签到邮箱" : section.getString("title", "签到邮箱");
        int rows = clamp(section == null ? 6 : section.getInt("rows", 6), 1, 6);
        List<Integer> itemSlots = new ArrayList<>();
        if (section != null) {
            itemSlots.addAll(section.getIntegerList("item-slots"));
        }
        if (itemSlots.isEmpty()) {
            for (int i = 0; i < 45; i++) {
                itemSlots.add(i);
            }
        }
        int claimAll = section == null ? 49 : section.getInt("claim-all-slot", 49);
        int close = section == null ? 53 : section.getInt("close-slot", 53);
        String entryName = section == null ? "<white>{name}</white>"
                : section.getString("entry.name", "<white>{name}</white>");
        List<String> entryLore = section == null ? List.of() : section.getStringList("entry.lore");
        if (entryLore.isEmpty()) {
            entryLore = List.of("<gray>数量：<white>{amount}</white></gray>", "<yellow>▶ 点击领取</yellow>");
        }
        return new Mail(title, rows, List.copyOf(itemSlots), claimAll, close,
                template(section == null ? null : section.getConfigurationSection("claim-all"),
                        null, "HOPPER", "<green>一键领取</green>",
                        List.of("<gray>领取全部暂存奖励</gray>"), false),
                template(section == null ? null : section.getConfigurationSection("close"),
                        null, "BARRIER", "<red>关闭</red>", List.of(), false),
                template(section == null ? null : section.getConfigurationSection("empty"),
                        null, "GRAY_STAINED_GLASS_PANE", "<gray>空</gray>", List.of(), false),
                entryName, entryLore);
    }

    private static ItemTemplate template(ConfigurationSection section, String unused, String defaultMaterial,
                                         String defaultName, List<String> defaultLore, boolean defaultGlow) {
        String item = section == null ? "" : section.getString("item", "");
        String materialName = section == null ? defaultMaterial : section.getString("material", defaultMaterial);
        Material material = Material.matchMaterial(materialName == null ? defaultMaterial : materialName);
        if (material == null) {
            material = Material.matchMaterial(defaultMaterial);
        }
        if (material == null) {
            material = Material.STONE;
        }
        String name = section == null ? defaultName : section.getString("name", defaultName);
        List<String> lore = section == null ? defaultLore : section.getStringList("lore");
        if (lore == null || lore.isEmpty()) {
            lore = defaultLore;
        }
        boolean glow = section != null && section.getBoolean("glow", defaultGlow);
        return new ItemTemplate(item, material, name, List.copyOf(lore), glow);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
