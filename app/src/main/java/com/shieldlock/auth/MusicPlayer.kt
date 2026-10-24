package com.shieldlock.auth

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

/**
 * Reproduce la canción en bucle hasta que se llame a detener().
 * Si el archivo ya no existe o no se puede leer, falla en silencio: la pantalla de bloqueo
 * nunca debe fallar por culpa de la música.
 */
class MusicPlayer(private val context: Context) {

    private companion object {
        const val TAG = "ShieldLockMusic"
    }

    private var player: MediaPlayer? = null

    fun iniciar(uri: Uri) {
        detener()
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.setOnPreparedListener { listo ->
                try {
                    listo.start()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "No se pudo empezar a reproducir", e)
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Error de reproducción: $what / $extra")
                detener()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo abrir la canción", e)
            detener()
        }
    }

    fun detener() {
        val mp = player ?: return
        player = null
        try {
            if (mp.isPlaying) mp.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "El reproductor ya estaba detenido", e)
        }
        mp.release()
    }
}
