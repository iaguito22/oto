package io.github.iaguito22.oto

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Audición local con una cola fija de empalmes reproducibles. */
class DjMixTrialActivity : Activity() {
    private lateinit var player: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val heading = TextView(this).apply {
            text = "DJ rápido · Prueba de mezcla"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        val current = TextView(this).apply {
            text = "Preparando la cola"
            textSize = 19f
            gravity = Gravity.CENTER
            setPadding(28, 40, 28, 0)
        }
        val repeat = Button(this).apply { text = "Repetir" }
        val next = Button(this).apply { text = "Siguiente" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(heading)
            addView(current)
            addView(LinearLayout(this@DjMixTrialActivity).apply {
                gravity = Gravity.CENTER
                setPadding(0, 32, 0, 0)
                addView(repeat)
                addView(next)
            })
        })
        val examples = listOf(
            "Bucle por pulsos y letra\nI Ain’t Worried → thank u, next",
            "Mezcla a tempo\nRunning with the Wild Things → Warriors",
            "Corte de intensidad · 4 pulsos y entrada anticipada\nWildfire → That Won’t Save Us",
            "Salida con filtro y eco\nCannabis → La Playa",
            "Fundido de 6 segundos\nback to friends → A Thousand Miles",
            "Referencia a tempo\nOutside → Sofía",
            "Referencia de bucle\nHeavy Is the Crown → versión orquestal",
            "Referencia a tempo\nOutside → Blame",
            "Referencia de intensidad\nThe Call → Still Here",
            "Referencia de filtro\nNo One Noticed → the perfect pair",
            "Referencia de filtro\nDie For You → Inspiration",
            "Referencia de bucle\nButterflies → Light It Up",
            "Referencia de bucle\nSprinter → like i do",
            "Referencia de salida de bucle\nLEMONADE → IYKYK",
            "Referencia · Bucle por pulsos y letra\nSide to Side → Love Again",
            "Referencia · Mezcla a tempo\nHeat Waves → QUEDATE",
            "Referencia · Corte de intensidad · 4 pulsos y entrada anticipada\nRebel Heart → weapon",
            "Referencia · Salida con filtro y eco\nAlways You & I → What I’ve Done",
            "Referencia · Fundido de 6 segundos\nweapon → Happier Than Ever",
        )
        val items = examples.mapIndexed { index, title ->
            MediaItem.Builder()
                .setUri("asset:///dj-mix-trial/${index + 1}.m4a")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
        }
        var index = 0
        fun playExample() {
            current.text = "${index + 1} / ${items.size}\n\n${examples[index]}"
            next.isEnabled = index < items.lastIndex
            player.setMediaItem(items[index])
            player.prepare()
            player.play()
            android.util.Log.i("OtoMixTrial", "Reproduciendo ${index + 1}")
        }
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        current.text = "${index + 1} / ${items.size}\n\n${examples[index]}\n\nPrueba terminada"
                        android.util.Log.i("OtoMixTrial", "Prueba ${index + 1} terminada")
                    }
                }
            })
        }
        repeat.setOnClickListener { playExample() }
        next.setOnClickListener {
            if (index < items.lastIndex) {
                index++
                playExample()
            }
        }
        playExample()
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
