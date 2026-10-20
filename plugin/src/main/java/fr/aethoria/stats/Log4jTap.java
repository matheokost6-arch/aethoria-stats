package fr.aethoria.stats;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/** Branche un appender Log4j2 sur le logger racine pour alimenter le tampon de la console. */
public final class Log4jTap extends AbstractAppender {
    private final ConsoleBuffer buffer;
    private org.apache.logging.log4j.core.Logger root;

    private Log4jTap(ConsoleBuffer buffer) {
        super("AethoriaStatsTap", null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        this.buffer = buffer;
    }

    /** Renvoie null si Log4j2 n'est pas disponible. */
    public static Log4jTap attach(ConsoleBuffer buffer) {
        try {
            Log4jTap tap = new Log4jTap(buffer);
            tap.start();
            org.apache.logging.log4j.core.Logger r = (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
            r.addAppender(tap);
            tap.root = r;
            return tap;
        } catch (Throwable t) {
            return null;
        }
    }

    public void detach() {
        if (root != null) root.removeAppender(this);
        stop();
    }

    @Override
    public void append(LogEvent e) {
        try {
            String msg = e.getMessage().getFormattedMessage();
            Throwable th = e.getThrown();
            if (th != null) msg += " (" + th.getClass().getSimpleName() + ": " + th.getMessage() + ")";
            buffer.add(e.getTimeMillis(), e.getLevel().name(), msg);
        } catch (Throwable ignored) {
            // ne jamais gêner le journal du serveur
        }
    }
}
