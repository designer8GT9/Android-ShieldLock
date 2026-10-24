package com.shieldlock.auth

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Activar y quitar Device Owner sin computadora, usando Shizuku o root.
 * Todo BLOQUEA: llámalo fuera del hilo principal.
 */
object DeviceOwnerHelper {

    private const val TAG = "ShieldLockDO"

    class Resultado(val ok: Boolean, val mensaje: String)

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun componente(context: Context) = ComponentName(context, AdminReceiver::class.java)

    fun activar(context: Context, store: SecureStore): Resultado {
        val paquete = context.packageName
        if (dpm(context).isDeviceOwnerApp(paquete)) return Resultado(true, "ShieldLock ya es Device Owner.")

        val r = Privilegios.ejecutar(
            context, store, "dpm set-device-owner ${componente(context).flattenToString()}"
        ) ?: return Resultado(false, "No hay acceso de Shizuku ni de root.")

        return if (r.ok && dpm(context).isDeviceOwnerApp(paquete)) {
            Resultado(true, "Device Owner activado ✅")
        } else {
            Log.w(TAG, "set-device-owner falló (${r.codigo}): ${r.salida.take(300)}")
            Resultado(false, traducir(r.salida))
        }
    }

    @Suppress("DEPRECATION")
    fun quitar(context: Context, store: SecureStore): Resultado {
        val paquete = context.packageName
        val manager = dpm(context)
        if (!manager.isDeviceOwnerApp(paquete)) return Resultado(true, "ShieldLock ya no es Device Owner.")

        // Primer intento: la propia app se quita
        try {
            manager.clearDeviceOwnerApp(paquete)
        } catch (e: Exception) {
            Log.w(TAG, "clearDeviceOwnerApp falló", e)
        }
        if (!manager.isDeviceOwnerApp(paquete)) return Resultado(true, "Device Owner quitado.")

        // Plan B: con Shizuku o root
        val r = Privilegios.ejecutar(
            context, store, "dpm remove-active-admin --user 0 ${componente(context).flattenToString()}"
        )
        return if (!manager.isDeviceOwnerApp(paquete)) {
            Resultado(true, "Device Owner quitado.")
        } else {
            val detalle = r?.salida?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(140)
                ?: "No hay acceso extra de Shizuku ni de root."
            Resultado(false, "No se pudo quitar Device Owner. $detalle")
        }
    }

    /** Traduce los errores más comunes de `dpm set-device-owner`. Si no reconoce el error, muestra el original. */
    private fun traducir(salida: String): String {
        val s = salida.lowercase()
        return when {
            "already some accounts" in s || "accounts on the device" in s ->
                "El teléfono tiene cuentas agregadas (Google, Samsung, Xiaomi…). Quítalas todas y reintenta."
            "several users" in s ->
                "El teléfono tiene más de un usuario. Deja solo el principal y reintenta."
            "already set" in s ->
                "Ya hay otro Device Owner en este teléfono (por ejemplo, Dhizuku)."
            else ->
                "No se pudo: " + (salida.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: "sin detalle")
        }
    }
}
