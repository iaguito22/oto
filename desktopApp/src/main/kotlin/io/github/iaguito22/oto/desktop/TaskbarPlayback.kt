package io.github.iaguito22.oto.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HICON
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.delay
import java.awt.BasicStroke
import java.awt.Color
import java.awt.EventQueue
import java.awt.RenderingHints
import java.awt.Window
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

internal data class TaskbarPlayback(
    val hasSong: Boolean,
    val playing: Boolean,
    val previous: () -> Unit,
    val toggle: () -> Unit,
    val next: () -> Unit,
)

@Composable
internal fun TaskbarTransport(window: Window, visible: Boolean, playback: TaskbarPlayback) {
    if (!System.getProperty("os.name").startsWith("Windows")) return
    val latest = rememberUpdatedState(playback)
    val toolbar = remember(window) { PlaybackThumbnail(window) { latest.value } }
    DisposableEffect(toolbar) { onDispose { toolbar.close() } }
    LaunchedEffect(toolbar, visible, playback.hasSong, playback.playing) {
        toolbar.visible = visible
        if (visible) {
            // Compose puede llegar después de TaskbarButtonCreated, o antes de que Explorer termine.
            repeat(10) {
                if (toolbar.refresh()) return@LaunchedEffect
                delay(150)
            }
        } else toolbar.refresh()
    }
}

private const val WM_COMMAND = 0x0111
private const val THBN_CLICKED = 0x1800
internal const val TASKBAR_PREVIOUS = 0x7101
internal const val TASKBAR_TOGGLE = 0x7102
internal const val TASKBAR_NEXT = 0x7103
private val taskbarButtonCreated by lazy { User32.INSTANCE.RegisterWindowMessage("TaskbarButtonCreated") }
private val thumbnails = ConcurrentHashMap<Pointer, PlaybackThumbnail>()

internal fun dispatchTaskbarThumbnailMessage(hwnd: Pointer, message: Int, command: Int): Boolean {
    val thumbnail = thumbnails[hwnd] ?: return false
    if (taskbarButtonCreated != 0 && message == taskbarButtonCreated) {
        EventQueue.invokeLater { thumbnail.recreated() }
        return false
    }
    val id = command and 0xffff
    if (message != WM_COMMAND || (command ushr 16) != THBN_CLICKED || id !in TASKBAR_PREVIOUS..TASKBAR_NEXT) return false
    EventQueue.invokeLater { thumbnail.click(id) }
    return true
}

internal fun forgetTaskbarThumbnail(hwnd: Pointer) { thumbnails.remove(hwnd) }

internal class PlaybackThumbnail(private val window: Window, private val playback: () -> TaskbarPlayback) : AutoCloseable {
    private var target: Pointer? = null
    private var installed = false
    private var images: Pointer? = null
    var visible = true

    fun refresh(): Boolean {
        if (!window.isShowing || (!visible && target == null)) return false
        return runCatching {
            val hwnd = Native.getWindowPointer(window)
            if (hwnd != target) {
                if (!installTaskbarMessageHook(window)) return false
                target?.let { thumbnails.remove(it, this) }
                target = hwnd
                installed = false
                thumbnails[hwnd] = this
            }
            val state = playback()
            val flags = if (!visible) 0x8 else if (!state.hasSong) 0x1 else 0
            val imageList = images ?: createThumbnailImages()?.also { images = it } ?: return false
            installed = thumbButtons(hwnd, imageList, state.playing, flags, installed)
            installed
        }.getOrDefault(false)
    }

    fun recreated() {
        if (target == null) return
        installed = false
        if (visible) refresh()
    }

    fun click(id: Int) {
        if (target == null || !visible) return
        val state = playback()
        if (!state.hasSong) return
        when (id) {
            TASKBAR_PREVIOUS -> state.previous()
            TASKBAR_TOGGLE -> state.toggle()
            TASKBAR_NEXT -> state.next()
        }
    }

    override fun close() {
        visible = false
        refresh()
        target?.let { thumbnails.remove(it, this) }
        target = null
        installed = false
        images?.let(imageLists::ImageList_Destroy)
        images = null
    }
}

@Structure.FieldOrder("dwMask", "iId", "iBitmap", "hIcon", "szTip", "dwFlags")
internal class ThumbnailButton : Structure() {
    @JvmField var dwMask = 0
    @JvmField var iId = 0
    @JvmField var iBitmap = 0
    @JvmField var hIcon: HICON? = null
    @JvmField var szTip = ShortArray(260) // WCHAR[260], UTF-16
    @JvmField var dwFlags = 0
}

internal fun thumbnailButtons(playing: Boolean, flags: Int): Array<Structure> {
    val glyphs = listOf(ThumbnailGlyph.Previous, if (playing) ThumbnailGlyph.Pause else ThumbnailGlyph.Play, ThumbnailGlyph.Next)
    return ThumbnailButton().toArray(glyphs.size).also { buttons ->
        buttons.forEachIndexed { index, entry ->
            val button = entry as ThumbnailButton
            val glyph = glyphs[index]
            // JNA lee los elementos posteriores desde memoria inicializada a cero.
            button.dwMask = 0x1 or 0x4 or 0x8 // THB_BITMAP | THB_TOOLTIP | THB_FLAGS
            button.iId = TASKBAR_PREVIOUS + index
            button.iBitmap = glyph.ordinal
            glyph.label.forEachIndexed { i, c -> button.szTip[i] = c.code.toShort() }
            button.dwFlags = flags
            button.write()
        }
    }
}

private fun thumbButtons(hwnd: Pointer, images: Pointer, playing: Boolean, flags: Int, installed: Boolean): Boolean {
    val ole = Ole32.INSTANCE
    val init = ole.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).toInt()
    try {
        val result = PointerByReference()
        val clsid = Guid.GUID("{56FDF344-FD6D-11d0-958A-006097C9A090}")
        val iid = Guid.GUID("{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}") // ITaskbarList3
        if (ole.CoCreateInstance(clsid, null, 1, iid, result).toInt() < 0) return false
        val taskbar = result.value
        val vtable = taskbar.getPointer(0)
        fun call(index: Int, vararg args: Any?) =
            Function.getFunction(vtable.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
                .invokeInt(arrayOf(taskbar, *args))
        try {
            if (call(3) < 0) return false // HrInit
            if (!installed && call(17, hwnd, images) < 0) return false // ThumbBarSetImageList
            val buttons = thumbnailButtons(playing, flags)
            val ptr = buttons.first().pointer
            val count = buttons.size
            // Una barra existente solo se puede actualizar. Explorer puede recrearla tras ocultar Oto.
            return if (installed) call(16, hwnd, count, ptr) >= 0 || (call(17, hwnd, images) >= 0 && call(15, hwnd, count, ptr) >= 0)
            else call(15, hwnd, count, ptr) >= 0 || call(16, hwnd, count, ptr) >= 0
        } finally { call(2) }
    } finally {
        if (init >= 0) ole.CoUninitialize()
    }
}

private interface ThumbnailImageLists : StdCallLibrary {
    fun ImageList_Create(width: Int, height: Int, flags: Int, initial: Int, grow: Int): Pointer?
    fun ImageList_ReplaceIcon(list: Pointer, index: Int, icon: HICON): Int
    fun ImageList_Destroy(list: Pointer): Boolean
}
private val imageLists by lazy { Native.load("comctl32", ThumbnailImageLists::class.java) }

private fun createThumbnailImages(): Pointer? {
    val size = User32.INSTANCE.GetSystemMetrics(11).coerceAtLeast(16) // SM_CXICON
    val list = imageLists.ImageList_Create(size, size, 0x20 or 0x1, ThumbnailGlyph.entries.size, 0) ?: return null
    var complete = false
    try {
        for (glyph in ThumbnailGlyph.entries) {
            val icon = glyph.icon(size) ?: return null
            val index = try { imageLists.ImageList_ReplaceIcon(list, -1, icon) }
                finally { User32.INSTANCE.DestroyIcon(icon) }
            if (index != glyph.ordinal) return null
        }
        complete = true
        return list
    } finally {
        if (!complete) imageLists.ImageList_Destroy(list)
    }
}

private interface ThumbnailIcons : StdCallLibrary {
    fun CreateIconFromResourceEx(bits: ByteArray, size: Int, icon: Boolean, version: Int, width: Int, height: Int, flags: Int): HICON?
}
private val iconApi by lazy { Native.load("user32", ThumbnailIcons::class.java) }

private enum class ThumbnailGlyph(val label: String) {
    Previous("Anterior"), Play("Reproducir"), Pause("Pausar"), Next("Siguiente");

    fun icon(size: Int): HICON? {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.scale(size / 32.0, size / 32.0)
            if (this == Previous) { g.translate(32, 0); g.scale(-1.0, 1.0) }
            val shapes = when (this) {
                Pause -> listOf(Rectangle2D.Double(8.0, 7.0, 5.0, 18.0), Rectangle2D.Double(19.0, 7.0, 5.0, 18.0))
                Play -> listOf(triangle(9.0, 25.0))
                Previous, Next -> listOf(triangle(6.0, 21.0), Rectangle2D.Double(22.0, 7.0, 4.0, 18.0))
            }
            for (shape in shapes) {
                g.color = Color(0x35, 0x35, 0x35)
                g.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(shape)
                g.color = Color(0xf4, 0xf4, 0xf4)
                g.fill(shape)
            }
        } finally { g.dispose() }

        val header = 40
        val stride = size * 4
        // Recurso RT_ICON: cabecera DIB, píxeles BGRA y máscara AND alineada a 32 bits.
        val maskStride = ((size + 31) / 32) * 4
        val bits = ByteArray(header + stride * size + size * maskStride)
        val dib = ByteBuffer.wrap(bits).order(ByteOrder.LITTLE_ENDIAN)
        dib.putInt(0, header)
        dib.putInt(4, size)
        dib.putInt(8, size * 2)
        dib.putShort(12, 1)
        dib.putShort(14, 32)
        val maskStart = header + stride * size
        for (y in 0 until size) for (x in 0 until size) {
            val argb = image.getRGB(x, size - 1 - y)
            dib.putInt(header + y * stride + x * 4, argb)
            if (argb ushr 24 == 0) {
                val index = maskStart + y * maskStride + x / 8
                bits[index] = (bits[index].toInt() or (0x80 shr (x % 8))).toByte()
            }
        }
        return iconApi.CreateIconFromResourceEx(bits, bits.size, true, 0x00030000, size, size, 0)
    }

    private fun triangle(left: Double, right: Double) = Path2D.Double().apply {
        moveTo(left, 6.0)
        lineTo(right, 16.0)
        lineTo(left, 26.0)
        closePath()
    }
}
