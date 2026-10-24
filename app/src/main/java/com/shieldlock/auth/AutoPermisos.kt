package com.shieldlock.auth

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Si la app tiene acceso con Shizuku (o root), revisa qué permisos le faltan y se los da sola.
 * Todo lo que ejecuta BLOQUEA: llámalo fuera del hilo principal.
 */
object AutoPermisos {

    private const val TAG = "ShieldLockAuto"

    enum class Requisito(val nombre: String) {
        ADMIN("Administrador"),
        ACCESIBILIDAD("Accesibilidad"),
        SUPERPOSICION("Superposición"),
        NOTIFICACIONES("Notificaciones"),
        BATERIA("Batería")
    }

    class Informe(
        val sinAcceso: Boolean,
        val concedidos: List<String>,
        val fallidos: List<String>
    )

    /** Qué permisos le importan a cada modo. El bloqueo de pantalla del sistema no se puede dar por comando. */
    private fun relevantes(modo: String, esOwner: Boolean): List<Requisito> =
        if (modo == SecureStore.MODO_MAXIMO) {
            // Con Device Owner ya hay administrador; sin él (camino por root) hace falta el administrador normal
            if (esOwner) listOf(Requisito.BATERIA) else listOf(Requisito.ADMIN, Requisito.BATERIA)
        } else {
            listOf(
                Requisito.ADMIN, Requisito.ACCESIBILIDAD, Requisito.SUPERPOSICION,
                Requisito.NOTIFICACIONES, Requisito.BATERIA
            )
        }

    fun faltantes(context: Context, modo: String, esOwner: Boolean): List<Requisito> =
        relevantes(modo, esOwner).filter { !tiene(context, it) }

    fun completar(context: Context, store: SecureStore, modo: String, esOwner: Boolean): Informe {
        val faltan = faltantes(context, modo, esOwner)
        if (faltan.isEmpty()) return Informe(false, emptyList(), emptyList())
        if (Privilegios.via(context, store) == null) {
            return Informe(true, emptyList(), faltan.map { it.nombre })
        }

        val concedidos = mutableListOf<String>()
        val fallidos = mutableListOf<String>()
        for (requisito in faltan) {
            var comandosOk = true
            for (comando in comandosPara(context, requisito)) {
                val r = Privilegios.ejecutar(context, store, comando)
                if (r == null || !r.ok) {
                    Log.w(TAG, "Falló '$comando': ${r?.salida?.take(200)}")
                    comandosOk = false
                    break
                }
            }
            // No basta con que el comando "no dé error": se comprueba que el permiso quedó concedido
            if (comandosOk && esperarHasta { tiene(context, requisito) }) {
                concedidos += requisito.nombre
            } else {
                fallidos += requisito.nombre
            }
        }
        return Informe(false, concedidos, fallidos)
    }

    // ---------- Comandos ----------

    private fun comandosPara(context: Context, r: Requisito): List<String> {
        val paquete = context.packageName
        return when (r) {
            Requisito.ADMIN ->
                listOf("dpm set-active-admin --user 0 ${componenteAdmin(context).flattenToString()}")

            Requisito.ACCESIBILIDAD -> {
                val servicio = componenteAccesibilidad(context)
                val actual = Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val yaEnLista = actual.split(':').any { ComponentName.unflattenFromString(it) == servicio }
                if (yaEnLista) {
                    listOf("settings put secure accessibility_enabled 1")
                } else {
                    // Se AGREGA a la lista: así no se desactivan los otros servicios de accesibilidad del usuario
                    val nuevo = if (actual.isEmpty()) servicio.flattenToString() else "$actual:${servicio.flattenToString()}"
                    listOf(
                        "settings put secure enabled_accessibility_services '$nuevo'",
                        "settings put secure accessibility_enabled 1"
                    )
                }
            }

            Requisito.SUPERPOSICION -> listOf("appops set $paquete SYSTEM_ALERT_WINDOW allow")
            Requisito.NOTIFICACIONES -> listOf("pm grant $paquete android.permission.POST_NOTIFICATIONS")
            Requisito.BATERIA ->
                listOf("cmd deviceidle whitelist +$paquete || dumpsys deviceidle whitelist +$paquete")
        }
    }

    // ---------- Comprobaciones ----------

    private fun tiene(context: Context, r: Requisito): Boolean = try {
        when (r) {
            Requisito.ADMIN -> {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.isAdminActive(componenteAdmin(context))
            }
            Requisito.ACCESIBILIDAD -> {
                val actual = Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val servicio = componenteAccesibilidad(context)
                actual.split(':').any { ComponentName.unflattenFromString(it) == servicio }
            }
            Requisito.SUPERPOSICION -> Settings.canDrawOverlays(context)
            Requisito.NOTIFICACIONES ->
                Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
            Requisito.BATERIA ->
                (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(context.packageName)
        }
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo comprobar ${r.nombre}", e)
        false
    }

    /** Algunos cambios (como encender un servicio de accesibilidad) tardan un instante en verse. */
    private fun esperarHasta(intentos: Int = 6, pausaMs: Long = 500, condicion: () -> Boolean): Boolean {
        repeat(intentos) {
            if (condicion()) return true
            Thread.sleep(pausaMs)
        }
        return condicion()
    }

    private fun componenteAdmin(context: Context) = ComponentName(context, AdminReceiver::class.java)

    private fun componenteAccesibilidad(context: Context) =
        ComponentName(context, GuardAccessibilityService::class.java)
}
