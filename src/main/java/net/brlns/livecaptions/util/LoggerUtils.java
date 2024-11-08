/*
 * Copyright (C) 2024 @hstr0100
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.brlns.livecaptions.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import java.io.File;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

/**
 * Utility class for configuring the application logger.
 *
 * @author Gabriel / hstr0100 / vertx010
 */
@Slf4j
public class LoggerUtils {

    private static final String LOG_PATTERN = "%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{36} - %msg%n";

    private static final Level DEFAULT_LOG_LEVEL = Level.INFO;

    /**
     * Sets the log file
     *
     * This method creates a new file appender with a specified log file
     * location and attaches it to the root logger.
     *
     * @param logFile the file where logs will be written
     */
    public static void setLogFile(File logFile) {
        LoggerContext loggerContext = (LoggerContext)LoggerFactory.getILoggerFactory();
        Logger logger = loggerContext.getLogger("ROOT");

        FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();
        fileAppender.setContext(loggerContext);
        fileAppender.setName("LOG_FILE");
        fileAppender.setFile(logFile.getPath());

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setPattern(LOG_PATTERN);
        encoder.start();

        fileAppender.setEncoder(encoder);
        fileAppender.start();

        logger.addAppender(fileAppender);
    }

    /**
     * Sets the logging level
     *
     * @param debug if true, sets the log level to DEBUG;
     * otherwise, sets it to INFO.
     */
    public static void setDebugLogLevel(boolean debug) {
        LoggerContext loggerContext = (LoggerContext)LoggerFactory.getILoggerFactory();
        Logger logger = loggerContext.getLogger("ROOT");

        if (debug && logger.getLevel() != Level.DEBUG) {
            logger.setLevel(Level.DEBUG);

            log.info("Log level changed to: {}", logger.getLevel());
        } else if (!debug && logger.getLevel() != DEFAULT_LOG_LEVEL) {
            logger.setLevel(DEFAULT_LOG_LEVEL);

            log.info("Log level changed to: {}", logger.getLevel());
        }
    }
}
