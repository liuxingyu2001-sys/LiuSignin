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
        String title = section == null
                ? "<dark_aqua>签到</dark_aqua> <dark_gray>·</dark_gray> <white>{month-name}</white>"
                : section.getString("title",
                        "<dark_aqua>签到</dark_aqua> <dark_gray>·</dark_gray> <white>{month-name}</white>");
        int rows = clamp(section == null ? 6 : section.getInt("rows", 6), 1, 6);
        int dayColumns = clamp(section == null ? 7 : section.getInt("day-columns", 7), 1, 9);
        String weekStartRaw = section == null ? "monday" : section.getString("week-start", "monday");
        DayOfWeek weekStart = "sunday".equalsIgnoreCase(weekStartRaw) ? DayOfWeek.SUNDAY : DayOfWeek.MONDAY;

        String backgroundName = section == null ? "BLACK_STAINED_GLASS_PANE"
                : section.getString("background", "BLACK_STAINED_GLASS_PANE");
        Material backgroundMaterial = Material.matchMaterial(backgroundName);
        // background 也支持 LiuLib 完整物品规格（如 ce:ns:id / base64:... / lib:...）：
        // ItemTemplate 会先交给 LiuLib 解析，失败再回退到下面的原版材质。
        String backgroundSpec = backgroundName == null ? "" : backgroundName.trim();
        ItemTemplate background = new ItemTemplate(backgroundSpec, backgroundMaterial == null
                ? Material.BLACK_STAINED_GLASS_PANE : backgroundMaterial, "", List.of(), false);

        Map<String, Integer> slots = new LinkedHashMap<>();
        ConfigurationSection slotSection = section == null ? null : section.getConfigurationSection("slots");
        if (slotSection != null) {
            for (String key : slotSection.getKeys(false)) {
                slots.put(key.toLowerCase(), slotSection.getInt(key));
            }
        }
        slots.putIfAbsent("prev-month", 7);
        slots.putIfAbsent("stats", 8);
        slots.putIfAbsent("next-month", 16);
        slots.putIfAbsent("cards", 17);
        slots.putIfAbsent("next-reward", 26);
        slots.putIfAbsent("mail", 35);
        slots.putIfAbsent("leaderboard", 44);
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
        info.putIfAbsent("stats", template(null, null, "CLOCK", "<yellow>签到统计</yellow>",
                List.of("<white>连续 {streak} 天 · 累计 {total} 天</white>",
                        "<dark_gray>最佳 {best} 天</dark_gray>"), false));
        info.putIfAbsent("cards", template(null, null, "PAPER", "<light_purple>补签卡</light_purple>",
                List.of("<white>{cards} 张</white>"), false));
        info.putIfAbsent("next-reward", template(null, null, "CHEST", "<gold>今日奖励</gold>",
                List.of("<white>{reward}</white>"), false));
        info.putIfAbsent("leaderboard", template(null, null, "GOLD_INGOT", "<gold>签到排行</gold>",
                List.of("<gray>点击查看</gray>"), false));
        info.putIfAbsent("mail", template(null, null, "ENDER_CHEST", "<aqua>签到邮箱</aqua>",
                List.of("<gray>点击领取暂存奖励</gray>"), false));
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
        states.putIfAbsent("signed", template(null, null, "LIME_DYE",
                "<green>{day}</green>", List.of("<dark_gray>已签到</dark_gray>"), false));
        states.putIfAbsent("signed-today", template(null, null, "EMERALD",
                "<green>{day} · 今天</green>", List.of("<dark_gray>今天已签到</dark_gray>"), true));
        states.putIfAbsent("today", template(null, null, "EMERALD",
                "<gold>{day} · 今天</gold>", List.of("<yellow>点击签到</yellow>"), true));
        states.putIfAbsent("missed", template(null, null, "RED_DYE",
                "<red>{day}</red>", List.of("<gray>点击补签</gray>"), false));
        states.putIfAbsent("missed-locked", template(null, null, "GRAY_DYE",
                "<dark_gray>{day}</dark_gray>", List.of("<dark_gray>已过期</dark_gray>"), false));
        states.putIfAbsent("future", template(null, null, "GRAY_DYE",
                "<gray>{day}</gray>", List.of(), false));

        return new Calendar(title, rows, dayColumns, weekStart, background,
                Map.copyOf(slots), Map.copyOf(info), Map.copyOf(states));
    }

    private static Makeup loadMakeup(ConfigurationSection section) {
        String title = section == null
                ? "<dark_aqua>补签</dark_aqua> <dark_gray>·</dark_gray> <white>{date}</white>"
                : section.getString("title",
                        "<dark_aqua>补签</dark_aqua> <dark_gray>·</dark_gray> <white>{date}</white>");
        int rows = clamp(section == null ? 3 : section.getInt("rows", 3), 1, 6);
        int info = section == null ? 13 : section.getInt("info-slot", 13);
        int confirm = section == null ? 11 : section.getInt("confirm-slot", 11);
        int cancel = section == null ? 15 : section.getInt("cancel-slot", 15);
        return new Makeup(title, rows, info, confirm, cancel,
                template(section == null ? null : section.getConfigurationSection("info"),
                        null, "WRITABLE_BOOK", "<yellow>{date}</yellow>",
                        List.of("<gray>消耗 <white>{cost}</white> 张补签卡</gray>",
                                "<gray>该日奖励：<white>{reward}</white></gray>"), false),
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
            entryLore = List.of("<gray>数量：<white>{amount}</white></gray>", "<yellow>点击领取</yellow>");
        }
        return new Mail(title, rows, List.copyOf(itemSlots), claimAll, close,
                template(section == null ? null : section.getConfigurationSection("claim-all"),
                        null, "HOPPER", "<green>一键领取</green>",
                        List.of("<gray>领取全部暂存奖励</gray>"), false),
                template(section == null ? null : section.getConfigurationSection("close"),
                        null, "BARRIER", "<red>关闭</red>", List.of(), false),
                template(section == null ? null : section.getConfigurationSection("empty"),
                        null, "BLACK_STAINED_GLASS_PANE", "", List.of(), false),
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
        boolean glow = section == null ? defaultGlow : section.getBoolean("glow", defaultGlow);
        // material 保留原来的材质名兼容，同时允许写 LiuLib 完整物品规格
        // （base64: / nbt: / ce:ns:id / lib:物品库ID）。item 优先，material 仅在 item 为空时作为规格来源；
        // ItemTemplate.build 先走 LiuLib 解析，失败再回退到已解析出的原版材质。
        String itemSpec = item == null ? "" : item.trim();
        if (itemSpec.isEmpty() && materialName != null && !materialName.isBlank()) {
            itemSpec = materialName.trim();
        }
        return new ItemTemplate(itemSpec, material, name, List.copyOf(lore), glow);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
