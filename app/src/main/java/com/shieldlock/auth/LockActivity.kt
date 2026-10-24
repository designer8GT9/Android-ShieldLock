package com.shieldlock.auth

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pantalla de bloqueo de ShieldLock (2ª y 3ª contraseña).
 *
 *  Paso 1: PIN = la hora actual.
 *  Paso 2: tercera contraseña (o PIN de recuperación), solo si el usuario la configuró.
 *
 * Reglas:
 *  - La canción suena desde que aparece la pantalla hasta que todo es correcto.
 *  - UN solo error (en el PIN o en la tercera contraseña) bloquea el teléfono y se vuelve a empezar.
 *  - No hay pistas: la pantalla no dice qué paso falló ni cuántos pasos hay.
 *
 * Esta versión aún no la lanza ningún servicio: se abre con el botón "Probar" de MainActivity.
 */
class LockActivity : SecureActivity() {

    companion object {
        private const val TAG = "ShieldLockLock"
        private const val NUL = '\u0000'
        private const val EXTRA_PRUEBA = "prueba"
        private const val DURACION_PRUEBA_MS = 60_000L

        /** true mientras la pantalla de bloqueo está a la vista. El guardián y la accesibilidad lo consultan. */
        @Volatile
        var visible: Boolean = false

        /**
         * prueba = true: un error NO bloquea el teléfono (para poder probar sin molestia).
         * Si se abre desde un servicio (sin Activity), se agrega NEW_TASK, que Android exige en ese caso.
         */
        fun intent(context: Context, prueba: Boolean = false): Intent =
            Intent(context, LockActivity::class.java).apply {
                putExtra(EXTRA_PRUEBA, prueba)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    private enum class Paso { PIN, TERCERA }

    private lateinit var store: SecureStore
    private lateinit var tilPin: TextInputLayout
    private lateinit var etPin: TextInputEditText
    private lateinit var tilTercera: TextInputLayout
    private lateinit var etTercera: TextInputEditText
    private lateinit var btnContinuar: MaterialButton
    private lateinit var btnSalirPrueba: MaterialButton

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var musica: MusicPlayer? = null
    private var paso = Paso.PIN
    private var prueba = false
    private var verificando = false
    private val handler = Handler(Looper.getMainLooper())
    private var resuelto = false // ya terminó, sea por éxito o por fallo: nada se procesa dos veces

    // ---------- Ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // SecureActivity pone FLAG_SECURE
        store = SecureStore(this)
        prueba = intent.getBooleanExtra(EXTRA_PRUEBA, false)

        // Sin configuración guardada no hay nada que pedir
        if (!store.configurado) {
            finishAndRemoveTask()
            return
        }

        try {
            setContentView(R.layout.activity_lock)
            enlazarVistas()
            ajustarInsets()
            bloquearAtras()
            configurarEventos()
            mostrarPaso()
            iniciarMusica()
            aplicarRefuerzos()
        } catch (e: Exception) {
            // OJO: aquí se "falla abierto" para poder depurar. Cuando el servicio esté listo,
            // se decide si un fallo de esta pantalla debe bloquear el teléfono.
            Log.e(TAG, "No se pudo mostrar la pantalla de bloqueo", e)
            musica?.detener()
            liberarNavegacion() // si no hay pantalla, no se deja el teléfono sin botones
            finishAndRemoveTask()
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        super.onStop()
        visible = false
        // Si la pantalla se apagó, se cancela todo: al próximo desbloqueo se pide desde cero
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!resuelto && !pm.isInteractive) {
            resuelto = true
            musica?.detener()
            GuardState.invalidar()
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        musica?.detener()
        handler.removeCallbacksAndMessages(null)
        if (prueba) liberarNavegacion() // la prueba nunca deja los botones ocultos
        io.shutdown()
        super.onDestroy()
    }

    // ---------- Configuración de la pantalla ----------

    private fun enlazarVistas() {
        tilPin = findViewById(R.id.tilPin)
        etPin = findViewById(R.id.etPin)
        tilTercera = findViewById(R.id.tilTercera)
        etTercera = findViewById(R.id.etTercera)
        btnContinuar = findViewById(R.id.btnContinuar)
        btnSalirPrueba = findViewById(R.id.btnSalirPrueba)
    }

    private fun ajustarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.lockRoot)) { v, insets ->
            val barras = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.updatePadding(left = barras.left, top = barras.top, right = barras.right, bottom = barras.bottom)
            insets
        }
    }

    /** Atrás no sale de la pantalla (solo en modo prueba, para poder cerrarla). */
    private fun bloquearAtras() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (prueba) salirPrueba()
            }
        })
    }

    private fun configurarEventos() {
        btnContinuar.setOnClickListener { continuar() }
        val alTerminar = TextView.OnEditorActionListener { _, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_DONE) {
                continuar()
                true
            } else {
                false
            }
        }
        etPin.setOnEditorActionListener(alTerminar)
        etTercera.setOnEditorActionListener(alTerminar)
    }

    private fun mostrarPaso() {
        val esPin = paso == Paso.PIN
        tilPin.visibility = if (esPin) android.view.View.VISIBLE else android.view.View.GONE
        tilTercera.visibility = if (esPin) android.view.View.GONE else android.view.View.VISIBLE

        val campo = if (esPin) etPin else etTercera
        campo.requestFocus()
        campo.post {
            WindowCompat.getInsetsController(window, campo).show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun iniciarMusica() {
        val uri = store.musicaUri ?: return // sin canción elegida: la alerta es en silencio
        musica = MusicPlayer(applicationContext).also { it.iniciar(Uri.parse(uri)) }
    }

    // ---------- Verificación ----------

    private fun continuar() {
        if (verificando || resuelto) return

        when (paso) {
            Paso.PIN -> {
                val ingresado = leer(etPin)
                if (ingresado.isEmpty()) return // un campo vacío no cuenta como intento
                val correcto = try {
                    TimePin.esValido(this, ingresado)
                } finally {
                    ingresado.fill(NUL)
                }
                if (!correcto) {
                    fallar()
                    return
                }
                etPin.text?.clear()
                if (store.tieneTercera()) {
                    paso = Paso.TERCERA
                    mostrarPaso()
                } else {
                    exito()
                }
            }

            Paso.TERCERA -> {
                val secreto = leer(etTercera)
                if (secreto.isEmpty()) return
                verificando = true
                btnContinuar.isEnabled = false
                // El hash es lento a propósito: va en un hilo aparte para no congelar la pantalla
                io.execute {
                    val correcto = try {
                        store.verificarTerceraORecuperacion(secreto)
                    } catch (e: Exception) {
                        Log.e(TAG, "Fallo al verificar", e)
                        false
                    } finally {
                        secreto.fill(NUL)
                    }
                    runOnUiThread {
                        verificando = false
                        if (correcto) {
                            if (!isDestroyed) exito()
                        } else {
                            fallar() // aunque la pantalla ya no exista, un error bloquea igual
                        }
                    }
                }
            }
        }
    }

    private fun exito() {
        if (resuelto) return
        resuelto = true
        musica?.detener()
        if (prueba) {
            toast("Correcto ✅")
        } else {
            GuardState.marcarVerificado()
        }
        liberarNavegacion() // los botones vuelven SOLO al acertar todo
        finishAndRemoveTask()
    }

    private fun fallar() {
        if (resuelto) return
        resuelto = true
        musica?.detener() // la canción no suena tras un intento incorrecto
        GuardState.invalidar()
        if (prueba) {
            toast("Incorrecto. En uso real se bloquearía el teléfono.")
            liberarNavegacion()
        } else {
            bloquearPantalla()
        }
        finishAndRemoveTask()
    }

    private fun bloquearPantalla() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) dpm.lockNow()
        } catch (e: SecurityException) {
            Log.w(TAG, "lockNow no permitido", e)
        }
    }

    // ---------- Refuerzos según el nivel ----------

    private fun aplicarRefuerzos() {
        if (prueba) {
            // En la prueba siempre hay salida: botón visible y cierre automático
            btnSalirPrueba.visibility = View.VISIBLE
            btnSalirPrueba.setOnClickListener { salirPrueba() }
            handler.postDelayed({ salirPrueba() }, DURACION_PRUEBA_MS)
        }
        if (GestorNivel.refuerzos(this, store).contains(Refuerzo.OCULTAR_NAVEGACION)) {
            NavigationLock.bloquearAsync()
        }
    }

    private fun liberarNavegacion() {
        if (GestorNivel.calcular(this, store) == Nivel.MAXIMO_ADMIN_ROOT) NavigationLock.liberarAsync()
    }

    private fun salirPrueba() {
        if (resuelto) return
        resuelto = true
        musica?.detener()
        liberarNavegacion()
        finishAndRemoveTask()
    }

    // ---------- Utilidades ----------

    private fun leer(campo: TextInputEditText): CharArray {
        val texto = campo.text ?: return CharArray(0)
        return CharArray(texto.length) { i -> texto[i] }
    }

    private fun toast(mensaje: String) =
        Toast.makeText(applicationContext, mensaje, Toast.LENGTH_SHORT).show()
}
