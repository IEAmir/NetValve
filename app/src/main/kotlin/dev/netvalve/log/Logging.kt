package dev.netvalve.log

import kotlinx.coroutines.flow.Flow

/** Severity levels, ordered. The user picks a minimum level to persist/show. */
enum class LogLevel { DEBUG, INFO, WARNING, ERROR }

/** What kind of event a log line represents (drives filtering + icons in the UI). */
enum class LogCategory {
    SYSTEM,          // service lifecycle, tunnel up/down
    CONNECTION_OPEN,
    CONNECTION_CLOSE,
    THROTTLE,        // a cap was applied / bucket paced
    RULE_MATCH,      // a rule decided a flow
    BLOCK,           // a flow was blocked/reset
    DNS,             // DNS query observed
    ERROR,
}

/** An immutable log record. [uid]/[packageName] are optional (some events are global). */
data class LogEvent(
    val timestampMillis: Long,
    val level: LogLevel,
    val category: LogCategory,
    val message: String,
    val uid: Int? = null,
    val packageName: String? = null,
)

/**
 * App-wide structured logger. Implementations must be safe to call from the
 * packet hot path: cheap when a line is below the active level, and rate-limited
 * so a flood of identical events cannot stall forwarding or bloat storage.
 */
interface Logger {
    /** Live tail for the in-app viewer (most-recent-last), backed by a ring buffer. */
    val events: Flow<List<LogEvent>>

    fun setMinLevel(level: LogLevel)

    /** Cheap check so hot-path callers can skip building debug strings when DEBUG is off. */
    fun isEnabled(level: LogLevel): Boolean

    fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        uid: Int? = null,
        packageName: String? = null,
    )

    // Convenience helpers.
    fun d(category: LogCategory, message: String, uid: Int? = null, pkg: String? = null) =
        log(LogLevel.DEBUG, category, message, uid, pkg)

    fun i(category: LogCategory, message: String, uid: Int? = null, pkg: String? = null) =
        log(LogLevel.INFO, category, message, uid, pkg)

    fun w(category: LogCategory, message: String, uid: Int? = null, pkg: String? = null) =
        log(LogLevel.WARNING, category, message, uid, pkg)

    fun e(category: LogCategory, message: String, uid: Int? = null, pkg: String? = null) =
        log(LogLevel.ERROR, category, message, uid, pkg)

    /** Render the persisted log to a shareable plain-text blob for export. */
    suspend fun exportText(): String
}
