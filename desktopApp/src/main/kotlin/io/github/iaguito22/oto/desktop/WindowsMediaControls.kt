package io.github.iaguito22.oto.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.EventQueue
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue

/** Lo que Windows enseña en sus controles multimedia (teclas, panel de volumen, pantalla de bloqueo). */
internal data class SystemMedia(
    val hasSong: Boolean,
    val playing: Boolean,
    val title: String,
    val artist: String,
    val artwork: String?,
)

@Composable
internal fun WindowsMediaTransport(media: SystemMedia, playback: TaskbarPlayback) {
    if (!isWindowsDesktop) return
    SideEffect { WindowsMediaControls.actions = playback }
    LaunchedEffect(media) { WindowsMediaControls.show(media) }
    DisposableEffect(Unit) { onDispose { WindowsMediaControls.show(SystemMedia(false, false, "", "", null)) } }
}

/**
 * Controles multimedia del sistema (SMTC) vía WinRT con JNA. Se cuelgan de una ventana nativa propia
 * que nunca se muestra, así funcionan igual en «Una ventana», en modular y con Oto en la bandeja.
 */
internal object WindowsMediaControls {
    @Volatile var actions: TaskbarPlayback? = null
    private val tasks = LinkedBlockingQueue<() -> Unit>()
    @Volatile private var threadId = 0
    private var controls: Pointer? = null
    private var updater: Pointer? = null
    private var shown: SystemMedia? = null
    private var unavailable = false

    // Hilo STA con su propia ventana y bucle de mensajes: Windows entrega por ahí los botones pulsados.
    private val thread by lazy {
        Thread(::loop, "oto-controles-windows").apply { isDaemon = true; start() }
    }

    fun show(media: SystemMedia) {
        tasks.add {
            if (!unavailable && media != shown) runCatching { apply(media) }.onFailure {
                unavailable = true
                ErrorLog.write("controles multimedia de Windows", it)
            }
        }
        thread
        threadId.takeIf { it != 0 }?.let { User32.INSTANCE.PostThreadMessage(it, WM_APP, WinDef.WPARAM(0), WinDef.LPARAM(0)) }
    }

    private fun loop() {
        val user = User32.INSTANCE
        val message = WinUser.MSG()
        user.PeekMessage(message, null, 0, 0, 0) // crea la cola de mensajes del hilo
        threadId = Kernel32.INSTANCE.GetCurrentThreadId()
        combase.RoInitialize(0) // RO_INIT_SINGLETHREADED
        while (true) {
            while (true) tasks.poll()?.invoke() ?: break
            if (user.GetMessage(message, null, 0, 0) <= 0) return
            if (message.message != WM_APP) {
                user.TranslateMessage(message)
                user.DispatchMessage(message)
            }
        }
    }

    private fun apply(media: SystemMedia) {
        val smtc = controls ?: open() ?: return
        val previous = shown
        shown = media
        if (!media.hasSong) {
            smtc.check(7, STATUS_CLOSED) // put_PlaybackStatus
            updater?.check(16) // ClearAll
            updater?.check(17) // Update
            smtc.check(11, 0) // put_IsEnabled
            return
        }
        if (previous?.hasSong != true) {
            smtc.check(11, 1) // put_IsEnabled
            smtc.check(13, 1) // put_IsPlayEnabled
            smtc.check(17, 1) // put_IsPauseEnabled
            smtc.check(25, 1) // put_IsPreviousEnabled
            smtc.check(27, 1) // put_IsNextEnabled
        }
        if (previous?.hasSong != true || previous.title != media.title || previous.artist != media.artist || previous.artwork != media.artwork) {
            val display = updater ?: return
            display.check(7, TYPE_MUSIC) // put_Type
            val music = PointerByReference()
            display.check(12, music) // get_MusicProperties
            try {
                withHString(media.title) { music.value.check(7, it) } // put_Title
                withHString(media.artist) { music.value.check(11, it) } // put_Artist
            } finally { music.value.release() }
            // Sin carátula Windows enseña el icono de la app; un fallo aquí no debe quitar los controles.
            runCatching { thumbnail(media.artwork) }.getOrNull().let { image ->
                display.check(11, image) // put_Thumbnail
                image?.release()
            }
            display.check(17) // Update
        }
        smtc.check(7, if (media.playing) STATUS_PLAYING else STATUS_PAUSED)
    }

    private fun open(): Pointer? {
        // Ventana nativa que nunca se muestra; así no depende de qué ventana de Oto esté abierta.
        // Con una clase predefinida («STATIC») Windows no llegaba a entregar los botones.
        val wc = WinUser.WNDCLASSEX().apply { lpfnWndProc = windowProc; lpszClassName = "OtoMediaControls"; hInstance = Kernel32.INSTANCE.GetModuleHandle(null) }
        if (User32.INSTANCE.RegisterClassEx(wc).toInt() == 0) error("RegisterClassEx = ${Native.getLastError()}")
        val window = User32.INSTANCE.CreateWindowEx(0, "OtoMediaControls", "Oto", 0, 0, 0, 0, 0, null, null, wc.hInstance, null)
            ?: error("CreateWindowEx = ${Native.getLastError()}")
        val hwnd = window.pointer
        val interop = activationFactory("Windows.Media.SystemMediaTransportControls", IID_INTEROP)
        val smtc = try {
            PointerByReference().also { interop.check(6, hwnd, IID_SMTC, it) }.value // GetForWindow (la interfaz hereda de IInspectable)
        } finally { interop.release() }
        updater = PointerByReference().also { smtc.check(8, it) }.value // get_DisplayUpdater
        smtc.check(32, buttonHandler, LongByReference()) // add_ButtonPressed
        controls = smtc
        return smtc
    }

    private val windowProc = object : WinUser.WindowProc {
        override fun callback(hwnd: WinDef.HWND, msg: Int, w: WinDef.WPARAM, l: WinDef.LPARAM): WinDef.LRESULT =
            User32.INSTANCE.DefWindowProc(hwnd, msg, w, l)
    }

    private fun thumbnail(url: String?): Pointer? {
        if (url.isNullOrBlank() || !url.startsWith("http")) return null
        val uris = activationFactory("Windows.Foundation.Uri", IID_URI_FACTORY)
        val uri = try {
            withHString(url) { text -> PointerByReference().also { uris.check(6, text, it) }.value } // CreateUri
        } finally { uris.release() }
        val streams = activationFactory("Windows.Storage.Streams.RandomAccessStreamReference", IID_STREAM_REFERENCE_STATICS)
        return try {
            PointerByReference().also { streams.check(7, uri, it) }.value // CreateFromUri
        } finally { streams.release(); uri.release() }
    }

    private fun pressed(button: Int) {
        val playback = actions ?: return
        if (!playback.hasSong) return
        when (button) {
            BUTTON_PLAY -> if (!playback.playing) playback.toggle()
            BUTTON_PAUSE, BUTTON_STOP -> if (playback.playing) playback.toggle()
            BUTTON_NEXT -> playback.next()
            BUTTON_PREVIOUS -> playback.previous()
        }
    }

    // Delegado COM hecho a mano: QueryInterface, AddRef, Release e Invoke. Vive lo que el proceso.
    private interface QueryInterface : StdCallLibrary.StdCallCallback { fun callback(self: Pointer, iid: Pointer, out: Pointer): Int }
    private interface Counter : StdCallLibrary.StdCallCallback { fun callback(self: Pointer): Int }
    private interface Invoke : StdCallLibrary.StdCallCallback { fun callback(self: Pointer, sender: Pointer?, args: Pointer?): Int }

    private val queryInterface = object : QueryInterface {
        override fun callback(self: Pointer, iid: Pointer, out: Pointer): Int {
            val asked = iid.getByteArray(0, 16)
            return if (handlerIids.any { it.contentEquals(asked) }) { out.setPointer(0, self); 0 }
            else { out.setPointer(0, Pointer.NULL); E_NOINTERFACE }
        }
    }
    private val counter = object : Counter { override fun callback(self: Pointer) = 1 }
    private val invoke = object : Invoke {
        override fun callback(self: Pointer, sender: Pointer?, args: Pointer?): Int {
            runCatching {
                val button = IntByReference()
                args?.check(6, button) // get_Button
                EventQueue.invokeLater { pressed(button.value) }
            }
            return 0
        }
    }
    private val handlerVtable = Memory(4L * Native.POINTER_SIZE).apply {
        listOf(queryInterface, counter, counter, invoke).forEachIndexed { i, callback ->
            setPointer(i.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(callback))
        }
    }
    private val buttonHandler = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, handlerVtable) }

    private val handlerIids = listOf(
        guidBytes(Guid.GUID("{00000000-0000-0000-C000-000000000046}")), // IUnknown
        guidBytes(Guid.GUID("{94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90}")), // IAgileObject
        guidBytes(BUTTON_PRESSED_HANDLER),
    )
}

/** IID de una interfaz genérica de WinRT (UUID v5 sobre la firma, como hace MIDL). */
internal fun parameterizedIid(signature: String): Guid.GUID {
    val namespace = "11f47ad57b7342c0abae878b1e16adee".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val hash = MessageDigest.getInstance("SHA-1").run { update(namespace); digest(signature.toByteArray(Charsets.UTF_8)) }
    hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
    hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = hash.take(16).joinToString("") { "%02x".format(it) }
    return Guid.GUID("{${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}}")
}

internal val BUTTON_PRESSED_HANDLER: Guid.GUID = parameterizedIid(
    "pinterface({9de1c534-6ae1-11e0-84e1-18a905bcc53f};" +
        "rc(Windows.Media.SystemMediaTransportControls;{99fa3ff4-1742-42a6-902e-087d41f965ec});" +
        "rc(Windows.Media.SystemMediaTransportControlsButtonPressedEventArgs;{b7f47116-a56f-4dc8-9e11-92031f4a87c2}))",
)

private fun guidBytes(guid: Guid.GUID): ByteArray { guid.write(); return guid.pointer.getByteArray(0, 16) }

private val IID_INTEROP = Guid.GUID("{DDB0472D-C911-4A1F-86D9-DC3D71A95F5A}")
private val IID_SMTC = Guid.GUID("{99FA3FF4-1742-42A6-902E-087D41F965EC}")
private val IID_URI_FACTORY = Guid.GUID("{44A9796F-723E-4FDF-A218-033E75B0C084}")
private val IID_STREAM_REFERENCE_STATICS = Guid.GUID("{857309DC-3FBF-4E7D-986F-EF3B1A07A964}")
private const val STATUS_CLOSED = 0
private const val STATUS_PLAYING = 3
private const val STATUS_PAUSED = 4
private const val TYPE_MUSIC = 1
private const val BUTTON_PLAY = 0
private const val BUTTON_PAUSE = 1
private const val BUTTON_STOP = 2
private const val BUTTON_NEXT = 6
private const val BUTTON_PREVIOUS = 7
private const val WM_APP = 0x8000
private const val E_NOINTERFACE = 0x80004002.toInt()

private interface Combase : StdCallLibrary {
    fun RoInitialize(type: Int): Int
    fun RoGetActivationFactory(classId: Pointer?, iid: Guid.GUID, factory: PointerByReference): Int
    fun WindowsCreateString(text: WString, length: Int, out: PointerByReference): Int
    fun WindowsDeleteString(text: Pointer?): Int
}
private val combase by lazy { Native.load("combase", Combase::class.java) }

private fun activationFactory(className: String, iid: Guid.GUID): Pointer = withHString(className) { name ->
    val out = PointerByReference()
    val result = combase.RoGetActivationFactory(name, iid, out)
    if (result < 0) error("RoGetActivationFactory($className) = 0x%08x".format(result))
    out.value
}

private fun <T> withHString(text: String, block: (Pointer?) -> T): T {
    val out = PointerByReference()
    val result = combase.WindowsCreateString(WString(text), text.length, out)
    if (result < 0) error("WindowsCreateString = 0x%08x".format(result))
    // Una cadena vacía es un HSTRING nulo, válido para WinRT.
    try { return block(out.value) } finally { combase.WindowsDeleteString(out.value) }
}

private fun Pointer.call(index: Int, vararg args: Any?): Int =
    Function.getFunction(getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        .invokeInt(arrayOf(this, *args))

private fun Pointer.check(index: Int, vararg args: Any?) {
    val result = call(index, *args)
    if (result < 0) error("WinRT método $index = 0x%08x".format(result))
}

private fun Pointer.release() { call(2) }
