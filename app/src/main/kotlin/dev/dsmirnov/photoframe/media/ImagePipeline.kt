package dev.dsmirnov.photoframe.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import dev.dsmirnov.photoframe.protocol.Frameo
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/** A photo turned into what the frame wants: a WebP at panel resolution, on disk. */
class PreparedPhoto(
    val file: File,
    val bytes: Long,
    val displayName: String,
    val capturedAtMs: Long,
    val contentId: Long,
)

/** A photo that could not be prepared. Per photo: it never aborts the rest of the batch. */
class PrepareException(val displayName: String, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * `content://` URI → app-private WebP. See `Spec/00 - Initial/02 - Architecture.md` §6.
 *
 * This runs in the foreground, while the URI grant from the picker or share sheet is still
 * alive. The queue never holds a URI, only the file this produces.
 */
class ImagePipeline(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** Where prepared photos live until the frame confirms them. */
    val outbox: File get() = File(context.filesDir, "outbox").apply { mkdirs() }

    fun prepare(uri: Uri, panel: PixelSize, quality: Int): PreparedPhoto {
        val name = displayName(uri)
        val bitmap = try {
            decode(uri, panel)
        } catch (failure: PrepareException) {
            throw failure
        } catch (failure: IOException) {
            throw PrepareException(name, "could not decode: ${failure.javaClass.simpleName}", failure)
        } catch (failure: RuntimeException) {
            // ImageDecoder reports unsupported and corrupt input as runtime exceptions.
            throw PrepareException(name, "could not decode: ${failure.javaClass.simpleName}", failure)
        }

        val file = File(outbox, "${UUID.randomUUID()}.webp")
        try {
            val opaque = flattenOntoWhite(bitmap)
            file.outputStream().use { out ->
                if (!opaque.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)) {
                    throw PrepareException(name, "WebP encoder refused the image")
                }
            }
            if (opaque !== bitmap) opaque.recycle()
        } catch (failure: IOException) {
            file.delete()
            throw PrepareException(name, "could not write the prepared file", failure)
        } finally {
            bitmap.recycle()
        }

        val size = file.length()
        if (size <= 0 || size > MAX_PREPARED_BYTES) {
            file.delete()
            throw PrepareException(name, "prepared file is $size bytes")
        }

        return PreparedPhoto(
            file = file,
            bytes = size,
            displayName = name,
            capturedAtMs = capturedAt(uri),
            contentId = Frameo.contentIdFor(file.readBytes()),
        )
    }

    /**
     * Decodes at the target size directly, so a 50 MP source never becomes a 200 MB bitmap.
     * `ImageDecoder` applies EXIF orientation itself; the size it reports and the size it is
     * given are in the same, already-rotated, coordinates, so nothing is rotated twice.
     */
    private fun decode(uri: Uri, panel: PixelSize): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            if (width.toLong() * height > MAX_SOURCE_PIXELS) {
                throw PrepareException(displayName(uri), "source is ${width}x$height, over the pixel limit")
            }
            val target = targetSize(PixelSize(width, height), panel)
            decoder.setTargetSize(target.width, target.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // The frame is an SDR sRGB display. Converting here keeps wide-gamut phone photos
            // from looking washed out once the encoder drops their colour profile.
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
    }

    /** WebP lossy has no useful alpha for a photo frame; match the reference and use white. */
    private fun flattenOntoWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return opaque
    }

    fun displayName(uri: Uri): String =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Photo"

    /** EXIF DateTimeOriginal, else MediaStore's DATE_TAKEN, else now. */
    private fun capturedAt(uri: Uri): Long =
        exifCapturedAt(uri) ?: mediaStoreDateTaken(uri) ?: System.currentTimeMillis()

    private fun exifCapturedAt(uri: Uri): Long? = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            parseExifDate(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            )
        }
    }.getOrNull()

    private fun mediaStoreDateTaken(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it > 0 } else null
        }
    }.getOrNull()
}

private val EXIF_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

/**
 * EXIF stores local wall-clock time, with the UTC offset in a separate tag that older cameras
 * omit. Without an offset the phone's own zone is the best guess.
 */
internal fun parseExifDate(value: String?, offset: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        val local = LocalDateTime.parse(value.trim(), EXIF_DATE)
        val withOffset = offset?.trim()?.takeIf { it.matches(Regex("[+-]\\d{2}:\\d{2}")) }
        if (withOffset != null) {
            OffsetDateTime.of(local, java.time.ZoneOffset.of(withOffset)).toInstant().toEpochMilli()
        } else {
            local.atZone(zone).toInstant().toEpochMilli()
        }.takeIf { it > 0 }
    } catch (_: DateTimeParseException) {
        null
    }
}
