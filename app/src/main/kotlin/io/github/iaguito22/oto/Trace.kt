package io.github.iaguito22.oto

import android.os.Trace

/** Una sección con nombre en las trazas de perfetto, para medir en el móvil. Sin trazar no cuesta nada. */
inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
