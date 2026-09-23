package app.framealt.ui.gallery

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.framealt.AppContainer
import app.framealt.protocol.client.MediaDownload
import app.framealt.protocol.client.MediaItem
import app.framealt.protocol.client.MediaType
import app.framealt.ui.describeFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-size photos are decoded no larger than this on their long side, to bound memory. */
private const val MAX_PREVIEW_PIXELS = 2048

data class PhotoState(
    val frameName: String = "your frame",
    val item: MediaItem? = null,
    val canManage: Boolean = false,
    val loading: Boolean = true,
    val bitmap: Bitmap? = null,
    val caption: String = "",
    /** Why there is no picture: a video, or a failed fetch. */
    val unavailable: String? = null,
    val working: Boolean = false,
    val confirmingDelete: Boolean = false,
    val message: String? = null,
    /** Set after a delete; the screen goes back to the grid. */
    val gone: Boolean = false,
)

class PhotoViewModel(private val container: AppContainer, private val id: Long) : ViewModel() {

    private val _state = MutableStateFlow(PhotoState())
    val state: StateFlow<PhotoState> = _state.asStateFlow()

    /** The original bytes, kept for Save to phone. */
    private var download: MediaDownload? = null

    init {
        viewModelScope.launch {
            container.gallery.items.collect { items -> _state.update { it.copy(item = items.firstOrNull { i -> i.id == id }) } }
        }
        viewModelScope.launch {
            container.frameStore.frame.collect { frame ->
                _state.update { it.copy(frameName = frame?.displayName ?: "your frame", canManage = frame?.canManage == true) }
            }
        }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val fetched = container.gallery.fetchFull(id)
                download = fetched
                val bitmap = withContext(Dispatchers.Default) { decodeBounded(fetched.bytes) }
                _state.update {
                    it.copy(
                        loading = false,
                        bitmap = bitmap,
                        caption = fetched.caption,
                        unavailable = if (bitmap == null) "Photo Frame can only show photos for now." else null,
                    )
                }
            } catch (failure: Exception) {
                val video = _state.value.item?.type != MediaType.PHOTO
                _state.update {
                    it.copy(
                        loading = false,
                        unavailable = if (video) "Photo Frame can only show photos for now." else describe(failure),
                    )
                }
            }
        }
    }

    /** Writes the frame's original to `Pictures/FrameAlt` through MediaStore; no permission needed. */
    fun save(context: Context) {
        val original = download ?: return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val extension = original.extension.ifBlank { "webp" }.lowercase()
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "frame-${java.lang.Long.toHexString(id)}.$extension")
                        put(MediaStore.Images.Media.MIME_TYPE, mimeFor(extension))
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Photo Frame")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                    resolver.openOutputStream(uri).use { out -> checkNotNull(out).write(original.bytes) }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                }.isSuccess
            }
            _state.update { it.copy(message = if (saved) "Saved to Pictures/Photo Frame." else "Couldn't save the photo to this phone.") }
        }
    }

    fun displayNow() = act("Showing it on ${_state.value.frameName} now.") { container.gallery.displayNow(id) }

    fun setVisible(visible: Boolean) = act(if (visible) "Back in the slideshow." else "Hidden from the slideshow.") {
        container.gallery.setVisibility(setOf(id), visible)
    }

    fun askDelete() = _state.update { it.copy(confirmingDelete = true) }

    fun dismissDelete() = _state.update { it.copy(confirmingDelete = false) }

    fun confirmDelete() {
        _state.update { it.copy(confirmingDelete = false) }
        act(null) {
            container.gallery.delete(setOf(id))
            _state.update { it.copy(gone = true) }
        }
    }

    private fun act(done: String?, action: suspend () -> Unit) {
        if (_state.value.working) return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            try {
                action()
                if (done != null) _state.update { it.copy(message = done) }
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

private fun mimeFor(extension: String) = when (extension) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "heic" -> "image/heic"
    else -> "image/webp"
}
