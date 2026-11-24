package io.github.iaguito22.oto.remote

/**
 * Con dos equipos enlazados solo suena uno: el último que empieza manda y el otro se pausa.
 * Si sonaran los dos, al pausar uno el reproductor pasaría a mostrar (y controlar) el otro, que seguía sonando.
 * Se alimenta con lo que suena aquí y lo que cuenta el otro (null sin conexión); solo reacciona a arranques.
 */
class OneAtATime {
    enum class Action { None, PauseRemote, PauseLocal }

    private var local: Boolean? = null
    private var remote: Boolean? = null

    fun update(localPlaying: Boolean, remotePlaying: Boolean?): Action {
        val localStarted = localPlaying && local == false
        val remoteStarted = remotePlaying == true && remote == false
        // Sin conexión se olvida todo: al volver no se sabe quién empezó antes.
        local = localPlaying.takeIf { remotePlaying != null }
        remote = remotePlaying
        return when {
            !localPlaying || remotePlaying != true -> Action.None
            // Los dos a la vez: no se sabe cuál fue el último.
            localStarted && remoteStarted -> Action.None
            localStarted -> Action.PauseRemote
            remoteStarted -> Action.PauseLocal
            else -> Action.None
        }
    }
}
