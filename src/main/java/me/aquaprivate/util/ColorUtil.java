package me.aquaprivate.util;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Цвета: поддержка &-кодов, формата &#RRGGBB и уже готового §x§R§R... (градиенты).
 *
 * Не используем net.md_5.bungee.api.ChatColor, чтобы не тащить лишние зависимости в оффлайн Maven.
 */
public final class ColorUtil {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private ColorUtil() {}

    public static String color(String input) {
        if (input == null) return "";

        // Convert &#RRGGBB -> §x§R§R§G§G§B§B (vanilla hex format)
        String s = input;
        Matcher m = HEX.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String hex = m.group(1);
            String replacement = toSectionHex(hex);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);

        // Translate legacy & codes
        return ChatColor.translateAlternateColorCodes('&', out.toString());
    }

    private static String toSectionHex(String hex6) {
        // hex6 = RRGGBB
        StringBuilder sb = new StringBuilder(14);
        sb.append('§').append('x');
        for (int i = 0; i < 6; i++) {
            sb.append('§').append(hex6.charAt(i));
        }
        return sb.toString();
    }
}
