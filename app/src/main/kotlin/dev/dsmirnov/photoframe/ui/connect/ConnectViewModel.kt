package dev.dsmirnov.photoframe.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsmirnov.photoframe.AppContainer
import dev.dsmirnov.photoframe.protocol.DiscoveredFrame
import dev.dsmirnov.photoframe.protocol.FrameEndpoint
import dev.dsmirnov.photoframe.ui.describeFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ConnectStep { FIND, MANUAL, CODE }

data class ConnectState(
    val step: ConnectStep = ConnectStep.FIND,
    /** True from the start: the screen opens by scanning, never by claiming nothing was found. */
    val scanning: Boolean = true,
    val scanned: Boolean = false,
    val found: List<DiscoveredFrame> = emptyList(),
    val endpoint: FrameEndpoint? = null,
    val manualHost: String = "",
    val manualPort: String = "",
    val friendCode: String = "",
    val senderName: String = "",
    val pairing: Boolean = false,
    val error: String? = null,
    val pairedFrameName: String? = null,
    val networkName: String = "this Wi-Fi",
)

class ConnectViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(senderName = container.settings.currentSenderName())
        }
        startScan(secondChance = true)
    }

    /** "Scan again": one browse, and whatever it finds is the answer. */
    fun scan() = startScan(secondChance = false)

    /**
     * @param secondChance for the scan that runs when the screen opens: if it finds nothing,
     *   browse once more before saying so. Opening this screen should mean "looking", and a
     *   first browse on a freshly started app can come back empty for reasons that a second
     *   one does not share. Reported on a Pixel 10, 2026-09-23.
     */
    private fun startScan(secondChance: Boolean) {
        // Guard on the job, not on `scanning`: the state starts out scanning.
        if (scanJob?.isActive == true) return
        _state.value = _state.value.copy(scanning = true, error = null)
        scanJob = viewModelScope.launch {
            var found = runCatching { container.discovery.discover() }.getOrDefault(emptyList())
            if (found.isEmpty() && secondChance) {
                container.eventLog.info("discovery", "nothing on the first browse; trying once more")
                found = runCatching { container.discovery.discover() }.getOrDefault(emptyList())
            }
            _state.value = _state.value.copy(
                scanning = false,
                scanned = true,
                found = found,
                // One frame is the overwhelmingly common case, so skip a pointless choice.
                endpoint = found.singleOrNull()?.endpoint ?: _state.value.endpoint,
                step = if (found.size == 1) ConnectStep.CODE else _state.value.step,
            )
        }
    }

    fun select(frame: DiscoveredFrame) {
        _state.value = _state.value.copy(endpoint = frame.endpoint, step = ConnectStep.CODE, error = null)
    }

    fun enterManually() {
        _state.value = _state.value.copy(step = ConnectStep.MANUAL, error = null)
    }

    fun setManualHost(value: String) {
        _state.value = _state.value.copy(manualHost = value.trim())
    }

    fun setManualPort(value: String) {
        _state.value = _state.value.copy(manualPort = value.filter { it.isDigit() })
    }

    fun confirmManualEntry() {
        val host = _state.value.manualHost
        val port = _state.value.manualPort.toIntOrNull()
        if (host.isBlank()) {
            _state.value = _state.value.copy(error = "Enter the frame's address.")
            return
        }
        if (port == null || port !in 1..65535) {
            _state.value = _state.value.copy(error = "Enter the port shown on the frame.")
            return
        }
        _state.value = _state.value.copy(
            endpoint = FrameEndpoint(host, port),
            step = ConnectStep.CODE,
            error = null,
        )
    }

    fun setFriendCode(value: String) {
        // Spaces and hyphens are cosmetic; the frame prints them for readability.
        _state.value = _state.value.copy(friendCode = value.filter { it.isDigit() || it == ' ' || it == '-' })
    }

    fun setSenderName(value: String) {
        _state.value = _state.value.copy(senderName = value.take(100))
    }

    fun pair() {
        val current = _state.value
        val endpoint = current.endpoint ?: return
        if (current.pairing) return
        if (current.senderName.isBlank()) {
            _state.value = current.copy(error = "Enter a name to show on the frame.")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(pairing = true, error = null)
            runCatching {
                container.connections.pair(endpoint, current.friendCode, current.senderName.trim())
                // Immediately connect so the frame's real name and panel size are known
                // before the user lands back on Home.
                container.connections.withSession { it.info.name }
            }.onSuccess { name ->
                _state.value = _state.value.copy(
                    pairing = false,
                    pairedFrameName = name.ifBlank { "your frame" },
                )
            }.onFailure { failure ->
                _state.value = _state.value.copy(
                    pairing = false,
                    error = describeFailure(
                        failure = failure,
                        frameName = "the frame",
                        onWifi = container.socketFactory.isOnWifi,
                    ),
                )
            }
        }
    }
}
