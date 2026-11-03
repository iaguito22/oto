package io.github.iaguito22.oto.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.iaguito22.oto.BuildConfig
import io.github.iaguito22.oto.playback.withPlaybackUri
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File

/** Conserva la cola durante una actualización, solo desde la instalación de pruebas aislada. */
internal suspend fun usualSession(context: Context, restore: Boolean) {
    check(BuildConfig.APPLICATION_ID.endsWith(".linktest"))
    val service=context.packageManager.queryIntentServices(Intent("androidx.media3.session.MediaLibraryService").setPackage("io.github.iaguito22.oto.debug"),0).single().serviceInfo
    val token=SessionToken(context,ComponentName(service.packageName,service.name))
    val future=MediaController.Builder(context,token).buildAsync()
    val controller=suspendCancellableCoroutine<MediaController> { continuation ->
        future.addListener({
            try {
                val result=future.get()
                if(continuation.isActive) continuation.resumeWith(Result.success(result)) else result.release()
            } catch(e: Exception) { if(continuation.isActive) continuation.resumeWith(Result.failure(e)) }
        },ContextCompat.getMainExecutor(context))
    }
    try {
        val file=File(context.filesDir,"habitual-session.bin")
        val session: Bundle
        if(!restore) {
            check(controller.mediaItemCount>0) { "No hay cola que conservar" }
            session=Bundle().apply {
                putParcelableArrayList("items",ArrayList((0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).toBundle() }))
                putInt("index",controller.currentMediaItemIndex)
                putLong("position",controller.currentPosition)
                putBoolean("playing",controller.playWhenReady)
                putBoolean("shuffle",controller.shuffleModeEnabled)
                putInt("repeat",controller.repeatMode)
            }
            val parcel=Parcel.obtain()
            try { parcel.writeBundle(session);file.writeBytes(parcel.marshall()) } finally { parcel.recycle() }
        } else {
            val parcel=Parcel.obtain()
            try {
                val bytes=file.readBytes();parcel.unmarshall(bytes,0,bytes.size);parcel.setDataPosition(0)
                session=parcel.readBundle(Bundle::class.java.classLoader) ?: error("Copia de cola incompleta")
            } finally { parcel.recycle() }
            @Suppress("DEPRECATION")
            val items=session.getParcelableArrayList<Bundle>("items").orEmpty().map { MediaItem.fromBundle(it).withPlaybackUri() }
            check(items.isNotEmpty())
            controller.setMediaItems(items,session.getInt("index"),session.getLong("position"))
            controller.shuffleModeEnabled=session.getBoolean("shuffle")
            controller.repeatMode=session.getInt("repeat")
            controller.prepare()
            controller.playWhenReady=session.getBoolean("playing")
        }
        @Suppress("DEPRECATION")
        val count=session.getParcelableArrayList<Bundle>("items")!!.size
        File(context.filesDir,"habitual-session-result.json").writeText(JSONObject()
            .put("restored",restore).put("count",count).put("index",session.getInt("index"))
            .put("position",session.getLong("position")).put("playing",session.getBoolean("playing")).toString())
    } finally { controller.release() }
}
