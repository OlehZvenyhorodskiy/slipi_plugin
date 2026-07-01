package me.aquaprivate.util;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Sends clickable chat messages without compile-time dependency on Adventure.
 *
 * IMPORTANT: On some "trimmed" Spigot APIs there is no bungee-chat in compile classpath,
 * but at runtime (Paper/Spigot) the classes exist. Therefore we use reflection.
 *
 * We also use TextComponent#fromLegacyText to correctly parse §-colors and gradients
 * (so messages are not truncated / shown incorrectly).
 */
public final class ClickChat {

    private ClickChat() {}

    /**
     * Sends a legacy-formatted message as BaseComponents with RUN_COMMAND click action.
     * @return true if BaseComponent send succeeded; false if reflection failed
     */
    @SuppressWarnings({"unchecked","rawtypes"})
    public static boolean sendRunCommand(Player player, String legacyText, String hoverLegacy, String command) {
        try {
            Class<?> baseCompClz = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Class<?> textCompClz = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            Class<?> clickEventClz = Class.forName("net.md_5.bungee.api.chat.ClickEvent");
            Class<?> clickActionClz = Class.forName("net.md_5.bungee.api.chat.ClickEvent$Action");
            Class<?> hoverEventClz = Class.forName("net.md_5.bungee.api.chat.HoverEvent");
            Class<?> hoverActionClz = Class.forName("net.md_5.bungee.api.chat.HoverEvent$Action");

            // BaseComponent[] comps = TextComponent.fromLegacyText(legacyText)
            Method fromLegacy = textCompClz.getMethod("fromLegacyText", String.class);
            Object compsObj = fromLegacy.invoke(null, legacyText);
            Object[] comps = (Object[]) compsObj;

            // ClickEvent(Action.RUN_COMMAND, command)
            Object runCmd = Enum.valueOf((Class<Enum>) clickActionClz, "RUN_COMMAND");
            Constructor<?> ceCtor = clickEventClz.getConstructor(clickActionClz, String.class);
            Object ce = ceCtor.newInstance(runCmd, command);

            Method setClick = baseCompClz.getMethod("setClickEvent", clickEventClz);

            // Optional hover event
            Object he = null;
            if (hoverLegacy != null && !hoverLegacy.isEmpty()) {
                Object showText = Enum.valueOf((Class<Enum>) hoverActionClz, "SHOW_TEXT");

                // Hover content: BaseComponent[] (legacy parsed)
                Object hoverCompsObj = fromLegacy.invoke(null, hoverLegacy);
                Object[] hoverComps = (Object[]) hoverCompsObj;

                try {
                    // Old constructor: HoverEvent(Action, BaseComponent[])
                    Constructor<?> heCtor = hoverEventClz.getConstructor(hoverActionClz, Array.newInstance(baseCompClz, 0).getClass());
                    Object hoverArr = Array.newInstance(baseCompClz, hoverComps.length);
                    for (int i = 0; i < hoverComps.length; i++) Array.set(hoverArr, i, hoverComps[i]);
                    he = heCtor.newInstance(showText, hoverArr);
                } catch (NoSuchMethodException ignored) {
                    // Newer bungee-chat uses HoverEvent(Action, Content)
                    // We skip hover if we can't build it safely without compile-time dependency.
                    he = null;
                }
            }
            Method setHover = null;
            if (he != null) {
                setHover = baseCompClz.getMethod("setHoverEvent", hoverEventClz);
            }

            // Apply click/hover to every component so the full line is clickable (stable)
            for (Object c : comps) {
                if (c == null) continue;
                setClick.invoke(c, ce);
                if (he != null && setHover != null) {
                    setHover.invoke(c, he);
                }
            }

            // player.spigot().sendMessage(BaseComponent...)
            Object spigot = player.getClass().getMethod("spigot").invoke(player);
            Method sendMsg = spigot.getClass().getMethod("sendMessage", Array.newInstance(baseCompClz, 0).getClass());

            Object outArr = Array.newInstance(baseCompClz, comps.length);
            for (int i = 0; i < comps.length; i++) Array.set(outArr, i, comps[i]);
            sendMsg.invoke(spigot, outArr);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void sendPlain(CommandSender sender, String msg) {
        sender.sendMessage(msg);
    }
}
