package com.liuxingyu.liusignin.data;

import java.util.UUID;

/**
 * 邮箱条目。{@code payload} 是带类型前缀的字符串：
 *
 * <ul>
 *   <li>{@code item:<base64>} —— LiuLib 物品编码</li>
 *   <li>{@code money:1000} —— 金币</li>
 *   <li>{@code exp:50} —— 经验点</li>
 * </ul>
 */
public record MailEntry(long id, UUID uniqueId, String payload, long createdAt) {

    public String kind() {
        int index = payload == null ? -1 : payload.indexOf(':');
        return index <= 0 ? "" : payload.substring(0, index).toLowerCase();
    }

    public String value() {
        int index = payload == null ? -1 : payload.indexOf(':');
        return index <= 0 ? "" : payload.substring(index + 1);
    }

    public static String item(String base64) {
        return "item:" + base64;
    }

    public static String money(double amount) {
        return "money:" + (amount == Math.rint(amount) && !Double.isInfinite(amount)
                ? String.valueOf((long) amount)
                : String.format(java.util.Locale.ROOT, "%.2f", amount));
    }

    public static String exp(int amount) {
        return "exp:" + amount;
    }
}
