package com.shieldlock.auth

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku y Shizuku+ son OPCIONALES: la app funciona igual sin ellos.
 * Los dos usan la misma API, así que la conexión es la misma (pingBinder, permiso, provider).
 * Lo único que cambia es el nombre del paquete de la app instalada.
 * Shizuku Next conserva el paquete y la API de Shizuku oficial, así que se detecta igual.
 */
object ShizukuHelper {

    const val PAQUETE_OFICIAL = "moe.shizuku.privileged.api"
    const val PAQUETE_PLUS = "af.shizuku.plus.api"
    const val URL_DESCARGA = "https://shizuku.rikka.app/download/"
    const val URL_DESCARGA_PLUS = "https://github.com/thejaustin/ShizukuPlus/releases"
    const val URL_DESCARGA_NEXT = "https://github.com/rushiranpise/Shizuku-Next/releases"

    private val PAQUETES = listOf(PAQUETE_OFICIAL, PAQUETE_PLUS)
    private const val CODIGO_PERMISO = 4217
    private const val TAG = "ShieldLockShizuku"

    enum class Estado {
        NO_INSTALADO,   // no hay app de Shizuku ni de Shizuku+
        NO_ACTIVO,      // instalado pero detenido (hay que iniciarlo)
        NO_COMPATIBLE,  // versión demasiado antigua (anterior a v11)
        SIN_PERMISO,    // activo, pero el usuario no le dio permiso a ShieldLock
        LISTO           // activo y con permiso
    }

    fun estado(context: Context): Estado = try {
        if (Shizuku.pingBinder()) {
            when {
                Shizuku.isPreV11() -> Estado.NO_COMPATIBLE
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Estado.LISTO
                else -> Estado.SIN_PERMISO
            }
        } else if (paqueteInstalado(context) != null) {
            Estado.NO_ACTIVO
        } else {
            Estado.NO_INSTALADO
        }
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo leer el estado de Shizuku", e)
        Estado.NO_INSTALADO
    }

    /** Paquete de la app instalada (Shizuku o Shizuku+), o null si no hay ninguna. */
    fun paqueteInstalado(context: Context): String? =
        PAQUETES.firstOrNull { instalado(context, it) }

    /** true si se envió la solicitud; false si Shizuku la bloqueó ("denegar y no volver a preguntar"). */
    fun pedirPermiso(): Boolean = try {
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            false
        } else {
            Shizuku.requestPermission(CODIGO_PERMISO)
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo pedir permiso a Shizuku", e)
        false
    }

    @Suppress("DEPRECATION")
    private fun instalado(context: Context, paquete: String): Boolean = try {
        context.packageManager.getPackageInfo(paquete, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}