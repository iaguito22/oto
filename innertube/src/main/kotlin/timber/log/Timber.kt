package timber.log

/**
 * Shim multiplataforma de Timber para que `:innertube`, `:core`, `:app` y `:desktopApp`
 * compilen en JVM puro y en Android con la misma API (`Timber.plant`, `Timber.Tree`, `Timber.DebugTree`).
 */
object Timber {
    abstract class Tree {
        protected open fun log(priority: Int, tag: String?, message: String, t: Throwable?) {}

        open fun d(message: String, vararg args: Any?) = log(3, null, format(message, args), null)
        open fun d(t: Throwable?, message: String = "", vararg args: Any?) = log(3, null, format(message, args), t)
        open fun i(message: String, vararg args: Any?) = log(4, null, format(message, args), null)
        open fun i(t: Throwable?, message: String = "", vararg args: Any?) = log(4, null, format(message, args), t)
        open fun w(message: String, vararg args: Any?) = log(5, null, format(message, args), null)
        open fun w(t: Throwable?, message: String = "", vararg args: Any?) = log(5, null, format(message, args), t)
        open fun w(t: Throwable?) = log(5, null, t?.message.orEmpty(), t)
        open fun e(message: String, vararg args: Any?) = log(6, null, format(message, args), null)
        open fun e(t: Throwable?, message: String = "", vararg args: Any?) = log(6, null, format(message, args), t)
        open fun e(t: Throwable?) = log(6, null, t?.message.orEmpty(), t)

        private fun format(message: String, args: Array<out Any?>): String =
            if (args.isEmpty()) message else runCatching { message.format(*args) }.getOrDefault(message)
    }

    open class DebugTree : Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val prefix = if (tag != null) "[$tag] " else ""
            if (priority >= 5) {
                System.err.println("$prefix$message")
                t?.printStackTrace(System.err)
            } else {
                println("$prefix$message")
            }
        }
    }

    @Volatile
    var delegate: Tree = object : Tree() {}

    fun plant(tree: Tree) {
        delegate = tree
    }

    fun tag(tag: String): Tree = delegate
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
