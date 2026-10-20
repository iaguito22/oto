# Modelos de InnerTube: kotlinx.serialization los necesita tal cual.
-keep,includedescriptorclasses class com.metrolist.innertube.**$$serializer { *; }
-keepclassmembers class com.metrolist.innertube.** { *** Companion; }
-keepclasseswithmembers class com.metrolist.innertube.** { kotlinx.serialization.KSerializer serializer(...); }
-keep class com.metrolist.innertubex.** { *; }
-dontwarn org.slf4j.**
