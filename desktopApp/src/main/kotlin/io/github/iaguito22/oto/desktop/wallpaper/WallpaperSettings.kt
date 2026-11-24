package io.github.iaguito22.oto.desktop.wallpaper

import io.github.iaguito22.oto.OtoDirs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Ajustes del fondo de escritorio vivo (Ajustes → Extras). Son los del prototipo del laboratorio
 * (`scratchpad/laboratorio-visual/fondo.html`) con los valores que eligió Iago. Se guardan en `fondo_vivo`.
 */
@Serializable
data class WallpaperSettings(
    // Monitor
    val monitor: String = "principal",            // principal · segundo · todos
    // Visualizador
    val visualizador: Boolean = true,             // sin él quedan la letra, la hora y los controles (Iago, 24/11)
    val estilo: String = "anillo",                // por defecto barras en anillo (Iago, 23/11) · malla · ncs · anillo · onda · pulso · eclipse · instrumentos · barras
    val resplandor: Float = .6f,
    val aro: Float = .85f,                        // intensidad del aro (estilo NCS)
    val estela: Float = .5f,
    val tam: Float = .11f,                        // radio del círculo, en parte del alto de la pantalla
    val pos: String = "mc",                       // fila (t m b) + columna (l c r)
    val offX: Float = 0f,
    val offY: Float = 0f,
    val barras: Int = 96,
    val largo: Float = 1f,
    val espejo: Boolean = true,
    val giro: Float = .3f,
    val suavizado: Float = .5f,
    // Carátula
    val dentro: String = "caratula",              // caratula · ambas · esfera
    val forma: String = "circulo",                // circulo · redondeada · cuadrada · vinilo
    val tamCaratula: Float = 1f,
    val borde: Float = .4f,
    val latido: Float = 1f,
    val transicion: String = "rebote",            // rebote · giro · fundido
    // Colores
    val colores: String = "caratula",             // caratula · mezcla · fijo · musica
    val color1: String = "#8f6bff",
    val color2: String = "#ff4fa3",
    val saturacion: Float = .9f,
    val brillo: Float = 1f,
    val arcoiris: Boolean = false,
    // Reacción
    val sens: Float = 1f,
    val kick: Float = 1f,
    val snare: Float = 1f,
    val hat: Float = 1f,
    val bass: Float = 1f,
    val voice: Float = 1f,
    val lenta: Float = .8f,
    val temblor: Float = .5f,
    val destellos: Boolean = true,
    val anillosBombo: Boolean = true,
    val laseres: Boolean = false,
    val desfase: Float = 0f,                      // ms
    // Partículas
    val particulas: Boolean = false,              // apagadas por defecto (Iago, 23/11)
    val tipoPart: String = "estrellas",           // chispas · estrellas · polvo
    val cantidad: Float = .45f,
    val velPart: Float = 1f,
    val tamPart: Float = 1f,
    // Letra
    val letra: Boolean = true,
    val letraPos: String = "auto",                // auto · izquierda · derecha · arriba · abajo
    val tamLetra: Float = 1f,
    val estiloLetra: String = "auto",             // auto · editorial · electronica · rock · hiphop · indie · sobria
    val respetarIconos: Boolean = true,
    val verZonas: Boolean = false,
    // Reloj (apagado por defecto)
    val reloj: Boolean = false,
    val relojPos: String = "sobre",               // sobre · bajo · izquierda · derecha (del círculo) · centro (de la pantalla) · ai · ad · bi · bd (esquinas)
    val relojEstilo: String = "dia",              // dia (el día en grande, como los widgets de Android; Iago, 24/11) · fino · grueso · digital · contorno
    val relojFuente: String = "estilo",           // estilo (la suya: «día» → Anurati o Michroma) · anurati · blanka · elianto · ailerons · michroma · oto
    val relojTam: Float = 1f,
    val hora24: Boolean = true,
    val relojIdioma: String = "es",               // es · en (idioma de la fecha y de a. m./p. m.; Iago, 24/11)
    val segundos: Boolean = false,
    val fecha: Boolean = true,
    val relojColor: String = "blanco",            // blanco · caratula
    val relojLatido: Boolean = false,
    val relojOpacidad: Float = .9f,
    // Controles de música en el escritorio (apagados por defecto)
    val controles: Boolean = false,
    val ctlPos: String = "bajo",                  // como relojPos
    val ctlEstilo: String = "compacta",           // compacta (tarjeta pequeña; Iago, 24/11) · pildora · tarjeta · botones · vinilo (esquina de abajo a la izquierda)
    val ctlFuente: String = "oto",                // oto (Rubik) · anurati · blanka · elianto · ailerons · michroma
    val ctlTam: Float = 1f,
    val ctlCaratula: Boolean = true,
    val ctlTitulo: Boolean = true,
    val ctlProgreso: Boolean = true,
    val ctlTiempos: Boolean = true,
    val ctlMeGusta: Boolean = true,
    val ctlAleatorio: Boolean = false,
    val ctlRepetir: Boolean = false,
    val ctlVolumen: Boolean = false,
    val ctlFondo: Float = .5f,                    // opacidad del cristal de detrás
    val ctlOpacidad: Float = 1f,
    val ctlAlPasar: Boolean = false,              // solo se ven al acercar el ratón
    val ctlColor: String = "caratula",            // caratula · blanco
    val ctlCola: Boolean = true,                  // vinilo: las siguientes de la cola
    val ctlColaN: Int = 3,
    val ctlGiro: Boolean = true,                  // vinilo: el disco gira mientras suena
    // Fondo
    val fondo: String = "actual",                // actual · caratula · degradado · color · negro
    val colorFondo: String = "#120f1c",
    val desenfoque: Float = .6f,
    val oscurecer: Float = 0f,
    val sombra: Float = .5f,
    val movimiento: Boolean = true,
    val vineta: Float = 0f,
    // Rendimiento
    val opacidad: Float = 1f,
    val fps: Int = 120,                           // 24 · 30 · 60 · 120 · 0 (sin límite). Por defecto 120 (Iago, 23/11); nunca pasa de lo que da la pantalla
    val calidad: Float = 1f,
    val ahorro: Boolean = true,
    val pausaCompleta: Boolean = true,
) {
    companion object {
        /** Estilos rápidos, como en el prototipo. */
        val presets: Map<String, (WallpaperSettings) -> WallpaperSettings> = linkedMapOf(
            "Por defecto" to { _ -> WallpaperSettings() },
            "Club" to { _ -> WallpaperSettings(estilo = "malla", tam = .1f, particulas = true, tipoPart = "estrellas", cantidad = 1f, laseres = true,
                temblor = .9f, destellos = true, colores = "mezcla", estiloLetra = "electronica", oscurecer = .35f, sombra = .7f) },
            "Tranquilo" to { _ -> WallpaperSettings(estilo = "onda", tam = .1f, tipoPart = "polvo", cantidad = .4f, velPart = .6f, temblor = 0f,
                destellos = false, laseres = false, estiloLetra = "sobria", fps = 30, giro = .15f) },
            "Mínimo" to { _ -> WallpaperSettings(estilo = "anillo", tam = .08f, particulas = false, anillosBombo = false, destellos = false,
                borde = .2f, estiloLetra = "sobria", fps = 30, sombra = .3f) },
        )
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
        internal fun decode(text: String?): WallpaperSettings =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: WallpaperSettings()
        internal fun encode(s: WallpaperSettings): String = json.encodeToString(serializer(), s)
    }
}

/** Lo guardado con una versión anterior ([from]) puesto al día: v2 partículas apagadas y barras en anillo; v3 120 fps. */
internal fun migrateSettings(s: WallpaperSettings, from: Int): WallpaperSettings {
    var out = s
    if (from < 2) out = out.copy(particulas = false, estilo = if (s.estilo == "malla") "anillo" else s.estilo)
    if (from < 3 && (out.fps == 30 || out.fps == 60)) out = out.copy(fps = 120)
    return out
}

/** Dónde vive lo guardado: activado (apagado por defecto) y los ajustes. */
internal object WallpaperStore {
    private val kv get() = OtoDirs.keyValue("fondo_vivo")
    private val _settings = MutableStateFlow(runCatching { migrate(WallpaperSettings.decode(kv.getString("ajustes", null))) }.getOrDefault(WallpaperSettings()))
    val settings: StateFlow<WallpaperSettings> = _settings.asStateFlow()
    private val _enabled = MutableStateFlow(runCatching { kv.getString("activo", null) == "1" }.getOrDefault(false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Una sola vez por versión: partículas apagadas y barras en anillo (v2); 120 fps si estaba en 30 o 60 (v3). */
    private fun migrate(s: WallpaperSettings): WallpaperSettings {
        val v = kv.getString("v", null)?.toIntOrNull() ?: 1
        if (v >= 3) return s
        val out = migrateSettings(s, v)
        runCatching { kv.putString("ajustes", WallpaperSettings.encode(out)); kv.putString("v", "3") }
        return out
    }

    fun update(change: (WallpaperSettings) -> WallpaperSettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        runCatching { kv.putString("ajustes", WallpaperSettings.encode(next)) }.onFailure { Timber.w(it, "No se pudieron guardar los ajustes del fondo vivo") }
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        runCatching { kv.putString("activo", if (on) "1" else null) }
    }
}
