package com.shieldlock.auth

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * La 4ª contraseña: una calculadora de aspecto antiguo.
 *  - Modo secuencia: se desbloquea al pulsar = con exactamente las teclas secretas.
 *  - Modo operación: se desbloquea con CUALQUIER operación cuyo resultado sea exactamente el número elegido.
 *
 * No usa el teclado de Android: todas las teclas son de esta pantalla.
 * Un solo error (pulsar = con algo que no es el secreto) cuenta como fallo.
 *
 * ETAPA 1: solo se abre desde el botón "Probar calculadora" (modo prueba: sin bloqueo ni kiosco).
 */
class CalculadoraActivity : SecureActivity() {

    companion object {
        private const val TAG = "ShieldLockCalc"
        private const val NUL = '\u0000'
        private const val EXTRA_PRUEBA = "prueba"
        private const val DURACION_PRUEBA_MS = 60_000L

        /** true mientras la calculadora está a la vista. */
        @Volatile
        var visible: Boolean = false

        fun intent(context: Context, prueba: Boolean = false): Intent =
            Intent(context, CalculadoraActivity::class.java).apply {
                putExtra(EXTRA_PRUEBA, prueba)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    private lateinit var store: SecureStore
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val buffer = StringBuilder()
    private var memoria: String? = null
    private var prueba = false
    private var verificando = false
    private var resuelto = false

    private lateinit var tvPrincipal: TextView
    private lateinit var tvExpresion: TextView
    private lateinit var tvMemoria: TextView

    // ---------- Ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        prueba = intent.getBooleanExtra(EXTRA_PRUEBA, false)

        if (!store.calcActiva) {
            finishAndRemoveTask()
            return
        }
        try {
            setContentView(construirPantalla())
            ocultarBarras()
            bloquearAtras()
            refrescar()
            if (prueba) handler.postDelayed({ salirPrueba() }, DURACION_PRUEBA_MS)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo mostrar la calculadora", e)
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
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!resuelto && !pm.isInteractive) { // pantalla apagada: se cancela y se vuelve a empezar
            resuelto = true
            GuardState.invalidar()
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ocultarBarras()
    }

    private fun ocultarBarras() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val control = WindowInsetsControllerCompat(window, window.decorView)
        control.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        control.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun bloquearAtras() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (prueba) salirPrueba()
            }
        })
    }

    // ---------- Pantalla ----------

    private fun construirPantalla(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(12), dp(24), dp(12), dp(16))
        }

        // Pantalla de cristal líquido
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFFB9C3B1.toInt())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(4), 0xFF3A3A3A.toInt())
            }
        }
        val filaSuperior = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tvMemoria = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF4F5A48.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        filaSuperior.addView(tvMemoria)
        if (prueba) {
            filaSuperior.addView(TextView(this).apply {
                text = getString(R.string.s_exit_test)
                textSize = 12f
                setTextColor(0xFF4F5A48.toInt())
                setOnClickListener { salirPrueba() }
            })
        }
        panel.addView(filaSuperior)

        tvPrincipal = TextView(this).apply {
            textSize = 52f
            setTextColor(0xFF1A1A1A.toInt())
            gravity = Gravity.END
            setSingleLine(true)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            text = "0"
        }
        tvExpresion = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFF6F7A68.toInt())
            gravity = Gravity.END
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        panel.addView(tvPrincipal)
        panel.addView(tvExpresion)
        raiz.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f))

        // Teclado: 4 columnas a la izquierda y una columna de operaciones a la derecha
        val izquierda = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val filas = listOf(
            listOf("%", "MC", "MR", "MS"),
            listOf("√", "7", "8", "9"),
            listOf("±", "4", "5", "6"),
            listOf("⌫", "1", "2", "3"),
            listOf("C", "0", ".", "=")
        )
        for (fila in filas) {
            val linea = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (etiqueta in fila) linea.addView(tecla(etiqueta), parametrosFila())
            izquierda.addView(linea, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val derecha = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        derecha.addView(tecla("*"), parametrosColumna(1f))
        derecha.addView(tecla("÷"), parametrosColumna(1f))
        derecha.addView(tecla("−"), parametrosColumna(1f))
        derecha.addView(tecla("+"), parametrosColumna(2f)) // la tecla + ocupa dos filas

        val teclado = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        teclado.addView(izquierda, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 4f))
        teclado.addView(derecha, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        raiz.addView(
            teclado,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 6f).apply { topMargin = dp(12) }
        )
        return raiz
    }

    private fun tecla(etiqueta: String): TextView {
        val colores = when (etiqueta) {
            "C" -> 0xFFE02020.toInt() to Color.WHITE
            "=" -> 0xFFFFA800.toInt() to Color.WHITE
            "+" -> 0xFF1FA89A.toInt() to Color.WHITE
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "." -> 0xFF9A9A9A.toInt() to 0xFFF2F2F2.toInt()
            else -> 0xFF5C5C5C.toInt() to Color.WHITE
        }
        return TextView(this).apply {
            text = etiqueta
            gravity = Gravity.CENTER
            textSize = if (etiqueta.length > 1) 22f else 28f
            setTextColor(colores.second)
            background = fondoTecla(colores.first)
            isClickable = true
            setOnClickListener { pulsar(etiqueta) }
        }
    }

    private fun fondoTecla(color: Int): StateListDrawable {
        fun capa(c: Int) = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(escalar(c, 1.25f), c)
        ).apply {
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), escalar(c, 0.6f))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), capa(escalar(color, 0.75f)))
            addState(intArrayOf(), capa(color))
        }
    }

    private fun escalar(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )

    private fun parametrosFila() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }

    private fun parametrosColumna(peso: Float) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, peso)
        .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }

    // ---------- Teclas ----------

    private fun pulsar(tecla: String) {
        if (verificando || resuelto) return
        when (tecla) {
            "C" -> buffer.setLength(0)
            "⌫" -> if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.length - 1)
            "=" -> {
                verificar()
                return
            }
            "MC" -> memoria = null
            "MS" -> CalcMotor.evaluar(buffer.toString())?.let { memoria = CalcMotor.canonico(it.valor) }
            "MR" -> memoria?.let { agregar(it) }
            "±" -> {
                val nuevo = CalcMotor.alternarSigno(buffer.toString())
                buffer.setLength(0)
                buffer.append(nuevo)
            }
            "√" -> agregar("r")
            "÷" -> agregar("/")
            "−" -> agregar("-")
            else -> agregar(tecla) // dígitos, punto, +, * y %
        }
        refrescar()
    }

    private fun agregar(texto: String) {
        if (buffer.length + texto.length <= CalcMotor.MAX_LARGO) buffer.append(texto)
    }

    private fun refrescar() {
        val b = buffer.toString()
        val principal = CalcMotor.ultimoNumero(b).ifEmpty { "0" }
        tvPrincipal.text = principal
        tvPrincipal.textSize = when {
            principal.length <= 8 -> 52f
            principal.length <= 11 -> 42f
            else -> 32f
        }
        tvExpresion.text = CalcMotor.paraMostrar(b)
        tvMemoria.text = if (memoria != null) "M" else ""
    }

    // ---------- Verificación ----------

    private fun verificar() {
        val texto = buffer.toString()
        if (texto.isEmpty()) return
        verificando = true
        val modo = store.calcModo
        // El hash es lento a propósito: va en un hilo aparte para no congelar la pantalla
        io.execute {
            val correcto = try {
                comprobar(texto, modo)
            } catch (e: Exception) {
                Log.e(TAG, "Fallo al verificar", e)
                false
            }
            runOnUiThread {
                verificando = false
                if (correcto) {
                    if (!isDestroyed) exito()
                } else {
                    fallar()
                }
            }
        }
    }

    private fun comprobar(texto: String, modo: String): Boolean {
        if (modo == SecureStore.CALC_SECUENCIA) {
            val teclas = texto.toCharArray()
            return try {
                store.verificarCalculadora(teclas)
            } finally {
                teclas.fill(NUL)
            }
        }
        val resultado = CalcMotor.evaluar(texto) ?: return false
        if (resultado.operaciones < 1) return false // un número suelto no cuenta como operación
        val canonico = CalcMotor.canonico(resultado.valor).toCharArray()
        return try {
            store.verificarCalculadora(canonico)
        } finally {
            canonico.fill(NUL)
        }
    }

    private fun exito() {
        if (resuelto) return
        resuelto = true
        if (prueba) toast(getString(R.string.s_correct)) else GuardState.marcarVerificado()
        finishAndRemoveTask()
    }

    private fun fallar() {
        if (resuelto) return
        resuelto = true
        GuardState.invalidar()
        if (prueba) {
            toast(getString(R.string.s_incorrect_in_real_use_the))
        } else {
            bloquearPantalla()
        }
        finishAndRemoveTask()
    }

    private fun salirPrueba() {
        if (resuelto) return
        resuelto = true
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

    private fun toast(mensaje: String) = Toast.makeText(applicationContext, mensaje, Toast.LENGTH_SHORT).show()

    private fun dp(valor: Int) = (valor * resources.displayMetrics.density).toInt()
}
