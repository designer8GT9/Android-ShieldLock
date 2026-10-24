package com.shieldlock.auth

import android.content.Context

class ResultadoComando(val codigo: Int, val salida: String) {
    val ok: Boolean get() = codigo == 0
}

/**
 * Acceso privilegiado unificado: Shizuku primero y, si no, root (si el usuario ya lo verificó).
 * Todo lo que ejecuta BLOQUEA: llámalo fuera del hilo principal.
 */
object Privilegios {

    enum class Via(val nombre: String) {
        SHIZUKU("Shizuku"),
        ROOT("root")
    }

    /** Cuál acceso hay AHORA, o null si no hay ninguno. Es barato: se puede llamar al dibujar la pantalla. */
    fun via(context: Context, store: SecureStore): Via? = when {
        ShizukuHelper.estado(context) == ShizukuHelper.Estado.LISTO -> Via.SHIZUKU
        store.rootVerificado -> Via.ROOT
        else -> null
    }

    /** null = no hay acceso o no se pudo ejecutar. */
    fun ejecutar(context: Context, store: SecureStore, comando: String): ResultadoComando? =
        when (via(context, store)) {
            Via.SHIZUKU -> ShizukuShell.ejecutar(context, comando)
                ?: if (store.rootVerificado) RootHelper.ejecutarConSalida(comando) else null
            Via.ROOT -> RootHelper.ejecutarConSalida(comando)
            null -> null
        }
}
