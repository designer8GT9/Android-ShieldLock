package com.shieldlock.auth

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ejecuta comandos con Shizuku usando un "UserService" (la forma que recomienda su API actual).
 * BLOQUEA hasta terminar: llámalo SIEMPRE fuera del hilo principal.
 */
object ShizukuShell {

    private const val TAG = "ShieldLockShizukuShell"

    @Volatile
    private var servicio: IShellService? = null

    @Volatile
    private var espera: CountDownLatch? = null

    private val conexion = object : ServiceConnection {
        override fun onServiceConnected(nombre: ComponentName?, binder: IBinder?) {
            servicio = if (binder != null && binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
            espera?.countDown()
        }

        override fun onServiceDisconnected(nombre: ComponentName?) {
            servicio = null
        }
    }

    private fun argumentos(context: Context) =
        Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
            .daemon(false)
            .tag("shieldlock-shell")
            .version(1)

    @Synchronized
    private fun conectar(context: Context, tiempoMs: Long): IShellService? {
        val actual = servicio
        if (actual != null && actual.asBinder().pingBinder()) return actual

        val latch = CountDownLatch(1)
        espera = latch
        Shizuku.bindUserService(argumentos(context), conexion)
        latch.await(tiempoMs, TimeUnit.MILLISECONDS)
        return servicio
    }

    /** null = no se pudo ejecutar (sin Shizuku, sin permiso o se agotó el tiempo). */
    fun ejecutar(context: Context, comando: String, tiempoMs: Long = 10_000): ResultadoComando? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "ShizukuShell.ejecutar no se puede llamar desde el hilo principal")
            return null
        }
        return try {
            val s = conectar(context, tiempoMs) ?: return null
            val bruto = s.ejecutar(comando) ?: return null
            val partes = bruto.split("\n", limit = 2)
            ResultadoComando(partes[0].trim().toIntOrNull() ?: -1, partes.getOrElse(1) { "" })
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo ejecutar el comando con Shizuku", e)
            null
        }
    }
}
