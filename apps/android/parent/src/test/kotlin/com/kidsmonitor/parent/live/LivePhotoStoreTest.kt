package com.kidsmonitor.parent.live

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LivePhotoStoreTest {
    private lateinit var gallery: Gallery
    private lateinit var store: LivePhotoStore
    @Before fun setup() {
        gallery = Gallery()
        gallery.attachInfo(ApplicationProvider.getApplicationContext<Context>(), android.content.pm.ProviderInfo().apply { authority = "media"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", gallery)
        store = LivePhotoStore(ApplicationProvider.getApplicationContext<Context>().contentResolver)
    }
    @Test fun `rotates the monitor picture and publishes a complete JPEG in the camera roll`() {
        val frame = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        store.save(frame, 90f, 1234)
        assertEquals("DCIM/KidsMonitor", gallery.inserted!!.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals(1, gallery.inserted!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals("image/jpeg", gallery.inserted!!.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals(0, gallery.published!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        val jpeg = gallery.file.readBytes()
        assertEquals(0xFF, jpeg[0].toInt() and 0xFF)
        assertEquals(0xD8, jpeg[1].toInt() and 0xFF)
        val image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(40, image.width); assertEquals(80, image.height)
        assertTrue(android.graphics.Color.red(image.getPixel(10, 10)) > 240)
        assertFalse(frame.isRecycled)
        assertFalse(gallery.deleted)
    }
    @Test fun `removes a pending row if writing fails`() {
        gallery.failOpen = true
        try { store.save(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), 0f); fail("expected save failure") } catch (_: Exception) { }
        assertTrue(gallery.deleted)
        assertNull(gallery.published)
    }
    @Test fun `removes a written but unpublished image if publishing fails`() {
        gallery.failPublish = true
        try { store.save(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), 0f); fail("expected save failure") } catch (_: IllegalStateException) { }
        assertTrue(gallery.deleted)
    }
    class Gallery : ContentProvider() {
        val file = File.createTempFile("live-photo-", ".jpg").apply { deleteOnExit() }
        var inserted: ContentValues? = null
        var published: ContentValues? = null
        var deleted = false
        var failOpen = false
        var failPublish = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted = ContentValues(values)
            return Uri.parse("content://media/external_primary/images/media/1")
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
            if (failOpen) null else ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            assertTrue("JPEG must be written before publication", file.length() > 0)
            if (failPublish) return 0
            published = ContentValues(values)
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deleted = true; return 1 }
        override fun getType(uri: Uri) = "image/jpeg"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }
}
