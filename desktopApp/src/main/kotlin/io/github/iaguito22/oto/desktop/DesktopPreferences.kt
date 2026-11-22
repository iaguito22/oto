package io.github.iaguito22.oto.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.iaguito22.oto.OtoDirs

internal object DesktopPreferences {
    private val settings = OtoDirs.keyValue("desktop_settings")
    private const val VolumePercentageKey = "show_volume_percentage"
    var showVolumePercentage by mutableStateOf(settings.getString(VolumePercentageKey) == "true")
        private set

    fun updateVolumePercentage(show: Boolean) {
        settings.putString(VolumePercentageKey, show.toString())
        showVolumePercentage = show
    }
}
