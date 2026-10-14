package com.company.testgen.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingBootstrapTest {

    @Test
    @DisplayName("LoggingBootstrap configures ConsoleAppender with LogstashEncoder and MDC inclusion")
    void testBootstrapConfiguresJsonLogger() {
        LoggingBootstrap.bootstrap(Level.DEBUG);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);

        assertThat(rootLogger.getLevel()).isEqualTo(Level.DEBUG);

        Appender<ILoggingEvent> appender = rootLogger.getAppender("CONSOLE_JSON");
        assertThat(appender).isNotNull();
        assertThat(appender).isInstanceOf(ConsoleAppender.class);

        ConsoleAppender<ILoggingEvent> consoleAppender = (ConsoleAppender<ILoggingEvent>) appender;
        assertThat(consoleAppender.getEncoder()).isInstanceOf(LogstashEncoder.class);

        LogstashEncoder encoder = (LogstashEncoder) consoleAppender.getEncoder();
        assertThat(encoder.isIncludeMdc()).isTrue();
    }
}
