package com.example.reportsystem.util;

import com.example.reportsystem.platform.CommandActor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

public class Text {
    private static MiniMessage MM = MiniMessage.miniMessage();
    private static String PREFIX = "";

    public static void setPrefix(String pfx) { PREFIX = pfx == null ? "" : pfx; }
    public static void reloadMiniMessage() { MM = MiniMessage.miniMessage(); }

    public static Component mm(String mini) {
        if (mini == null || mini.isBlank()) return Component.empty();
        return MM.deserialize(mini);
    }

    public static void msg(CommandActor actor, String mini) {
        if (actor == null) return;
        actor.sendMessage(mm(PREFIX + (mini == null ? "" : mini)));
    }

    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Optional convenience for console logs using MiniMessage formatting. */
    public static void msgConsole(Logger logger, String mini) {
        if (logger == null) return;
        logger.info(mm(mini).toString());
    }
}
