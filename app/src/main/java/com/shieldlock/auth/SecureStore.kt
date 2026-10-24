package com.shieldlock.auth

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Guarda la configuración. Nunca guarda contraseñas en texto plano:
 * la tercera contraseña y el PIN de recuperación se guardan como hash PBKDF2 con sal aleatoria.
 *
 * Calcular un hash es lento a propósito (así cuesta más adivinarlo):
 * llama a guardarTriple() y verificarTerceraORecuperacion() FUERA del hilo principal.
 */
class SecureStore(context: Context) {

    companion object {
        const val MODO_ESTANDAR = "estandar"
        const val MODO_MAXIMO = "maximo"
        const val CALC_SECUENCIA = "secuencia"
        const val CALC_OPERACION = "operacion"
        const val TERCERA_MIN = 6
        const val TERCERA_MAX = 15
        const val LONGITUD_PIN = 12

        private const val ARCHIVO = "shieldlock_prefs"
        private const val K_MODO = "modo"
        private const val K_CONFIGURADO = "configurado"
        private const val K_TERCERA = "tercera_hash"
        private const val K_RECUPERACION = "recuperacion_hash"
        private const val K_NOTIF_PEDIDA = "notif_pedida"
        private const val K_ROOT = "root_verificado"
        private const val K_NAV = "ocultar_navegacion"
        private const val K_IDIOMA_PREG = "idioma_preguntado"
        private const val K_CALC_ACTIVA = "calc_activa"
        private const val K_CALC_MODO = "calc_modo"
        private const val K_CALC_PRIMERO = "calc_primero"
        private const val K_CALC_HASH = "calc_hash"
        private const val K_MUSICA_URI = "musica_uri"
        private const val K_MUSICA_NOMBRE = "musica_nombre"

        private const val FORMATO = "v1"
        private const val ITERACIONES = 120_000

        // Sin 0/O/1/I/L para que se pueda copiar a mano sin confundirse
        private const val ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        /** 12 caracteres aleatorios con SecureRandom. El usuario no puede elegirlo ni cambiarlo. */
        fun generarPinRecuperacion(): CharArray {
            val azar = SecureRandom()
            return CharArray(LONGITUD_PIN) { ALFABETO[azar.nextInt(ALFABETO.length)] }
        }

        /** Para mostrarlo: ABCD-EFGH-JKMN */
        fun formatearPin(pin: CharArray): String = String(pin).chunked(4).joinToString("-")

        private fun algoritmo() =
            if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"

        private fun pbkdf2(secreto: CharArray, sal: ByteArray, iteraciones: Int, alg: String): ByteArray {
            val spec = PBEKeySpec(secreto, sal, iteraciones, 256)
            try {
                return SecretKeyFactory.getInstance(alg).generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        /** Formato guardado: v1:algoritmo:iteraciones:sal:hash (así se puede subir la dificultad después). */
        private fun hash(secreto: CharArray): String {
            val sal = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val alg = algoritmo()
            val h = pbkdf2(secreto, sal, ITERACIONES, alg)
            return listOf(FORMATO, alg, ITERACIONES.toString(), b64(sal), b64(h)).joinToString(":")
        }

        private fun coincide(secreto: CharArray, registro: String?): Boolean {
            if (registro.isNullOrEmpty()) return false
            return try {
                val p = registro.split(":")
                p.size == 5 && p[0] == FORMATO && run {
                    val esperado = Base64.decode(p[4], Base64.NO_WRAP)
                    val real = pbkdf2(secreto, Base64.decode(p[3], Base64.NO_WRAP), p[2].toInt(), p[1])
                    MessageDigest.isEqual(esperado, real) // comparación en tiempo constante
                }
            } catch (e: Exception) {
                false // registro corrupto = no coincide
            }
        }
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    // ---------- Valores simples ----------

    /** "estandar", "maximo" o null. Si el valor guardado es inválido, devuelve null. */
    var modo: String?
        get() = prefs.getString(K_MODO, null)?.takeIf { it == MODO_ESTANDAR || it == MODO_MAXIMO }
        set(value) {
            val e = prefs.edit()
            if (value == null) e.remove(K_MODO) else e.putString(K_MODO, value)
            e.apply()
        }

    /** true cuando el usuario ya guardó su configuración de contraseñas (doble o triple). */
    val configurado: Boolean
        get() = prefs.getBoolean(K_CONFIGURADO, false)

    /** Para saber si ya se pidió el permiso de notificaciones (y detectar el "no volver a preguntar"). */
    var notificacionesPedidas: Boolean
        get() = prefs.getBoolean(K_NOTIF_PEDIDA, false)
        set(value) { prefs.edit().putBoolean(K_NOTIF_PEDIDA, value).apply() }

    /** Resultado de la última verificación de root. Es opcional y solo informativo. */
    var rootVerificado: Boolean
        get() = prefs.getBoolean(K_ROOT, false)
        set(value) { prefs.edit().putBoolean(K_ROOT, value).apply() }

    // ---------- Contraseña calculadora (4ª) ----------

    /** true si hay una calculadora configurada y activa. */
    val calcActiva: Boolean
        get() = prefs.getBoolean(K_CALC_ACTIVA, false) && !prefs.getString(K_CALC_HASH, null).isNullOrEmpty()

    /** CALC_SECUENCIA o CALC_OPERACION. */
    val calcModo: String
        get() = prefs.getString(K_CALC_MODO, CALC_SECUENCIA) ?: CALC_SECUENCIA

    /** true si la calculadora va ANTES del PIN de la hora y de la tercera contraseña. */
    val calcPrimero: Boolean
        get() = prefs.getBoolean(K_CALC_PRIMERO, false)

    /**
     * Guarda solo el HASH del secreto: la secuencia de teclas, o el resultado exacto ya normalizado ("50").
     * Es lento a propósito: llámalo fuera del hilo principal.
     */
    fun guardarCalculadora(modo: String, secreto: CharArray, primero: Boolean): Boolean {
        val h = hash(secreto)
        return prefs.edit()
            .putString(K_CALC_HASH, h)
            .putString(K_CALC_MODO, modo)
            .putBoolean(K_CALC_PRIMERO, primero)
            .putBoolean(K_CALC_ACTIVA, true)
            .commit()
    }

    fun quitarCalculadora(): Boolean =
        prefs.edit().remove(K_CALC_HASH).putBoolean(K_CALC_ACTIVA, false).commit()

    fun verificarCalculadora(secreto: CharArray): Boolean =
        coincide(secreto, prefs.getString(K_CALC_HASH, null))

    /** true cuando ya se le preguntó el idioma al usuario (solo se pregunta la primera vez). */
    var idiomaPreguntado: Boolean
        get() = prefs.getBoolean(K_IDIOMA_PREG, false)
        set(value) { prefs.edit().putBoolean(K_IDIOMA_PREG, value).apply() }

    /** Opción experimental (nivel administrador + root): ocultar botones y gestos mientras se pide el PIN. Apagada por defecto. */
    var ocultarNavegacion: Boolean
        get() = prefs.getBoolean(K_NAV, false)
        set(value) { prefs.edit().putBoolean(K_NAV, value).apply() }

    /** Canción de alerta elegida por el usuario (URI de un archivo suyo). null = alerta en silencio. */
    val musicaUri: String?
        get() = prefs.getString(K_MUSICA_URI, null)?.takeIf { it.isNotEmpty() }

    val musicaNombre: String?
        get() = prefs.getString(K_MUSICA_NOMBRE, null)

    fun guardarMusica(uri: String, nombre: String) {
        prefs.edit().putString(K_MUSICA_URI, uri).putString(K_MUSICA_NOMBRE, nombre).apply()
    }

    fun quitarMusica() {
        prefs.edit().remove(K_MUSICA_URI).remove(K_MUSICA_NOMBRE).apply()
    }

    // ---------- Contraseñas ----------

    /** Triple = existe tercera contraseña. Es la única fuente de verdad del nivel. */
    fun tieneTercera(): Boolean = !prefs.getString(K_TERCERA, null).isNullOrEmpty()

    /** Pasa a doble contraseña y borra tercera contraseña y PIN de recuperación. */
    fun guardarDoble(): Boolean = prefs.edit()
        .remove(K_TERCERA)
        .remove(K_RECUPERACION)
        .putBoolean(K_CONFIGURADO, true)
        .commit()

    /** Guarda los dos hashes y el estado en una sola operación: o se guarda todo o nada. */
    fun guardarTriple(tercera: CharArray, recuperacion: CharArray): Boolean {
        val hTercera = hash(tercera)
        val hRecuperacion = hash(recuperacion)
        return prefs.edit()
            .putString(K_TERCERA, hTercera)
            .putString(K_RECUPERACION, hRecuperacion)
            .putBoolean(K_CONFIGURADO, true)
            .commit()
    }

    /** Acepta la tercera contraseña o el PIN de recuperación (con o sin guiones, en mayúscula o minúscula). */
    fun verificarTerceraORecuperacion(secreto: CharArray): Boolean {
        if (coincide(secreto, prefs.getString(K_TERCERA, null))) return true

        val limpio = secreto.filter { it.isLetterOrDigit() }.map { it.uppercaseChar() }.toCharArray()
        return try {
            limpio.size == LONGITUD_PIN && coincide(limpio, prefs.getString(K_RECUPERACION, null))
        } finally {
            limpio.fill('\u0000')
        }
    }
}