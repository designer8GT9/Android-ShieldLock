package com.shieldlock.auth

import kotlin.system.exitProcess

/**
 * Corre dentro del proceso que Shizuku crea, con identidad de shell (2000) o root (0).
 * No es un servicio normal de Android: no se declara en el manifest, y el contexto de app NO funciona aquí.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun ejecutar(comando: String?): String {
        return try {
            val proceso = ProcessBuilder("sh", "-c", comando ?: "").redirectErrorStream(true).start()
            val salida = proceso.inputStream.bufferedReader().use { it.readText() }
            val codigo = proceso.waitFor()
            "$codigo\n$salida"
        } catch (e: Exception) {
            "-1\n${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
