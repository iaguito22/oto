package io.github.iaguito22.oto.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Los iconos vectoriales del móvil (`app/src/main/res/drawable`), leídos tal cual desde los XML copiados a los
 * recursos del escritorio. Compose Desktop no entiende los `<vector>` de Android, pero `PathParser` sí entiende su
 * `pathData`, así que se reconstruye el `ImageVector` al vuelo y se cachea.
 *
 * El relleno del XML es blanco; el color final lo pone siempre el `tint` del `Icon`.
 */
object MobileIcons {
    private val cache = HashMap<String, ImageVector>()

    val accountCircle: ImageVector get() = icon("ic_account_circle")
    val add: ImageVector get() = icon("ic_add")
    val arrowBack: ImageVector get() = icon("ic_arrow_back")
    val check: ImageVector get() = icon("ic_check")
    val chevronRight: ImageVector get() = icon("ic_chevron_right")
    val close: ImageVector get() = icon("ic_close")
    val createNewFolder: ImageVector get() = icon("ic_create_new_folder")
    val dataSaver: ImageVector get() = icon("ic_data_saver_on")
    val delete: ImageVector get() = icon("ic_delete")
    val download: ImageVector get() = icon("ic_download")
    val downloadDone: ImageVector get() = icon("ic_download_done")
    val downloadOffline: ImageVector get() = icon("ic_download_for_offline")
    val edit: ImageVector get() = icon("ic_edit")
    val favorite: ImageVector get() = icon("ic_favorite")
    val favoriteOutline: ImageVector get() = icon("ic_favorite_outline")
    val folder: ImageVector get() = icon("ic_folder")
    val graphicEq: ImageVector get() = icon("ic_graphic_eq")
    val hardDrive: ImageVector get() = icon("ic_hard_drive")
    val headphones: ImageVector get() = icon("ic_headphones")
    val highQuality: ImageVector get() = icon("ic_high_quality")
    val history: ImageVector get() = icon("ic_history")
    val keep: ImageVector get() = icon("ic_keep")
    val libraryMusic: ImageVector get() = icon("ic_library_music")
    val lyrics: ImageVector get() = icon("ic_lyrics")
    val moreHoriz: ImageVector get() = icon("ic_more_horiz")
    val pause: ImageVector get() = icon("ic_pause")
    val person: ImageVector get() = icon("ic_person")
    val play: ImageVector get() = icon("ic_play_arrow")
    val playlistAdd: ImageVector get() = icon("ic_playlist_add")
    val queueMusic: ImageVector get() = icon("ic_queue_music")
    val refresh: ImageVector get() = icon("ic_refresh")
    val repeat: ImageVector get() = icon("ic_repeat")
    val ring: ImageVector get() = icon("ic_ring")
    val search: ImageVector get() = icon("ic_search")
    val settings: ImageVector get() = icon("ic_settings")
    val share: ImageVector get() = icon("ic_share")
    val shuffle: ImageVector get() = icon("ic_shuffle")
    val skipNext: ImageVector get() = icon("ic_skip_next")
    val skipPrevious: ImageVector get() = icon("ic_skip_previous")
    val smartphone: ImageVector get() = icon("ic_smartphone")
    val speaker: ImageVector get() = icon("ic_speaker")
    val swapHoriz: ImageVector get() = icon("ic_swap_horiz")
    val swapVert: ImageVector get() = icon("ic_swap_vert")
    val swipeUp: ImageVector get() = icon("ic_swipe_up")
    val timer: ImageVector get() = icon("ic_timer")
    val transitionFade: ImageVector get() = icon("ic_transition_fade")
    val translate: ImageVector get() = icon("ic_translate")

    @Synchronized
    fun icon(name: String): ImageVector = cache.getOrPut(name) {
        parse(name) ?: error("No se pudo leer el icono $name")
    }

    private fun parse(name: String): ImageVector? {
        val stream: InputStream = MobileIcons::class.java.getResourceAsStream("/drawable/$name.xml") ?: return null
        return stream.use {
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(it)
            val root = doc.documentElement
            val width = root.attr("width")?.removeSuffix("dp")?.toFloatOrNull() ?: 24f
            val height = root.attr("height")?.removeSuffix("dp")?.toFloatOrNull() ?: 24f
            val viewportW = root.attr("viewportWidth")?.toFloatOrNull() ?: 24f
            val viewportH = root.attr("viewportHeight")?.toFloatOrNull() ?: 24f
            val builder = ImageVector.Builder(
                name = name,
                defaultWidth = width.dp,
                defaultHeight = height.dp,
                viewportWidth = viewportW,
                viewportHeight = viewportH,
            )
            root.childElements().forEach { builder.addElement(it) }
            builder.build()
        }
    }

    private fun ImageVector.Builder.addElement(element: Element) {
        when (element.tagName) {
            "path" -> {
                val data = element.attr("pathData") ?: return
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    pathFillType = when (element.attr("fillType")) {
                        "evenOdd" -> PathFillType.EvenOdd
                        else -> PathFillType.NonZero
                    },
                    fill = SolidColor(element.attr("fillColor").toColor() ?: Color.White),
                    fillAlpha = element.attr("fillAlpha")?.toFloatOrNull() ?: 1f,
                    stroke = element.attr("strokeColor").toColor()?.let { SolidColor(it) },
                    strokeAlpha = element.attr("strokeAlpha")?.toFloatOrNull() ?: 1f,
                    strokeLineWidth = element.attr("strokeWidth")?.toFloatOrNull() ?: 0f,
                )
            }
            "group" -> {
                addGroup(
                    name = element.attr("name") ?: "",
                    rotate = element.attr("rotation")?.toFloatOrNull() ?: 0f,
                    pivotX = element.attr("pivotX")?.toFloatOrNull() ?: 0f,
                    pivotY = element.attr("pivotY")?.toFloatOrNull() ?: 0f,
                    scaleX = element.attr("scaleX")?.toFloatOrNull() ?: 1f,
                    scaleY = element.attr("scaleY")?.toFloatOrNull() ?: 1f,
                    translationX = element.attr("translateX")?.toFloatOrNull() ?: 0f,
                    translationY = element.attr("translateY")?.toFloatOrNull() ?: 0f,
                )
                element.childElements().forEach { addElement(it) }
                clearGroup()
            }
        }
    }

    private fun Element.childElements(): List<Element> {
        val children = childNodes
        return (0 until children.length).mapNotNull { children.item(it) as? Element }
    }

    private fun Element.attr(name: String): String? =
        getAttributeNS("http://schemas.android.com/apk/res/android", name).takeIf { it.isNotBlank() }
            ?: getAttribute(name).takeIf { it.isNotBlank() }

    private fun String?.toColor(): Color? {
        val raw = this?.trim()?.removePrefix("#") ?: return null
        val value = raw.toLongOrNull(16) ?: return null
        return when (raw.length) {
            8 -> Color(value)
            6 -> Color(0xFF000000L or value)
            else -> null
        }
    }
}
