package com.liuxingyu.liusignin.util;

import com.liuxingyu.liulib.item.ItemService;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * messages.yml 的渲染入口。文本支持 MiniMessage，占位符使用 {@code {name}}。
 */
public final class Messages {

    private final YamlConfiguration yaml;
    private final ItemService items;
    private final Logger logger;
    private final Component prefix;

    public Messages(YamlConfiguration yaml, ItemService items, Logger logger) {
        this.yaml = yaml == null ? new YamlConfiguration() : yaml;
        this.items = items;
        this.logger = logger;
        this.prefix = parse(this.yaml.getString("prefix", ""));
    }

    public static Messages load(File file, ItemService items, Logger logger) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return new Messages(yaml, items, logger);
    }

    /** 带前缀的消息。 */
    public Component msg(String key, String... pairs) {
        Component body = parse(Text.fill(yaml.getString(key, ""), pairs));
        return prefix.append(body);
    }

    /** 不带前缀的消息。 */
    public Component raw(String key, String... pairs) {
        return parse(Text.fill(yaml.getString(key, ""), pairs));
    }

    /** 从消息列表随机取一行原始文本；列表为空返回空串。 */
    public String randomLine(String key) {
        List<String> lines = yaml.getStringList(key);
        if (lines.isEmpty()) {
            return "";
        }
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    /** 列表消息（帮助、排行榜等）。 */
    public List<Component> list(String key, String... pairs) {
        List<Component> out = new ArrayList<>();
        for (String line : yaml.getStringList(key)) {
            out.add(parse(Text.fill(line, pairs)));
        }
        return out;
    }

    public void send(CommandSender sender, String key, String... pairs) {
        if (sender == null) {
            return;
        }
        if (!yaml.contains(key)) {
            logger.warning("messages.yml 缺少消息键: " + key);
            return;
        }
        sender.sendMessage(msg(key, pairs));
    }

    public void sendRaw(CommandSender sender, Component component) {
        if (sender != null && component != null) {
            sender.sendMessage(component);
        }
    }

    public String rawString(String key) {
        return yaml.getString(key, "");
    }

    public boolean has(String key) {
        return yaml.contains(key);
    }

    private Component parse(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        try {
            return items.names().parse(text);
        } catch (RuntimeException error) {
            logger.warning("消息解析失败: " + text);
            return Component.text(text);
        }
    }
}
