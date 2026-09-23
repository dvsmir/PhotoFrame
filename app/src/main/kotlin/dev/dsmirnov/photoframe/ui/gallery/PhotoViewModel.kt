package dev.dsmirnov.photoframe.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsmirnov.photoframe.AppContainer
import dev.dsmirnov.photoframe.gallery.MediaFilter
import dev.dsmirnov.photoframe.protocol.client.MediaItem
import dev.dsmirnov.photoframe.protocol.client.MediaType
import dev.dsmirnov.photoframe.ui.describeFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-size photos are decoded no larger than this on their long side, to bound memory. */
private const val MAX_PREVIEW_PIXELS = 2048

/** Photos this many pages away from the one on screen are fetched ahead of a swipe. */
private const val PREFETCH = 1

/** Photos further away than this are dropped, so swiping through hundreds stays bounded. */
private const val KEEP = 2

private const val PHOTOS_ONLY = "Photo Frame can only show photos for now."

/** One full-size photo, as far as it has got. */
sealed interface PhotoLoad {
    data object Loading : PhotoLoad

    data class Loaded(val bitmap: Bitmap, val caption: String) : PhotoLoad

    /** A video, or a fetch that failed; [text] says which, in UX §7 words. */
    data class Unavailable(val text: String) : PhotoLoad
}

data class PhotoState(
    val frameName: String = "your frame",
    val items: List<MediaItem> = emptyList(),
    /** The photo on screen. Actions apply to it. */
    val currentId: Long,
    val loads: Map<Long, PhotoLoad> = emptyMap(),
    val canManage: Boolean = false,
    val working: Boolean = false,
    val confirmingDelete: Boolean = false,
    val message: String? = null,
    /** Set when the last photo was deleted; the screen goes back to the grid. */
    val gone: Boolean = false,
) {
    val current: MediaItem? get() = items.firstOrNull { it.id == currentId }
}

/**
 * The swipeable preview: every item the gallery lists, in its order, starting at the one
 * tapped. Only the photo on screen and its neighbours are held at full size.
 */
class PhotoViewModel(private val container: AppContainer, startId: Long) : ViewModel() {

    private val _state = MutableStateFlow(PhotoState(currentId = startId))
    val state: StateFlow<PhotoState> = _state.asStateFlow()

    private val jobs = HashMap<Long, Job>()

    init {
        viewModelScope.launch {
            // Swipes stay inside whatever the grid was filtered to.
            combine(container.gallery.items, container.gallery.filter) { items, filter ->
                if (filter == MediaFilter.ALL) items else items.filter(filter::matches)
            }.collect { items ->
                _state.update { it.copy(items = items, gone = items.isEmpty()) }
            }
        }
        viewModelScope.launch {
            container.frameStore.frame.collect { frame ->
                _state.update { it.copy(frameName = frame?.displayName ?: "your frame", canManage = frame?.canManage == true) }
            }
        }
    }

    /** Called when a page settles: make it current, fetch it and its neighbours, drop the rest. */
    fun show(id: Long) {
        _state.update { it.copy(currentId = id) }
        val items = _state.value.items
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return

        // The one on screen first: the frame answers one request at a time.
        val wanted = (index - PREFETCH..index + PREFETCH).mapNotNull { items.getOrNull(it)?.id }
        (listOf(id) + wanted.filter { it != id }).forEach(::load)

        val keep = (index - KEEP..index + KEEP).mapNotNull { items.getOrNull(it)?.id }.toSet()
        jobs.keys.filterNot { it in keep }.forEach { jobs.remove(it)?.cancel() }
        _state.update { state -> state.copy(loads = state.loads.filterKeys { it in keep }) }
    }

    private fun load(id: Long) {
        if (id in _state.value.loads || jobs[id]?.isActive == true) return
        _state.update { it.copy(loads = it.loads + (id to PhotoLoad.Loading)) }
        jobs[id] = viewModelScope.launch {
            val result = try {
                val fetched = container.gallery.fetchFull(id)
                val bitmap = withContext(Dispatchers.Default) { decodeBounded(fetched.bytes) }
                if (bitmap == null) PhotoLoad.Unavailable(PHOTOS_ONLY) else PhotoLoad.Loaded(bitmap, fetched.caption)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                val item = _state.value.items.firstOrNull { it.id == id }
                PhotoLoad.Unavailable(if (item?.type != MediaType.PHOTO) PHOTOS_ONLY else describe(failure))
            }
            _state.update { state ->
                // Dropped while it was loading: it has left the window, so keep it out.
                if (id in state.loads) state.copy(loads = state.loads + (id to result)) else state
            }
        }
    }

    fun displayNow() = act("Showing it on ${_state.value.frameName} now.") { container.gallery.displayNow(it) }

    fun setVisible(visible: Boolean) = act(if (visible) "Back in the slideshow." else "Hidden from the slideshow.") {
        container.gallery.setVisibility(setOf(it), visible)
    }

    fun askDelete() = _state.update { it.copy(confirmingDelete = true) }

    fun dismissDelete() = _state.update { it.copy(confirmingDelete = false) }

    /** Deletes the photo on screen; the pager then shows its neighbour. */
    fun confirmDelete() {
        _state.update { it.copy(confirmingDelete = false) }
        act("Deleted.") { id ->
            container.gallery.delete(setOf(id))
            _state.update { state -> state.copy(loads = state.loads - id) }
        }
    }

    private fun act(done: String, action: suspend (Long) -> Unit) {
        if (_state.value.working) return
        val id = _state.value.currentId
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            try {
                action(id)
                _state.update { it.copy(message = done) }
            } catch (failure: Exception) {
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

private fun decodeBounded(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PREVIEW_PIXELS) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
