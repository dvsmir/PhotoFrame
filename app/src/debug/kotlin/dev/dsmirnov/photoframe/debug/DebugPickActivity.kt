package dev.dsmirnov.photoframe.debug

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import dev.dsmirnov.photoframe.PhotoFrameApp
import dev.dsmirnov.photoframe.ui.MainActivity

/**
 * Debug-only stand-in for the photo picker: picks every image in one MediaStore folder and
 * opens Review & Send with them, exactly as a real pick would.
 *
 * ```
 * adb shell pm grant dev.dsmirnov.photoframe.debug android.permission.READ_MEDIA_IMAGES
 * adb shell am start -n dev.dsmirnov.photoframe.debug/dev.dsmirnov.photoframe.debug.DebugPickActivity \
 *     --es dir Pictures/PhotoFrameBatch/
 * ```
 */
class DebugPickActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = intent.getStringExtra("dir").orEmpty().let { if (it.endsWith("/")) it else "$it/" }
        val uris = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(dir),
            "${MediaStore.Images.Media.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)))
                }
            }
        }.orEmpty()

        val container = (application as PhotoFrameApp).container
        container.eventLog.info("debug", "picked ${uris.size} photo(s) from $dir")
        if (uris.isNotEmpty()) {
            container.pendingPicks = uris
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_REVIEW_PENDING)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }
}
