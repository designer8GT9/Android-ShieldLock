package com.shieldlock.auth

import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Root es OPCIONAL: la app funciona igual sin él.
 *
 * No se puede "pedir" root con un permiso de Android: la forma real es ejecutar `su`.
 * En ese momento tu gestor de root (Magisk, KernelSU, etc.) muestra su propio aviso al usuario.
 * Todo lo de aquí BLOQUEA hasta terminar: llámalo fuera del hilo principal.
 */
object RootHelper {

    private const val TAG = "ShieldLockRoot"

    private fun correr(comando: String, tiempoMaxMs: Long): ResultadoComando? {
        val ejecutor = Executors.newSingleThreadExecutor()
        val proceso = AtomicReference<Process?>(null)
        return try {
            val tarea = ejecutor.submit(Callable {
                val p = ProcessBuilder("su", "-c", comando).redirectErrorStream(true).start()
                proceso.set(p)
                val salida = p.inputStream.bufferedReader().use { it.readText() }
                ResultadoComando(p.waitFor(), salida)
            })
            tarea.get(tiempoMaxMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // Sin su, permiso denegado o tiempo agotado: en todos los casos, no hay root
            Log.i(TAG, "Sin acceso root: ${e.javaClass.simpleName}")
            null
        } finally {
            proceso.get()?.destroy()
            ejecutor.shutdownNow()
        }
    }

    /** true solo si `su` responde como uid=0. */
    fun verificar(tiempoMaxMs: Long = 20_000): Boolean =
        correr("id", tiempoMaxMs)?.salida?.contains("uid=0") == true

    /** Ejecuta un comando como root y devuelve el código y la salida. null = no hay root o se agotó el tiempo. */
    fun ejecutarConSalida(comando: String, tiempoMaxMs: Long = 15_000): ResultadoComando? =
        correr(comando, tiempoMaxMs)

    /** Ejecuta un comando como root. true si terminó con código 0. */
    fun ejecutar(comando: String, tiempoMaxMs: Long = 8_000): Boolean {
        val r = correr(comando, tiempoMaxMs)
        if (r == null || r.codigo != 0) {
            Log.w(TAG, "Comando root falló (${r?.codigo}): ${r?.salida?.take(200)}")
            return false
        }
        return true
    }
}