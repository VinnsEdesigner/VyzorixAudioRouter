package com.vyzorix.audiorouter.common.logging

/**
 * Pluggable logging facade. Layer 0 ships [ConsoleLogger] as the default
 * implementation; Layer 6 binds a Logcat-aware impl (LogcatBridge) and adds a
 * file-backed impl (FileLogger). Higher layers depend only on this interface.
 */
public interface Logger {
    public fun verbose(tag: String, message: String, throwable: Throwable? = null)
    public fun debug(tag: String, message: String, throwable: Throwable? = null)
    public fun info(tag: String, message: String, throwable: Throwable? = null)
    public fun warn(tag: String, message: String, throwable: Throwable? = null)
    public fun error(tag: String, message: String, throwable: Throwable? = null)
}

public enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }
