package app.framealt.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * An in-memory ring buffer of protocol events.
 *
 * The failure modes here are invisible by nature — mDNS blocked by a router, the phone on a
 * guest SSID, a sleeping frame, a missing permission — and they all surface to the user as
 * the same "can't reach the frame". Without a log the only debugging tool is guesswork.
 *
 * Nothing secret goes in here: no key material, no friend codes, no photo content. Peer IDs
 * are redacted to their first eight hex characters by [redact].
 */
class EventLog(private val capacity: Int = 200) {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    class Event(
        val atMillis: Long,
        val level: Level,
        val tag: String,
        val message: String,
    ) {
        val time: String get() = FORMATTER.format(Instant.ofEpochMilli(atMillis))
        override fun toString(): String = "$time  ${level.name.padEnd(5)} [$tag] $message"
    }

    private val buffer = ArrayDeque<Event>(capacity)
    private val _events = MutableStateFlow<List<Event>>(emptyList())

    val events: StateFlow<List<Event>> = _events

    fun debug(tag: String, message: String) = add(Level.DEBUG, tag, message)
    fun info(tag: String, message: String) = add(Level.INFO, tag, message)
    fun warn(tag: String, message: String) = add(Level.WARN, tag, message)
    fun error(tag: String, message: String) = add(Level.ERROR, tag, message)

    @Synchronized
    private fun add(level: Level, tag: String, message: String) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(Event(System.currentTimeMillis(), level, tag, message))
        _events.value = buffer.toList()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _events.value = emptyList()
    }

    /** The whole log as text, for the "copy diagnostics" action. */
    @Synchronized
    fun dump(): String = buffer.joinToString("\n")

    companion object {
        private val FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

        /** Shortens an identifier so logs and screenshots never carry a full key. */
        fun redact(id: String): String = if (id.length <= 8) id else id.take(8) + "…"
    }
}
