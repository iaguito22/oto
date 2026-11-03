package io.github.iaguito22.oto.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

/** Android Auto necesita una URI local que pueda abrir desde su propio proceso. */
class CarArtworkProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Solo lectura")
        val url = images[uri.lastPathSegment] ?: throw FileNotFoundException("Carátula desconocida")
        val ctx = context ?: throw FileNotFoundException("Sin contexto")
        val image = runBlocking {
            val request = ImageRequest.Builder(ctx).data(url.resize(320)).size(320).allowHardware(false).build()
            ctx.imageLoader.execute(request).image?.toBitmap()
        } ?: throw FileNotFoundException("Carátula no disponible")
        // Un archivo temporal independiente evita que dos peticiones simultáneas se sobrescriban.
        val file = File.createTempFile("oto-car-art-", ".png", ctx.cacheDir)
        try {
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            file.delete()
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private val images = ConcurrentHashMap<String, String>()

        fun uri(context: Context, url: String): Uri {
            val id = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            images[id] = url
            return Uri.Builder().scheme("content").authority("${context.packageName}.car-artwork").appendPath(id).build()
        }
    }
}
