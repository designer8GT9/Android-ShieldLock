package com.shieldlock.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Se ejecuta cuando el teléfono termina de arrancar (y el usuario ya lo desbloqueó con su
 * primera contraseña) o cuando ShieldLock se actualiza.
 *
 * Solo actúa si el usuario ya guardó su configuración; si no, no hace nada.
 */
class BootReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "ShieldLockBoot"
        const val QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON" // algunos fabricantes (HTC, Xiaomi...)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val accion = intent?.action ?: return
        if (accion != Intent.ACTION_BOOT_COMPLETED &&
            accion != QUICKBOOT &&
            accion != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        try {
            if (!SecureStore(context).configurado) return
            Log.i(TAG, "Arranque detectado ($accion): se reactiva el guardián")
            GuardService.iniciar(context)
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al reactivar la protección tras el arranque", e)
        }
    }
}