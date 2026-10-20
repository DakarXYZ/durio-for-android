package chat.stoat.fork.attach

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * The newest images on the device for the attachment panel (MediaStore). With partial access
 * (Android 14, "selected photos") only the ones the user allowed come back.
 */
object RecentImages {
    /** What to ask for: images on Android 13+ (and the partial grant on 14+), storage below. */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Whether some images may be read (all, or the selected ones on Android 14+). */
    fun canRead(context: Context): Boolean = permissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Up to [limit] content URIs of the newest images; empty without permission or on errors. */
    fun query(context: Context, limit: Int = 5): List<Uri> {
        if (!canRead(context)) return emptyList()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val newestFirst = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        return try {
            val cursor = if (Build.VERSION.SDK_INT >= 30) {
                val args = Bundle().apply {
                    putStringArray(
                        android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                        arrayOf(MediaStore.Images.Media.DATE_ADDED),
                    )
                    putInt(
                        android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                        android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
                    )
                    putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                }
                context.contentResolver.query(collection, projection, args, null)
            } else {
                context.contentResolver.query(collection, projection, null, null, newestFirst)
            }

            cursor?.use {
                val id = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                buildList {
                    while (size < limit && it.moveToNext()) {
                        add(ContentUris.withAppendedId(collection, it.getLong(id)))
                    }
                }
            }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
