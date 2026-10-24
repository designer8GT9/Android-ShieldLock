package com.shieldlock.auth

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

/** Nivel de protección según los permisos y accesos que el usuario le dio a la app. */
enum class Nivel(val titulo: Int, val descripcion: Int) {
    INCOMPLETO(R.string.s_incomplete, R.string.s_permissions_or_root_are_missing),
    ESTANDAR(R.string.s_standard, R.string.s_administrator_accessibility_and_overlay),
    ESTANDAR_SHIZUKU(R.string.s_standard_shizuku, R.string.s_shizuku_connected_the_app_repairs),
    MAXIMO_OWNER(R.string.s_maximum_with_device_owner, R.string.s_device_owner_active_kiosk_mode),
    MAXIMO_OWNER_ROOT(R.string.s_maximum_with_device_owner_and, R.string.s_root_repairs_permissions_and_forces),
    MAXIMO_ADMIN_ROOT(R.string.s_maximum_with_administrator_and_root, R.string.s_no_device_owner_root_forces)
}

/** Cosas extra que la app puede hacer cuando el nivel lo permite. */
enum class Refuerzo {
    LANZAR_CON_PRIVILEGIOS, // si Android bloquea abrir la pantalla desde segundo plano, se abre con Shizuku o root
    OCULTAR_NAVEGACION   // experimental: ignora botones, gestos y panel de notificaciones mientras se pide el PIN
}

/**
 * Gestor adaptativo: mira qué permisos hay AHORA y decide qué refuerzos usar.
 * No pide ni cambia permisos: solo lee.
 */
object GestorNivel {

    private const val TAG = "ShieldLockNivel"

    fun calcular(context: Context, store: SecureStore): Nivel {
        val modo = store.modo ?: return Nivel.INCOMPLETO
        return try {
            if (modo == SecureStore.MODO_MAXIMO) {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val esOwner = dpm.isDeviceOwnerApp(context.packageName)
                val esAdmin = esOwner || dpm.isAdminActive(ComponentName(context, AdminReceiver::class.java))
                val root = store.rootVerificado
                when {
                    esOwner && root -> Nivel.MAXIMO_OWNER_ROOT
                    esOwner -> Nivel.MAXIMO_OWNER
                    esAdmin && root -> Nivel.MAXIMO_ADMIN_ROOT
                    else -> Nivel.INCOMPLETO
                }
            } else {
                if (ShizukuHelper.estado(context) == ShizukuHelper.Estado.LISTO) Nivel.ESTANDAR_SHIZUKU else Nivel.ESTANDAR
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo calcular el nivel", e)
            Nivel.INCOMPLETO
        }
    }

    /** La contraseña calculadora solo existe con Device Owner (solo o con root). Con administrador + root, no. */
    fun calculadoraDisponible(context: Context, store: SecureStore): Boolean {
        val nivel = calcular(context, store)
        return nivel == Nivel.MAXIMO_OWNER || nivel == Nivel.MAXIMO_OWNER_ROOT
    }

    fun refuerzos(context: Context, store: SecureStore): Set<Refuerzo> {
        val nivel = calcular(context, store)
        val activos = mutableSetOf<Refuerzo>()
        if (nivel == Nivel.ESTANDAR_SHIZUKU || nivel == Nivel.MAXIMO_OWNER_ROOT || nivel == Nivel.MAXIMO_ADMIN_ROOT) {
            activos += Refuerzo.LANZAR_CON_PRIVILEGIOS
        }
        if (nivel == Nivel.MAXIMO_ADMIN_ROOT && store.ocultarNavegacion) {
            activos += Refuerzo.OCULTAR_NAVEGACION
        }
        return activos
    }
}