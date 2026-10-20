package io.github.iaguito22.oto.ui.menu

import android.content.Context
import android.content.Intent
import com.metrolist.innertube.models.YTItem

/** El menú de compartir del sistema, con un enlace de YouTube Music: se abre en cualquier móvil, tenga Oto o no. */
private fun share(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun shareSong(context: Context, id: String, title: String, artist: String) =
    share(context, title, "$title · $artist\nhttps://music.youtube.com/watch?v=$id")

fun shareItem(context: Context, item: YTItem) = share(context, item.title, "${item.title}\n${item.shareLink}")
