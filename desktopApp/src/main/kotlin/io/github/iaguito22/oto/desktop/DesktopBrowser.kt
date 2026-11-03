package io.github.iaguito22.oto.desktop

import java.io.File

internal fun findChromiumBrowser(
    os: String = System.getProperty("os.name"),
    env: Map<String, String> = System.getenv(),
): String? {
    val windows = os.startsWith("Windows", true)
    val environment = if (windows) env.entries.associate { it.key.uppercase() to it.value } else env
    fun executable(path: String) = File(path).let { it.isFile && it.canExecute() }
    environment["OTO_CHROME"]?.takeIf(::executable)?.let { return it }
    val candidates = when {
        windows -> listOf("PROGRAMFILES", "PROGRAMFILES(X86)", "LOCALAPPDATA").flatMap { variable ->
            val base = environment[variable]?.takeIf(String::isNotBlank) ?: return@flatMap emptyList()
            listOf("Google/Chrome/Application/chrome.exe", "Microsoft/Edge/Application/msedge.exe",
                "BraveSoftware/Brave-Browser/Application/brave.exe").map { File(base, it).path }
        }
        os.contains("mac", true) -> listOf(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        )
        else -> listOf("/usr/bin/google-chrome-stable", "/usr/bin/google-chrome", "/usr/bin/chromium",
            "/usr/bin/chromium-browser", "/usr/bin/brave-browser", "/usr/bin/microsoft-edge", "/snap/bin/chromium")
    }
    candidates.firstOrNull(::executable)?.let { return it }
    val names = if (windows) listOf("chrome.exe", "msedge.exe", "brave.exe", "chromium.exe")
        else listOf("google-chrome-stable", "google-chrome", "chromium", "chromium-browser", "brave-browser", "microsoft-edge")
    return environment["PATH"].orEmpty().split(File.pathSeparatorChar).filter(String::isNotBlank)
        .flatMap { dir -> names.map { File(dir.trim('"'), it).path } }.firstOrNull(::executable)
}
