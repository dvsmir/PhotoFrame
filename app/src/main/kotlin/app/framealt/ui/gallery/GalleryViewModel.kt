package app.framealt.ui.gallery

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.framealt.AppContainer
import app.framealt.gallery.GalleryRepository
import app.framealt.gallery.MediaFilter
import app.framealt.protocol.client.FrameInfo
import app.framealt.protocol.client.MediaItem
import app.framealt.ui.describeFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "gallery"

enum class GalleryAccess { LOADING, NEEDS_ACCESS, WAITING, TIMED_OUT, TOO_OLD, READY, FAILED }

data class GalleryState(
    val frameName: String = "your frame",
    val access: GalleryAccess = GalleryAccess.LOADING,
    val canManage: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val thumbnails: Map<Long, Bitmap> = emptyMap(),
    val selection: Set<Long> = emptySet(),
    val refreshing: Boolean = false,
    /** A delete or hide is in flight; the actions are disabled meanwhile. */
    val working: Boolean = false,
    /** Set while the delete confirmation is showing. */
    val confirmingDelete: Boolean = false,
    /** One-shot line for the snackbar. */
    val message: String? = null,
    /** Why the gallery could not load, in UX §7 words. */
    val failure: String? = null,
    val filter: MediaFilter = MediaFilter.ALL,
) {
    /** What the grid shows: [items] through [filter]. */
    val shown: List<MediaItem> get() = if (filter == MediaFilter.ALL) items else items.filter(filter::matches)

    val selecting: Boolean get() = selection.isNotEmpty()
}

/** `Spec/00 - Initial/03 - UX.md` §6. */
class GalleryViewModel(private val container: AppContainer) : ViewModel() {

    private val repository: GalleryRepository = container.gallery

    private val _state = MutableStateFlow(GalleryState())
    val state: StateFlow<GalleryState> = _state.asStateFlow()

    private var accessJob: Job? = null

    init {
        viewModelScope.launch { repository.items.collect { items -> _state.update { it.copy(items = items) } } }
        viewModelScope.launch {
            // A selection made under one filter must not act on items the next one hides.
            repository.filter.collect { f -> _state.update { it.copy(filter = f, selection = emptySet()) } }
        }
        viewModelScope.launch { repository.thumbnails.collect { t -> _state.update { it.copy(thumbnails = t) } } }
        viewModelScope.launch {
            container.frameStore.frame.collect { frame ->
                _state.update { it.copy(frameName = frame?.displayName ?: "your frame", canManage = frame?.canManage == true) }
            }
        }
        refresh()
    }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                apply(repository.refresh())
            } catch (failure: Exception) {
                container.eventLog.warn(TAG, "could not list: ${failure.javaClass.simpleName}: ${failure.message}")
                _state.update {
                    it.copy(
                        access = if (it.items.isEmpty()) GalleryAccess.FAILED else it.access,
                        failure = describe(failure),
                        message = if (it.items.isEmpty()) null else describe(failure),
                    )
                }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun apply(info: FrameInfo) {
        _state.update {
            it.copy(
                canManage = info.permissions.manage,
                failure = null,
                access = when {
                    info.protocolVersion in 1 until GalleryRepository.MIN_GALLERY_VERSION -> GalleryAccess.TOO_OLD
                    !info.permissions.view -> GalleryAccess.NEEDS_ACCESS
                    else -> GalleryAccess.READY
                },
            )
        }
    }

    /** Asks for view + manage, then waits up to five minutes for the owner to allow it. */
    fun requestAccess() {
        if (accessJob?.isActive == true) return
        val wasReady = _state.value.access == GalleryAccess.READY
        if (!wasReady) _state.update { it.copy(access = GalleryAccess.WAITING) }
        accessJob = viewModelScope.launch {
            try {
                val info = repository.requestAccess()
                if (info == null) {
                    if (!wasReady) _state.update { it.copy(access = GalleryAccess.TIMED_OUT) }
                } else {
                    apply(repository.refresh())
                }
            } catch (failure: Exception) {
                _state.update { it.copy(access = if (wasReady) it.access else GalleryAccess.FAILED, failure = describe(failure)) }
            }
        }
        if (wasReady) {
            _state.update { it.copy(message = "Asked the frame. Tap Allow on the frame, then refresh.") }
        }
    }

    fun cancelRequest() {
        accessJob?.cancel()
        _state.update { it.copy(access = GalleryAccess.NEEDS_ACCESS) }
    }

    fun setFilter(filter: MediaFilter) = repository.setFilter(filter)

    fun requestThumbnail(id: Long) = repository.requestThumbnail(id)

    // --- selection --------------------------------------------------------------------

    fun toggle(id: Long) = _state.update {
        it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
    }

    fun startSelection(id: Long) {
        if (_state.value.canManage) _state.update { it.copy(selection = it.selection + id) }
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    /**
     * Selects what this phone sent, by the media IDs it recorded. It cannot select anything
     * someone else sent, which is what makes it the safe way to clear your own uploads.
     */
    fun selectSentFromThisPhone() {
        viewModelScope.launch {
            val frame = container.frameStore.current() ?: return@launch
            val mine = repository.sentFromThisPhone(frame.peerId)
            val onFrame = _state.value.shown.map { it.id }.filter { it in mine }.toSet()
            container.eventLog.info(TAG, "this phone sent ${mine.size} item(s); ${onFrame.size} still on the frame")
            _state.update {
                it.copy(
                    selection = onFrame,
                    message = if (onFrame.isEmpty()) "None of the photos on the frame were sent from this phone." else null,
                )
            }
        }
    }

    // --- managing ---------------------------------------------------------------------

    fun askDelete() {
        if (_state.value.selecting) _state.update { it.copy(confirmingDelete = true) }
    }

    fun dismissDelete() = _state.update { it.copy(confirmingDelete = false) }

    fun confirmDelete() {
        val ids = _state.value.selection
        _state.update { it.copy(confirmingDelete = false) }
        run(ids, done = { "Deleted ${items(ids.size)}." }) { repository.delete(ids) }
    }

    fun setVisibility(visible: Boolean) {
        val ids = _state.value.selection
        run(ids, done = { if (visible) "Showing ${items(ids.size)} again." else "Hid ${items(ids.size)}." }) {
            repository.setVisibility(ids, visible)
        }
    }

    private fun run(ids: Set<Long>, done: () -> String, action: suspend () -> Unit) {
        if (ids.isEmpty() || _state.value.working) return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            try {
                action()
                _state.update { it.copy(selection = emptySet(), message = done()) }
            } catch (failure: Exception) {
                container.eventLog.warn(TAG, "manage failed: ${failure.javaClass.simpleName}: ${failure.message}")
                // A batched delete may have partly succeeded; the list is the truth.
                runCatching { repository.refresh() }
                _state.update { it.copy(message = describe(failure)) }
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun describe(failure: Throwable) =
        describeFailure(failure, _state.value.frameName, container.socketFactory.isOnWifi)
}

internal fun items(count: Int): String = if (count == 1) "1 photo" else "$count photos"
