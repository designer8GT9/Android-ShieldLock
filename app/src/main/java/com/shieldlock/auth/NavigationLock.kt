package com.shieldlock.auth

import android.util.Log

/**
 * EXPERIMENTAL. Solo se usa en el nivel "administrador + root" (sin Device Owner).
 *
 * Con root le pide al sistema que ignore los botones y gestos de navegación y el panel de
 * notificaciones. Es estado en memoria del sistema (no un ajuste guardado).
 *
 * Los nombres de las banderas "recents", "statusbar-expansion" y "notification-peek" constan en el
 * código de Android. "home" y "back" NO pude confirmarlos, por eso hay un segundo intento sin ellos.
 * Depende de la versión de Android y del fabricante: hay que probarlo en tu teléfono.
 *
 * Regla de seguridad: los botones SOLO se liberan al acertar el PIN (y la tercera contraseña, si existe)
 * o cuando algo falla y no se puede mostrar la pantalla de bloqueo (para no dejarte sin teléfono).
 */
object NavigationLock {

    private const val TAG = "ShieldLockNav"
    private const val BASE = "cmd statusbar send-disable-flag"
    private const val BLOQUEO_COMPLETO = "$BASE home recents back statusbar-expansion notification-peek"
    private const val BLOQUEO_CONFIRMADO = "$BASE recents statusbar-expansion notification-peek"
    private const val LIBERAR = "$BASE none"

    fun bloquear(): Boolean {
        val ok = RootHelper.ejecutar(BLOQUEO_COMPLETO) || RootHelper.ejecutar(BLOQUEO_CONFIRMADO)
        if (!ok) Log.w(TAG, "No se pudo ocultar la navegación")
        return ok
    }

    fun liberar(): Boolean {
        val ok = RootHelper.ejecutar(LIBERAR) || RootHelper.ejecutar(BASE) // sin banderas = todo vuelve
        if (!ok) Log.w(TAG, "No se pudo restaurar la navegación")
        return ok
    }

    fun bloquearAsync() {
        Thread({ bloquear() }, "ShieldLock-nav-on").start()
    }

    fun liberarAsync() {
        Thread({ liberar() }, "ShieldLock-nav-off").start()
    }
}
