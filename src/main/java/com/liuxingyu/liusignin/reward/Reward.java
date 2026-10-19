package com.liuxingyu.liusignin.reward;

import com.liuxingyu.liulib.item.ItemService;
import com.liuxingyu.liulib.item.ItemSpec;
import org.bukkit.Material;

import java.util.Locale;

/**
 * 单条奖励。
 *
 * <p>由 rewards.yml 的字符串 DSL 解析而来，例如 {@code money:1000}、
 * {@code item:DIAMOND*3}、{@code cmd:console: say hi}。</p>
 */
public record Reward(Type type, String value, double amount, String mode) {

    public enum Type {
        MONEY,
        EXP,
        ITEM,
        COMMAND,
        CARD
    }

    public static Reward money(double amount) {
        return new Reward(Type.MONEY, formatNumber(amount), amount, "");
    }

    public static Reward exp(int amount) {
        return new Reward(Type.EXP, String.valueOf(amount), amount, "");
    }

    public static Reward card(int amount) {
        return new Reward(Type.CARD, String.valueOf(amount), amount, "");
    }

    public static Reward item(String spec) {
        return new Reward(Type.ITEM, spec, 0, "");
    }

    /**
     * 解析 DSL；格式错误抛 {@link IllegalArgumentException}。
     */
    public static Reward parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("奖励为空");
        }
        String text = raw.trim();
        int index = text.indexOf(':');
        if (index <= 0) {
            throw new IllegalArgumentException("缺少类型前缀: " + raw);
        }
        String type = text.substring(0, index).trim().toLowerCase(Locale.ROOT);
        String body = text.substring(index + 1).trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("奖励内容为空: " + raw);
        }
        return switch (type) {
            case "money", "eco", "coin" -> new Reward(Type.MONEY, body, parseAmount(body, raw), "");
            case "exp", "xp" -> new Reward(Type.EXP, body, parseAmount(body, raw), "");
            case "card", "makeup" -> new Reward(Type.CARD, body, parseAmount(body, raw), "");
            case "item" -> new Reward(Type.ITEM, body, 0, "");
            case "cmd", "command" -> parseCommand(body);
            default -> throw new IllegalArgumentException("未知奖励类型: " + type);
        };
    }

    private static Reward parseCommand(String body) {
        if (body.regionMatches(true, 0, "player:", 0, 7)) {
            return new Reward(Type.COMMAND, body.substring(7).trim(), 0, "player");
        }
        if (body.regionMatches(true, 0, "console:", 0, 8)) {
            return new Reward(Type.COMMAND, body.substring(8).trim(), 0, "console");
        }
        return new Reward(Type.COMMAND, body, 0, "console");
    }

    private static double parseAmount(String body, String raw) {
        try {
            return Double.parseDouble(body);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("奖励数量不是数字: " + raw, error);
        }
    }

    /** 玩家可读的奖励摘要（月历 lore / 聊天消息）。 */
    public String describe(ItemService items) {
        return describe(items, false);
    }

    /**
     * GUI / 聊天用摘要：原版物品输出客户端翻译标签（{@code <lang:item.minecraft.diamond>}），
     * 中文客户端显示“钻石”，英文客户端显示“Diamond”，不再依赖服务端语言文件。
     */
    public String describeForDisplay(ItemService items) {
        return describe(items, true);
    }

    private String describe(ItemService items, boolean clientTranslated) {
        return switch (type) {
            case MONEY -> "金币 " + formatNumber(amount);
            case EXP -> "经验 " + formatNumber(amount);
            case CARD -> "补签卡 " + formatNumber(amount);
            case COMMAND -> "命令奖励";
            case ITEM -> {
                ItemSpec spec = items == null ? null : items.parser().parseOrNull(value);
                if (spec == null) {
                    yield "物品 " + value;
                }
                String label = null;
                if (clientTranslated && spec.isVanilla()) {
                    try {
                        Material material = Material.matchMaterial(spec.id());
                        if (material != null && !material.isAir()) {
                            label = "<lang:" + material.getTranslationKey() + ">";
                        }
                    } catch (RuntimeException ignored) {
                        // 回退到 LiuLib 的普通名称
                    }
                }
                if (label == null) {
                    label = spec.isEncoded()
                            ? "自定义物品"
                            : items.names().labelById(spec.canonicalId());
                }
                yield spec.amount() > 1 ? label + " x" + spec.amount() : label;
            }
        };
    }

    /** 完整原始内容，便于日志排障。 */
    public String raw() {
        return switch (type) {
            case MONEY -> "money:" + formatNumber(amount);
            case EXP -> "exp:" + formatNumber(amount);
            case CARD -> "card:" + formatNumber(amount);
            case ITEM -> "item:" + value;
            case COMMAND -> "cmd:" + mode + ":" + value;
        };
    }

    public static String formatNumber(double amount) {
        if (amount == Math.rint(amount) && !Double.isInfinite(amount)) {
            return String.valueOf((long) amount);
        }
        return String.format(Locale.ROOT, "%.2f", amount);
    }
}
