package com.shieldlock.auth

/**
 * Estado en memoria del vigilante. El servicio que detecta el desbloqueo (siguiente paso)
 * lo consulta para saber si debe mostrar la pantalla de bloqueo.
 *
 * Se pierde si el sistema mata el proceso, y eso es lo correcto: ante la duda, se vuelve a pedir.
 */
object GuardState {

    @Volatile
    var verificado: Boolean = false
        private set

    fun marcarVerificado() {
        verificado = true
    }

    fun invalidar() {
        verificado = false
    }
}
