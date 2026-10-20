# Modelos de InnerTube: kotlinx.serialization los necesita tal cual.
-keep,includedescriptorclasses class com.metrolist.innertube.**$$serializer { *; }
-keepclassmembers class com.metrolist.innertube.** { *** Companion; }
-keepclasseswithmembers class com.metrolist.innertube.** { kotlinx.serialization.KSerializer serializer(...); }
-keep class com.metrolist.innertubex.** { *; }
-dontwarn org.slf4j.**

# PoToken: la página de BotGuard llama a estos métodos desde JavaScript.
-keepclassmembers class io.github.iaguito22.oto.playback.potoken.PoTokenWebView {
    @android.webkit.JavascriptInterface public *;
}
-keep class io.github.iaguito22.oto.playback.potoken.** { *; }
