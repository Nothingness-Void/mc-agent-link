package world.agentlink.logs;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

/**
 * Captures every line that goes through Log4j2's root logger and feeds it
 * into {@link LogBuffer}. This catches console /say output (which becomes
 * a regular [Server] log line), plugin/mod log spam, GC/compaction warnings,
 * stack traces — everything visible in the server console.
 *
 * <p>Filter happens at read time (in {@link world.agentlink.dispatch.tools.GetRecentLogsTool})
 * to keep the appender hot path cheap.
 */
public final class LogTap {

    private static volatile boolean attached = false;

    public static synchronized void install() {
        if (attached) return;
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration cfg = ctx.getConfiguration();

        AbstractAppender appender = new AbstractAppender("AgentLinkLogTap", null, null, true, null) {
            @Override
            public void append(LogEvent event) {
                String message;
                try {
                    message = event.getMessage().getFormattedMessage();
                } catch (Exception e) {
                    return;
                }
                String logger = event.getLoggerName();
                String level = event.getLevel().name();
                LogBuffer.get().append(level, logger == null ? "" : logger, message == null ? "" : message);
            }
        };
        appender.start();
        cfg.addAppender(appender);

        LoggerConfig root = cfg.getRootLogger();
        root.addAppender(appender, Level.INFO, null);
        ctx.updateLoggers();
        attached = true;
    }
}
