package com.company.testgen.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import org.slf4j.LoggerFactory;

/**
 * Configures logstash-logback-encoder for structured JSON logging to stdout (§9.3, FR-33, FR-34).
 */
public final class LoggingBootstrap {

    private LoggingBootstrap() {}

    public static void bootstrap() {
        bootstrap(Level.INFO);
    }

    public static void bootstrap(Level rootLevel) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }

        context.reset();

        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.setIncludeMdc(true);
        encoder.setIncludeCallerData(false);
        encoder.start();

        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
        appender.setContext(context);
        appender.setName("CONSOLE_JSON");
        appender.setEncoder(encoder);
        appender.start();

        ch.qos.logback.classic.Logger rootLogger = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(rootLevel != null ? rootLevel : Level.INFO);
        rootLogger.addAppender(appender);
    }
}
