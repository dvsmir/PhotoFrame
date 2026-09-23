package app.framealt.ui.review

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.framealt.AppContainer
import app.framealt.media.PixelSize
import app.framealt.media.PrepareException
import app.framealt.media.PreparedPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "review"

sealed interface ReviewPhoto {
    val uri: Uri

    data class Preparing(override val uri: Uri) : ReviewPhoto

    data class Ready(override val uri: Uri, val prepared: PreparedPhoto, val alreadySent: Boolean) : ReviewPhoto

    data class Failed(override val uri: Uri, val displayName: String) : ReviewPhoto
}

data class ReviewState(
    val frameName: String = "your frame",
    val photos: List<ReviewPhoto> = emptyList(),
    val caption: String = "",
    val fit: Boolean = true,
    val sending: Boolean = false,
    /** Set once the batch is queued; the screen navigates away. */
    val done: Boolean = false,
) {
    val usable: List<ReviewPhoto.Ready> get() = photos.filterIsInstance<ReviewPhoto.Ready>()
    val failed: List<ReviewPhoto.Failed> get() = photos.filterIsInstance<ReviewPhoto.Failed>()
    val preparingCount: Int get() = photos.count { it is ReviewPhoto.Preparing }
    val preparedCount: Int get() = photos.size - preparingCount
    val isPreparing: Boolean get() = preparingCount > 0
    val duplicateCount: Int get() = usable.count { it.alreadySent }
}

/**
 * Prepares the picked photos right away, one at a time, while the picker's URI grant is
 * alive. Preparing before Send is what lets the screen say "Already sent": the content ID is
 * a hash of the prepared bytes. The caption and fit switch travel as metadata and do not
 * change those bytes, so nothing has to be redone when they change.
 */
class ReviewViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ReviewState())
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    init {
        val uris = container.pendingPicks.distinct()
        container.pendingPicks = emptyList()
        _state.value = ReviewState(photos = uris.map { ReviewPhoto.Preparing(it) })

        viewModelScope.launch {
            val frame = container.frameStore.current()
            val fit = container.settings.fitByDefault.first()
            _state.update { it.copy(frameName = frame?.displayName ?: "your frame", fit = fit) }
            prepareAll(uris, frame?.let { PixelSize(it.width, it.height) } ?: FALLBACK_PANEL, frame?.peerId)
        }
    }

    private suspend fun prepareAll(uris: List<Uri>, panel: PixelSize, peerId: String?) {
        val quality = container.settings.webpQuality.first()
        for (uri in uris) {
            if (_state.value.photos.none { it.uri == uri }) continue // removed while waiting
            val result = withContext(Dispatchers.Default) {
                try {
                    container.imagePipeline.prepare(uri, panel, quality)
                } catch (failure: PrepareException) {
                    container.eventLog.warn(TAG, "could not prepare a photo: ${failure.message}")
                    failure
                }
            }
            val next: ReviewPhoto = when (result) {
                is PreparedPhoto -> {
                    val seen = peerId != null &&
                        container.database.ledger().alreadySent(peerId, listOf(result.contentId)).isNotEmpty()
                    ReviewPhoto.Ready(uri, result, seen)
                }
                is PrepareException -> ReviewPhoto.Failed(uri, result.displayName)
                else -> error("unreachable")
            }
            var kept = false
            _state.update { state ->
                kept = state.photos.any { it.uri == uri }
                state.copy(photos = state.photos.map { if (it.uri == uri) next else it })
            }
            if (!kept && next is ReviewPhoto.Ready) next.prepared.file.delete()
        }
        container.eventLog.info(TAG, "prepared ${_state.value.usable.size} of ${uris.size} photo(s)")
    }

    fun remove(uri: Uri) {
        val removed = _state.value.photos.firstOrNull { it.uri == uri } ?: return
        _state.update { it.copy(photos = it.photos - removed) }
        if (removed is ReviewPhoto.Ready) removed.prepared.file.delete()
    }

    fun setCaption(value: String) = _state.update { it.copy(caption = value.take(MAX_CAPTION)) }

    fun setFit(value: Boolean) = _state.update { it.copy(fit = value) }

    fun send() {
        val current = _state.value
        if (current.isPreparing || current.sending || current.usable.isEmpty()) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            val frame = container.frameStore.current()
            if (frame == null) {
                _state.update { it.copy(sending = false) }
                return@launch
            }
            container.sendQueue.enqueue(
                peerId = frame.peerId,
                photos = current.usable.map { it.prepared },
                caption = current.caption.trim(),
                fit = current.fit,
            )
            _state.update { it.copy(done = true) }
        }
    }

    /** Leaving without sending: the prepared files belong to nobody now. */
    override fun onCleared() {
        if (!_state.value.done) _state.value.usable.forEach { it.prepared.file.delete() }
    }

    private companion object {
        /** The reference client's default panel, used only before a frame has ever been reached. */
        val FALLBACK_PANEL = PixelSize(1280, 800)
        const val MAX_CAPTION = 200
    }
}
