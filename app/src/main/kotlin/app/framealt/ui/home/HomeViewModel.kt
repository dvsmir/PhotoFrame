package app.framealt.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.framealt.AppContainer
import app.framealt.data.StoredFrame
import app.framealt.ui.describeFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val status: FrameStatus = FrameStatus.UNKNOWN,
    val message: String? = null,
    val senderName: String = "",
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.frameStore.frame.collect { frame ->
                _state.value = _state.value.copy(
                    frame = frame,
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
        refresh()
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
                    message = "You're not on Wi-Fi. FrameAlt sends photos over your home network.",
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
            container.connections.forget()
            _state.value = HomeState(senderName = _state.value.senderName)
        }
    }
}
