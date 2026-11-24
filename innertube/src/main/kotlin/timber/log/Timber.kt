package timber.log

/**
 * Shim multiplataforma de Timber para que `:innertube`, `:core`, `:app` y `:desktopApp`
 * compilen en JVM puro y en Android con la misma API (`Timber.plant`, `Timber.Tree`, `Timber.DebugTree`).
 *
 * Como el Timber de verdad, `Timber.tag(x)` vale solo para la siguiente línea del mismo hilo.
 */
object Timber {
    abstract class Tree {
        protected open fun log(priority: Int, tag: String?, message: String, t: Throwable?) {}

        private fun emit(priority: Int, t: Throwable?, message: String, args: Array<out Any?>) =
            log(priority, takeTag(), format(message, args), t)

        open fun d(message: String, vararg args: Any?) = emit(3, null, message, args)
        open fun d(t: Throwable?, message: String = "", vararg args: Any?) = emit(3, t, message, args)
        open fun i(message: String, vararg args: Any?) = emit(4, null, message, args)
        open fun i(t: Throwable?, message: String = "", vararg args: Any?) = emit(4, t, message, args)
        open fun w(message: String, vararg args: Any?) = emit(5, null, message, args)
        open fun w(t: Throwable?, message: String = "", vararg args: Any?) = emit(5, t, message, args)
        open fun w(t: Throwable?) = log(5, takeTag(), t?.message.orEmpty(), t)
        open fun e(message: String, vararg args: Any?) = emit(6, null, message, args)
        open fun e(t: Throwable?, message: String = "", vararg args: Any?) = emit(6, t, message, args)
        open fun e(t: Throwable?) = log(6, takeTag(), t?.message.orEmpty(), t)

        private fun format(message: String, args: Array<out Any?>): String =
            if (args.isEmpty()) message else runCatching { message.format(*args) }.getOrDefault(message)
    }

    /** Consola: hora (la consola no la pone) y etiqueta. Avisos y errores a stderr. */
    open class DebugTree : Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val line = format(tag, message, java.time.LocalTime.now())
            if (priority >= 5) {
                System.err.println(line)
                t?.printStackTrace(System.err)
            } else {
                println(line)
            }
        }

        companion object {
            private val TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

            fun format(tag: String?, message: String, at: java.time.LocalTime): String =
                TIME.format(at) + " " + (if (tag != null) "[$tag] " else "") + message
        }
    }

    @Volatile
    var delegate: Tree = object : Tree() {}

    private val pendingTag = ThreadLocal<String?>()

    private fun takeTag(): String? = pendingTag.get()?.also { pendingTag.remove() }

    fun plant(tree: Tree) {
        delegate = tree
    }

    fun tag(tag: String): Tree {
        pendingTag.set(tag)
        return delegate
    }

    fun d(message: String, vararg args: Any?) = delegate.d(message, *args)
    fun d(t: Throwable?, message: String = "", vararg args: Any?) = delegate.d(t, message, *args)
    fun i(message: String, vararg args: Any?) = delegate.i(message, *args)
    fun i(t: Throwable?, message: String = "", vararg args: Any?) = delegate.i(t, message, *args)
    fun w(message: String, vararg args: Any?) = delegate.w(message, *args)
    fun w(t: Throwable?, message: String = "", vararg args: Any?) = delegate.w(t, message, *args)
    fun w(t: Throwable?) = delegate.w(t)
    fun e(message: String, vararg args: Any?) = delegate.e(message, *args)
    fun e(t: Throwable?, message: String = "", vararg args: Any?) = delegate.e(t, message, *args)
    fun e(t: Throwable?) = delegate.e(t)
}
