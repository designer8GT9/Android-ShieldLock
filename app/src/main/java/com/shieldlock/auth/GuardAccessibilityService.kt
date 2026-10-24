package com.shieldlock.auth

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Refuerzo del modo estándar: mientras el usuario no haya pasado la verificación, si aparece
 * cualquier otra pantalla (inicio, otra app...), vuelve a abrir la pantalla de bloqueo, y si se abre
 * el panel de notificaciones, lo cierra.
 */
class GuardAccessibilityService : AccessibilityService() {

    private companion object {
        const val TAG = "ShieldLockAcc"
        const val SYSTEMUI = "com.android.systemui"
        const val ENFRIAMIENTO_MS = 500L
    }

    private val store by lazy { SecureStore(applicationContext) }
    private var ultimoIntento = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val paquete = event.packageName?.toString() ?: return
        if (paquete == packageName) return // es nuestra propia pantalla

        try {
            if (!store.configurado || GuardState.verificado) return
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (km.isKeyguardLocked) return // sigue bloqueado el teléfono: todavía no toca

            if (paquete == SYSTEMUI) cerrarPanelNotificaciones()

            if (LockActivity.visible) return
            val ahora = SystemClock.elapsedRealtime()
            if (ahora - ultimoIntento < ENFRIAMIENTO_MS) return
            ultimoIntento = ahora
            startActivity(LockActivity.intent(this))
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo reforzar el bloqueo", e)
        }
    }

    /**
     * Android 12 o superior: acción global de accesibilidad (no existe antes).
     * Android 11 o inferior: el aviso del sistema que cierra el panel de notificaciones
     * (desde Android 12 las apps ya no pueden enviarlo, por eso allí se usa la acción global).
     */
    @Suppress("DEPRECATION")
    private fun cerrarPanelNotificaciones() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo cerrar el panel de notificaciones", e)
        }
    }

    override fun onInterrupt() {
        // Sin acción
    }
}