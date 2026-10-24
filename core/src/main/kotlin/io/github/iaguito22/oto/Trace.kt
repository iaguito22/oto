package io.github.iaguito22.oto

object TraceHooks {
    @Volatile
    var begin: ((String) -> Unit)? = null
    @Volatile
    var end: (() -> Unit)? = null
}

/** Una sección con nombre en las trazas de perfetto, para medir en el móvil. Sin trazar no cuesta nada. */
inline fun <T> traced(name: String, block: () -> T): T {
    TraceHooks.begin?.invoke(name)
    try {
        return block()
    } finally {
        TraceHooks.end?.invoke()
    }
}
