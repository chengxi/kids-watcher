package com.kidsmonitor.parent.live

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.util.UUID

/** Explicit snapshots only. Publish to the phone's gallery after the entire JPEG is written. */
class LivePhotoStore(private val resolver: ContentResolver) {
    fun save(frame: Bitmap, rotationDegrees: Float, nowMs: Long = System.currentTimeMillis()): Uri {
        val rotated = if (rotationDegrees % 360f == 0f) frame else Bitmap.createBitmap(
            frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotationDegrees) }, true,
        )
        var inserted: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "KidsMonitor_${nowMs}_${UUID.randomUUID()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/KidsMonitor")
                put(MediaStore.Images.Media.DATE_TAKEN, nowMs)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)) { "Gallery unavailable" }
            inserted = uri
            checkNotNull(resolver.openOutputStream(uri, "w")) { "Cannot open gallery photo" }.use {
                check(rotated.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "Cannot encode photo" }
            }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) { "Cannot publish photo" }
            return uri
        } catch (error: Exception) {
            inserted?.let { runCatching { resolver.delete(it, null, null) } }
            throw error
        } finally {
            if (rotated !== frame) rotated.recycle()
        }
    }
}
