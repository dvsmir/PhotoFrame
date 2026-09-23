package app.framealt.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.framealt.AppContainer
import app.framealt.data.QueueItem
import app.framealt.data.QueueState
import app.framealt.data.StoredFrame
import app.framealt.send.LiveSend
import app.framealt.ui.describeFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The frame's reachability as the UI shows it.
 *
 * [READY] means a Wi-Fi network is present and we have an address to try — not that a
 * connection is currently open. The card must never claim more than that: if a send then
 * fails, the status drops to [OFFLINE] with the real reason underneath.
 */
enum class FrameStatus { UNKNOWN, CHECKING, READY, OFFLINE, NO_WIFI }

data class HomeState(
    val frame: StoredFrame? = null,
    /** False until the stored pairing has been read, so Home does not flash the unpaired screen. */
    val loaded: Boolean = false,
    val status: FrameStatus = FrameStatus.UNKNOWN,
    val message: String? = null,
    val senderName: String = "",
    val activity: ActivityState = ActivityState(),
)

/** A finished batch, as one line: "50 photos sent · today 14:32". */
data class BatchSummary(val sent: Int, val completedAt: Long)

/** Home's Activity section. Copy is `Spec/00 - Initial/03 - UX.md` §3. */
data class ActivityState(
    /** Photos in batches that are still going, and how many of them the frame confirmed. */
    val total: Int = 0,
    val done: Int = 0,
    val totalBytes: Long = 0,
    val sentBytes: Long = 0,
    /** True while a photo is actually in flight, as opposed to waiting. */
    val sendingNow: Boolean = false,
    val waiting: Int = 0,
    val failed: List<QueueItem> = emptyList(),
    val recent: List<BatchSummary> = emptyList(),
) {
    val isEmpty: Boolean get() = total == 0 && failed.isEmpty() && recent.isEmpty()
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.frameStore.frame.collect { frame ->
                _state.value = _state.value.copy(
                    frame = frame,
                    loaded = true,
                    status = when {
                        frame == null -> FrameStatus.UNKNOWN
                        !container.socketFactory.isOnWifi -> FrameStatus.NO_WIFI
                        else -> _state.value.status.takeIf { it != FrameStatus.UNKNOWN } ?: FrameStatus.READY
                    },
                )
            }
        }
        viewModelScope.launch {
            container.settings.senderName.collect { name ->
                _state.value = _state.value.copy(senderName = name)
            }
        }
        // Wi-Fi coming and going changes what Home may claim: "Ready" with the phone on mobile
        // data would be a lie, and so would "waiting to go" when it is waiting for Wi-Fi.
        viewModelScope.launch {
            container.socketFactory.wifiAvailable.collect { onWifi ->
                val current = _state.value
                if (current.frame == null) return@collect
                _state.value = when {
                    !onWifi -> current.copy(
                        status = FrameStatus.NO_WIFI,
                        message = "You're not on Wi-Fi. Photo Frame sends photos over your home network.",
                    )
                    current.status == FrameStatus.NO_WIFI -> current.copy(status = FrameStatus.READY, message = null)
                    else -> current
                }
            }
        }
        viewModelScope.launch {
            combine(container.sendQueue.items, container.sendQueue.live, ::summarise).collect { activity ->
                _state.value = _state.value.copy(activity = activity)
            }
        }
        refresh()
    }

    fun retry(item: QueueItem) {
        viewModelScope.launch { container.sendQueue.retry(item.id) }
    }

    fun remove(item: QueueItem) {
        viewModelScope.launch { container.sendQueue.remove(item.id) }
    }

    /** Renames the frame on this phone only; blank goes back to the frame's own name. */
    fun rename(value: String) {
        viewModelScope.launch { container.frameStore.setAlias(value) }
    }

    /** "Send now" on waiting photos: skip whatever backoff the queue is sitting in. */
    fun sendNow() {
        viewModelScope.launch { container.sendQueue.kick() }
    }

    /** Contacts the frame and refreshes what it reports about itself. */
    fun refresh() {
        val current = _state.value
        if (current.status == FrameStatus.CHECKING) return

        viewModelScope.launch {
            if (container.frameStore.current() == null) {
                _state.value = _state.value.copy(status = FrameStatus.UNKNOWN, message = null)
                return@launch
            }
            if (!container.socketFactory.isOnWifi) {
                _state.value = _state.value.copy(
                    status = FrameStatus.NO_WIFI,
                    message = "You're not on Wi-Fi. Photo Frame sends photos over your home network.",
                )
                return@launch
            }

            _state.value = _state.value.copy(status = FrameStatus.CHECKING, message = null)
            runCatching { container.connections.withSession { it.info } }
                .onSuccess {
                    _state.value = _state.value.copy(status = FrameStatus.READY, message = null)
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(
                        status = FrameStatus.OFFLINE,
                        message = describeFailure(
                            failure = failure,
                            frameName = _state.value.frame?.displayName ?: "your frame",
                            onWifi = container.socketFactory.isOnWifi,
                        ),
                    )
                }
        }
    }

    fun forget() {
        viewModelScope.launch {
            // Queued photos were meant for this frame; UX §9 says they are cancelled with it.
            container.sendQueue.cancelAll()
            container.connections.forget()
            _state.value = HomeState(senderName = _state.value.senderName)
        }
    }
}

/** At most this many finished batches are listed under Activity. */
private const val RECENT_BATCHES = 3

/**
 * Folds the queue into what Home shows. Batches that still have work count as one running
 * total, so a second pick while the first is sending reads as "Sending 14 of 60", not as two
 * competing progress bars.
 */
internal fun summarise(items: List<QueueItem>, live: LiveSend?): ActivityState {
    val byBatch = items.groupBy { it.batchId }
    val running = byBatch.values
        .filter { batch -> batch.any { !it.state.isFinished } }
        .flatten()
        .filter { it.state != QueueState.CANCELLED }

    val liveBytes = live?.let { l -> running.firstOrNull { it.id == l.itemId }?.let { l.sentBytes } } ?: 0L
    val done = running.filter { it.state == QueueState.SENT }

    val recent = byBatch.values
        .filter { batch -> batch.all { it.state.isFinished } }
        .mapNotNull { batch ->
            val sent = batch.filter { it.state == QueueState.SENT }
            if (sent.isEmpty()) null else BatchSummary(sent.size, sent.maxOf { it.completedAt ?: 0L })
        }
        .sortedByDescending { it.completedAt }
        .take(RECENT_BATCHES)

    return ActivityState(
        total = running.size,
        done = done.size,
        totalBytes = running.sumOf { it.preparedBytes },
        sentBytes = done.sumOf { it.preparedBytes } + liveBytes,
        sendingNow = live != null,
        waiting = running.count { it.state == QueueState.PREPARED || it.state == QueueState.SENDING },
        failed = items.filter { it.state == QueueState.FAILED },
        recent = recent,
    )
}
