package app.framealt.ui.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** What a share handed over: the photos worth preparing, and how many items came in total. */
data class SharedItems<T>(val photos: List<T>, val offered: Int) {
    val skipped: Int get() = offered - photos.size
}

/**
 * Keeps the images from a share. `UX.md` §5: non-image items are dropped silently, only
 * counted.
 *
 * An item whose type the sender did not say is kept, not dropped: some apps share photos
 * with no type, and a file that turns out not to be an image fails preparation with its own
 * per-photo message anyway. Dropping it here would lose real photos without a word.
 */
fun <T> classifyShared(items: List<Pair<T, String?>>): SharedItems<T> {
    val unique = items.distinctBy { it.first }
    val photos = unique.filter { (_, type) -> type == null || type.startsWith("image/", ignoreCase = true) }
    return SharedItems(photos.map { it.first }, unique.size)
}

/** Reads `ACTION_SEND` / `ACTION_SEND_MULTIPLE`. Anything else yields nothing. */
fun readShare(intent: Intent, resolver: ContentResolver): SharedItems<Uri> {
    val uris: List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }.ifEmpty {
        // Some senders put the items only in ClipData, which is also what carries the grant.
        val clip = intent.clipData ?: return@ifEmpty emptyList()
        (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    // The intent's own type is the common type of everything shared ("image/*", or "*/*"
    // for a mix), so it only helps when it is specific.
    val intentType = intent.type?.takeUnless { it.endsWith("/*") }
    return classifyShared(uris.map { it to (runCatching { resolver.getType(it) }.getOrNull() ?: intentType) })
}
