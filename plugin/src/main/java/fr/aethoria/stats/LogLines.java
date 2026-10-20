package fr.aethoria.stats;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reconnaît, dans une ligne du journal du serveur, ce qui sert aux statistiques. Sans dépendance Bukkit. */
public final class LogLines {
    private LogLines() {}

    private static final Pattern ADV = Pattern.compile(
            "^(\\S{1,32}) has (?:made the advancement|completed the challenge|reached the goal) \\[(.{1,80})\\]$");
    private static final Pattern CHAT = Pattern.compile("^(?:\\[Not Secure\\] )?<[^>\\s]{1,32}> .*");
    private static final Pattern CMD = Pattern.compile("^(\\S{1,32}) issued server command: /(\\S{1,40}).*");
    private static final Pattern CLEARLAG = Pattern.compile(
            "(?i)(?:clear\\s?lag.*?removed\\s+(\\d+))|(?:removed\\s+(\\d+)\\s+(?:entities|entit))|(?:balayeurs.*?:\\s*(\\d+))");

    /**
     * Renvoie {"chat"}, {"cmd", nom}, {"adv", joueur, titre} ou {"clearlag", nombre}, ou null si la ligne n'intéresse pas.
     * Le contenu des messages de chat et les arguments des commandes ne sont jamais renvoyés.
     */
    public static String[] classify(String text) {
        if (text == null || text.length() > 400) return null;
        if (text.startsWith("<") || text.startsWith("[Not Secure] <")) {
            return CHAT.matcher(text).matches() ? new String[] {"chat"} : null;
        }
        Matcher m = CMD.matcher(text);
        if (m.matches()) return new String[] {"cmd", m.group(2)};
        m = ADV.matcher(text);
        if (m.matches()) return new String[] {"adv", m.group(1), m.group(2)};
        m = CLEARLAG.matcher(text);
        if (m.find()) return new String[] {"clearlag", m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3)};
        return null;
    }
}
