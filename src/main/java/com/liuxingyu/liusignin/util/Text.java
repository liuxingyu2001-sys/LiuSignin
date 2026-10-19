package com.liuxingyu.liusignin.util;

import java.util.Map;

/** 简单占位符替换：{name}。 */
public final class Text {

    private Text() {
    }

    public static String fill(String text, String... pairs) {
        if (text == null || text.isEmpty() || pairs == null) {
            return text == null ? "" : text;
        }
        String out = text;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i] != null) {
                out = out.replace("{" + pairs[i] + "}", pairs[i + 1] == null ? "" : pairs[i + 1]);
            }
        }
        return out;
    }

    public static String fill(String text, Map<String, String> placeholders) {
        if (text == null || text.isEmpty() || placeholders == null || placeholders.isEmpty()) {
            return text == null ? "" : text;
        }
        String out = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return out;
    }
}
