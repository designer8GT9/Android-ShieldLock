package com.shieldlock.auth

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

/**
 * Pantalla de configuración de ShieldLock.
 *
 * Flujo: confirmar el bloqueo del sistema (1ª contraseña) -> elegir modo -> permisos del modo
 *        -> contraseñas (doble o triple).
 *
 * Reglas de robustez que sigue este archivo:
 *  - Nada pesado en el hilo principal (los hashes PBKDF2 corren en un hilo aparte).
 *  - Ninguna contraseña viaja en String ni se guarda en texto plano; los CharArray se borran al terminar.
 *  - Toda comprobación de permisos y todo intent a ajustes está protegido con try-catch,
 *    porque muchos fabricantes (Xiaomi, Huawei, Samsung...) cambian o quitan pantallas de ajustes.
 */
class MainActivity : SecureActivity() {

    private companion object {
        const val TAG = "ShieldLock"
        const val NUL = '\u0000'
        const val GRACIA_MS = 2 * 60 * 1000L // tiempo fuera de la app antes de volver a pedir el bloqueo del sistema
        const val MAX_INTENTOS = 3           // fallos al confirmar un cambio antes de bloquear el teléfono
        const val ESTADO_AUTENTICADO = "autenticado"
        const val ESTADO_VISTO = "visto"
        const val AUTO_ESPERA_MS = 30_000L // mínimo entre dos intentos automáticos de completar permisos
    }

    private class Permiso(
        val nombre: String,
        val descripcion: String,
        val concedido: Boolean,
        val accion: (() -> Unit)?,
        val textoBoton: String? = null,
        val botonSiempre: Boolean = false // para filas que se pueden volver a verificar aunque estén en ✅
    )

    private lateinit var store: SecureStore
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    // Vistas
    private lateinit var contenido: View
    private lateinit var toggleModo: MaterialButtonToggleGroup
    private lateinit var tvDescModo: TextView
    private lateinit var cardPermisos: View
    private lateinit var tvTituloPermisos: TextView
    private lateinit var tvEstadoModo: TextView
    private lateinit var llPermisos: LinearLayout
    private lateinit var layoutAdb: View
    private lateinit var tvComandoAdb: TextView
    private lateinit var cardPasswords: View
    private lateinit var rgNivel: RadioGroup
    private lateinit var tvTerceraEstado: TextView
    private lateinit var layoutTercera: View
    private lateinit var tilTercera: TextInputLayout
    private lateinit var tilConfirmar: TextInputLayout
    private lateinit var etTercera: TextInputEditText
    private lateinit var etConfirmar: TextInputEditText
    private lateinit var btnGuardar: MaterialButton
    private lateinit var cardExtras: View
    private lateinit var tvCancion: TextView
    private lateinit var btnQuitarCancion: MaterialButton
    private lateinit var tvNivel: TextView
    private lateinit var layoutNavegacion: View
    private lateinit var swNavegacion: MaterialSwitch
    private var actualizandoSwitch = false
    private lateinit var btnAutoPermisos: MaterialButton
    private var autoViaAnterior: Privilegios.Via? = null
    private var ultimoAuto = 0L
    private lateinit var cardCalculadora: View
    private lateinit var tvCalcAviso: TextView
    private lateinit var layoutCalc: View
    private lateinit var tvCalcEstado: TextView
    private lateinit var rgCalcModo: RadioGroup
    private lateinit var rgCalcOrigen: RadioGroup
    private lateinit var tilCalcSecreto: TextInputLayout
    private lateinit var etCalcSecreto: TextInputEditText
    private lateinit var swCalcPrimero: MaterialSwitch
    private lateinit var btnCalcGuardar: MaterialButton
    private lateinit var btnCalcProbar: MaterialButton
    private lateinit var btnCalcDesactivar: MaterialButton

    // Estado
    private var autenticado = false
    private var autenticando = false
    private var ultimaVez = 0L
    private var permisosListos = false
    private var guardando = false
    private var intentosFallidos = 0
    private var receptorActivo = false
    private var dialogo: AlertDialog? = null
    private var pendienteTercera: CharArray? = null
    private var pendienteRecuperacion: CharArray? = null

    // ---------- Launchers y receptores ----------

    /** Pantalla del sistema que pide PIN, patrón o contraseña (la 1ª contraseña). */
    private val credencialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        autenticando = false
        if (resultado.resultCode == Activity.RESULT_OK) entrar() else finish()
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (!concedido) {
            toast(getString(R.string.s_without_notifications_the_system_may))
        }
        render()
    }

    /** Si se apaga la pantalla, la sesión de esta pantalla de ajustes se invalida. */
    private val pantallaApagada = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            autenticado = false
        }
    }

    /** Selector de archivos del sistema, filtrado a audio. */
    private val cancionLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) guardarCancion(uri) }

    // Shizuku (opcional): refresca la pantalla cuando se conecta, se desconecta o responde el permiso
    private val shizukuPermiso = Shizuku.OnRequestPermissionResultListener { _, _ -> refrescarDesdeShizuku() }
    private val shizukuConectado = Shizuku.OnBinderReceivedListener { refrescarDesdeShizuku() }
    private val shizukuDesconectado = Shizuku.OnBinderDeadListener { refrescarDesdeShizuku() }

    private fun refrescarDesdeShizuku() = runOnUiThread {
        if (autenticado && !isFinishing && !isDestroyed) render()
    }

    // ---------- Ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // aquí SecureActivity ya puso FLAG_SECURE
        try {
            setContentView(R.layout.activity_main)

            store = SecureStore(this)
            dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            admin = ComponentName(this, AdminReceiver::class.java)

            // Al rotar la pantalla no se vuelve a pedir el bloqueo si fue hace poco
            autenticado = savedInstanceState?.getBoolean(ESTADO_AUTENTICADO, false) ?: false
            ultimaVez = savedInstanceState?.getLong(ESTADO_VISTO, 0L) ?: 0L

            enlazarVistas()
            contenido.visibility = if (autenticado) View.VISIBLE else View.INVISIBLE
            ajustarInsets()
            limitarAncho()
            restaurarEleccion()
            configurarEventos()
            registrarShizuku()
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al iniciar", e)
            toast(getString(R.string.s_shieldlock_could_not_start))
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            ContextCompat.registerReceiver(
                this, pantallaApagada, IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receptorActivo = true
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar el receptor de pantalla apagada", e)
        }

        if (autenticando) return // la pantalla del sistema ya está abierta
        val vencido = autenticado && SystemClock.elapsedRealtime() - ultimaVez > GRACIA_MS
        if (!autenticado || vencido) {
            autenticado = false
            contenido.visibility = View.INVISIBLE // no se muestra nada hasta confirmar
            pedirCredencialDelSistema()
        }
    }

    override fun onResume() {
        super.onResume()
        if (autenticado) render() // se refresca al volver de los ajustes del sistema
    }

    override fun onStop() {
        super.onStop()
        ultimaVez = SystemClock.elapsedRealtime()
        if (receptorActivo) {
            try {
                unregisterReceiver(pantallaApagada)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receptor ya estaba desregistrado", e)
            }
            receptorActivo = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(ESTADO_AUTENTICADO, autenticado)
        outState.putLong(ESTADO_VISTO, SystemClock.elapsedRealtime())
    }

    override fun onDestroy() {
        quitarShizuku()
        dialogo?.let { if (it.isShowing) it.dismiss() }
        dialogo = null
        descartarPendientes()
        io.shutdown()
        super.onDestroy()
    }

    // ---------- Configuración inicial de la pantalla ----------

    private fun enlazarVistas() {
        contenido = findViewById(R.id.contenido)
        toggleModo = findViewById(R.id.toggleModo)
        tvDescModo = findViewById(R.id.tvDescModo)
        cardPermisos = findViewById(R.id.cardPermisos)
        tvTituloPermisos = findViewById(R.id.tvTituloPermisos)
        tvEstadoModo = findViewById(R.id.tvEstadoModo)
        llPermisos = findViewById(R.id.llPermisos)
        layoutAdb = findViewById(R.id.layoutAdb)
        tvComandoAdb = findViewById(R.id.tvComandoAdb)
        cardPasswords = findViewById(R.id.cardPasswords)
        rgNivel = findViewById(R.id.rgNivel)
        tvTerceraEstado = findViewById(R.id.tvTerceraEstado)
        layoutTercera = findViewById(R.id.layoutTercera)
        tilTercera = findViewById(R.id.tilTercera)
        tilConfirmar = findViewById(R.id.tilConfirmar)
        etTercera = findViewById(R.id.etTercera)
        etConfirmar = findViewById(R.id.etTerceraConfirmar)
        btnGuardar = findViewById(R.id.btnGuardar)
        cardExtras = findViewById(R.id.cardExtras)
        tvCancion = findViewById(R.id.tvCancion)
        btnQuitarCancion = findViewById(R.id.btnQuitarCancion)
        tvNivel = findViewById(R.id.tvNivel)
        layoutNavegacion = findViewById(R.id.layoutNavegacion)
        swNavegacion = findViewById(R.id.swNavegacion)
        btnAutoPermisos = findViewById(R.id.btnAutoPermisos)
        cardCalculadora = findViewById(R.id.cardCalculadora)
        tvCalcAviso = findViewById(R.id.tvCalcAviso)
        layoutCalc = findViewById(R.id.layoutCalc)
        tvCalcEstado = findViewById(R.id.tvCalcEstado)
        rgCalcModo = findViewById(R.id.rgCalcModo)
        rgCalcOrigen = findViewById(R.id.rgCalcOrigen)
        tilCalcSecreto = findViewById(R.id.tilCalcSecreto)
        etCalcSecreto = findViewById(R.id.etCalcSecreto)
        swCalcPrimero = findViewById(R.id.swCalcPrimero)
        btnCalcGuardar = findViewById(R.id.btnCalcGuardar)
        btnCalcProbar = findViewById(R.id.btnCalcProbar)
        btnCalcDesactivar = findViewById(R.id.btnCalcDesactivar)
    }

    /** Pantalla de borde a borde (Android 15+): evita que el contenido quede bajo la barra de estado, la de navegación o el teclado. */
    private fun ajustarInsets() {
        val raiz = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, insets ->
            val barras = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
            )
            v.updatePadding(left = barras.left, top = barras.top, right = barras.right, bottom = barras.bottom)
            insets
        }
    }

    /** En tablets y pantallas anchas el contenido no pasa de 520dp y queda centrado. */
    private fun limitarAncho() {
        val maximo = dp(520)
        if (resources.displayMetrics.widthPixels > maximo) {
            contenido.layoutParams = contenido.layoutParams.also { it.width = maximo }
        }
    }

    private fun restaurarEleccion() {
        when (store.modo) {
            SecureStore.MODO_ESTANDAR -> toggleModo.check(R.id.btnEstandar)
            SecureStore.MODO_MAXIMO -> toggleModo.check(R.id.btnMaximo)
        }
        rgNivel.check(if (store.tieneTercera()) R.id.rbTriple else R.id.rbDoble)
        actualizarVisibilidadTercera()
        restaurarCalculadora()
    }

    // Los listeners se registran DESPUÉS de restaurar, para que restaurar no dispare guardados.
    private fun configurarEventos() {
        toggleModo.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            store.modo = if (id == R.id.btnMaximo) SecureStore.MODO_MAXIMO else SecureStore.MODO_ESTANDAR
            render()
        }
        rgNivel.setOnCheckedChangeListener { _, _ -> actualizarVisibilidadTercera() }
        btnGuardar.setOnClickListener { onGuardar() }
        findViewById<MaterialButton>(R.id.btnCopiarAdb).setOnClickListener { copiarComandoAdb() }
        findViewById<MaterialButton>(R.id.btnElegirCancion).setOnClickListener { elegirCancion() }
        btnQuitarCancion.setOnClickListener { quitarCancion() }
        findViewById<MaterialButton>(R.id.btnProbar).setOnClickListener { probarBloqueo() }
        findViewById<MaterialButton>(R.id.btnDonar).setOnClickListener { mostrarDonacion() }
        btnAutoPermisos.setOnClickListener { completarPermisos(manual = true) }
        findViewById<MaterialButton>(R.id.btnGuia).setOnClickListener { lanzar(Intent(this, GuiaActivity::class.java)) }
        findViewById<MaterialButton>(R.id.btnIdioma).setOnClickListener { elegirIdioma() }
        actualizarBotonIdioma()
        rgCalcModo.setOnCheckedChangeListener { _, _ -> actualizarCamposCalc() }
        rgCalcOrigen.setOnCheckedChangeListener { _, _ -> actualizarCamposCalc() }
        btnCalcGuardar.setOnClickListener { guardarCalculadora() }
        btnCalcDesactivar.setOnClickListener { desactivarCalculadora() }
        btnCalcProbar.setOnClickListener { probarCalculadora() }
        swNavegacion.setOnCheckedChangeListener { _, marcado ->
            if (actualizandoSwitch) return@setOnCheckedChangeListener
            store.ocultarNavegacion = marcado
            if (!marcado) NavigationLock.liberarAsync() // al apagarlo, todo vuelve de inmediato
        }
        findViewById<MaterialButton>(R.id.btnRestaurarNav).setOnClickListener {
            NavigationLock.liberarAsync()
            toast(getString(R.string.s_restoring_buttons_and_gestures))
        }
    }

    private fun actualizarVisibilidadTercera() {
        layoutTercera.visibility =
            if (rgNivel.checkedRadioButtonId == R.id.rbTriple) View.VISIBLE else View.GONE
    }

    // ---------- 1ª contraseña: bloqueo del sistema ----------

    private fun pedirCredencialDelSistema() {
        if (autenticando) return
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val seguro = try { km.isDeviceSecure } catch (e: Exception) { false }

        if (!seguro) {
            // Sin bloqueo de pantalla no hay nada que confirmar: se deja entrar para que pueda configurarlo
            entrar()
            return
        }

        val intent: Intent? = try {
            km.createConfirmDeviceCredentialIntent(
                "ShieldLock",
                getString(R.string.s_confirm_your_phone_lock_to)
            )
        } catch (e: Exception) {
            null
        }

        if (intent == null) {
            toast(getString(R.string.s_your_identity_could_not_be))
            finish()
            return
        }

        autenticando = true
        try {
            credencialLauncher.launch(intent)
        } catch (e: Exception) {
            autenticando = false
            Log.e(TAG, "No se pudo abrir la confirmación del sistema", e)
            toast(getString(R.string.s_your_identity_could_not_be))
            finish()
        }
    }

    private fun entrar() {
        autenticado = true
        ultimaVez = SystemClock.elapsedRealtime()
        contenido.visibility = View.VISIBLE
        render()
        preguntarIdiomaSiHaceFalta() // la primera vez, antes de elegir el modo
    }

    // ---------- Comprobaciones de estado (nunca lanzan excepciones) ----------

    private inline fun safe(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Comprobación fallida", e)
            false
        }

    private fun tieneBloqueo() = safe {
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
    }

    private fun adminActivo() = safe { dpm.isAdminActive(admin) }

    private fun accesibilidadActiva() = safe {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            val si = info.resolveInfo?.serviceInfo
            si != null && si.packageName == packageName &&
                    si.name == GuardAccessibilityService::class.java.name
        }
    }

    private fun overlayActivo() = safe { Settings.canDrawOverlays(this) }

    private fun bateriaIgnorada() = safe {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    }

    private fun notificacionesActivas() = safe {
        Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun esDeviceOwner() = safe { dpm.isDeviceOwnerApp(packageName) }

    // ---------- Abrir pantallas de ajustes (con alternativas si el fabricante las quitó) ----------

    private fun lanzar(vararg intentos: Intent) {
        for (intento in intentos) {
            try {
                startActivity(intento)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No existe la pantalla: ${intento.action}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Sin permiso para abrir: ${intento.action}", e)
            }
        }
        toast(getString(R.string.s_i_could_not_open_that))
    }

    private fun ajustesDeLaApp() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun abrirSeguridad() = lanzar(Intent(Settings.ACTION_SECURITY_SETTINGS))

    private fun pedirAdmin() = lanzar(
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.s_needed_to_lock_the_screen)
            )
        }
    )

    private fun pedirAccesibilidad() = lanzar(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun pedirOverlay() = lanzar(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        ajustesDeLaApp()
    )

    private fun pedirBateria() = lanzar(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        ajustesDeLaApp()
    )

    private fun pedirNotificaciones() {
        if (Build.VERSION.SDK_INT < 33) return
        val permiso = Manifest.permission.POST_NOTIFICATIONS
        if (store.notificacionesPedidas && !shouldShowRequestPermissionRationale(permiso)) {
            // El usuario marcó "no volver a preguntar": el diálogo ya no aparece, hay que ir a los ajustes
            lanzar(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                ajustesDeLaApp()
            )
        } else {
            store.notificacionesPedidas = true
            notifLauncher.launch(permiso)
        }
    }

    // ---------- Canción de alerta y prueba del bloqueo ----------

    private fun elegirCancion() = ejecutarSeguro { cancionLauncher.launch(arrayOf("audio/*")) }

    private fun guardarCancion(uri: Uri) = ejecutarSeguro {
        val tipo = contentResolver.getType(uri)
        if (tipo != null && !tipo.startsWith("audio/")) {
            toast(getString(R.string.s_that_file_is_not_audio))
            return@ejecutarSeguro
        }
        try {
            // Sin esto, el acceso al archivo se pierde al cerrar la app o reiniciar el teléfono
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "No se pudo conservar el acceso al archivo", e)
            toast(getString(R.string.s_access_to_that_file_could))
            return@ejecutarSeguro
        }
        store.guardarMusica(uri.toString(), nombreDe(uri) ?: getString(R.string.s_chosen_song))
        toast(getString(R.string.s_song_saved))
        render()
    }

    private fun nombreDe(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun quitarCancion() = ejecutarSeguro {
        store.musicaUri?.let { guardada ->
            try {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(guardada), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo liberar el acceso al archivo", e)
            }
        }
        store.quitarMusica()
        toast(getString(R.string.s_song_removed))
        render()
    }

    private fun probarBloqueo() {
        if (!store.configurado) {
            toast(getString(R.string.s_save_your_passwords_first))
            return
        }
        lanzar(LockActivity.intent(this, prueba = true))
    }

    // ---------- Donaciones ----------

    /** Muestra res/drawable/dona.png en un diálogo. */
    private fun mostrarDonacion() {
        try {
            val imagen = ImageView(this).apply {
                setImageResource(R.drawable.dona)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                maxHeight = (resources.displayMetrics.heightPixels * 0.6).toInt() // que no se salga de la pantalla
                contentDescription = getString(R.string.s_donation_image)
            }
            val contenedor = FrameLayout(this).apply {
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(
                    imagen,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            val d = MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.s_support_shieldlock))
                .setView(contenedor)
                .setPositiveButton(getString(R.string.s_close), null)
                .create()
                .secure() // sin capturas, como el resto de la app
            mostrar(d)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo mostrar la imagen de donación", e)
            toast(getString(R.string.s_the_image_could_not_be))
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "La imagen de donación es demasiado grande", e)
            toast(getString(R.string.s_the_image_is_too_large))
        }
    }

    // ---------- Contraseña calculadora (4ª) ----------

    private fun restaurarCalculadora() {
        rgCalcModo.check(if (store.calcModo == SecureStore.CALC_OPERACION) R.id.rbCalcOperacion else R.id.rbCalcSecuencia)
        swCalcPrimero.isChecked = store.calcPrimero
        actualizarCamposCalc()
    }

    private fun actualizarCamposCalc() {
        val secuencia = rgCalcModo.checkedRadioButtonId == R.id.rbCalcSecuencia
        val azar = secuencia && rgCalcOrigen.checkedRadioButtonId == R.id.rbCalcAzar
        rgCalcOrigen.visibility = if (secuencia) View.VISIBLE else View.GONE
        tilCalcSecreto.visibility = if (azar) View.GONE else View.VISIBLE
        tilCalcSecreto.hint = if (secuencia) getString(R.string.s_sequence_keys_example) else getString(R.string.s_exact_result_example)
        etCalcSecreto.inputType =
            if (secuencia) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
        etCalcSecreto.text?.clear()
    }

    private fun guardarCalculadora() {
        if (guardando) return
        if (!GestorNivel.calculadoraDisponible(this, store)) {
            toast(getString(R.string.s_the_calculator_needs_device_owner))
            return
        }
        tilCalcSecreto.error = null
        val secuencia = rgCalcModo.checkedRadioButtonId == R.id.rbCalcSecuencia
        val azar = secuencia && rgCalcOrigen.checkedRadioButtonId == R.id.rbCalcAzar
        val primero = swCalcPrimero.isChecked
        val texto = etCalcSecreto.text?.toString().orEmpty().trim()

        val aplicar: () -> Unit
        if (azar) {
            aplicar = { mostrarSecuenciaGenerada(CalcMotor.generarSecuencia(), primero) }
        } else if (secuencia) {
            if (texto.length !in CalcMotor.SECUENCIA_MIN..CalcMotor.SECUENCIA_MAX ||
                texto.any { it !in CalcMotor.TECLAS_SECRETO }
            ) {
                tilCalcSecreto.error = getString(R.string.s_use_to_keys_numbers_and, CalcMotor.SECUENCIA_MIN, CalcMotor.SECUENCIA_MAX)
                return
            }
            aplicar = { confirmarGuardadoCalc(SecureStore.CALC_SECUENCIA, texto.toCharArray(), primero) }
        } else {
            val canonico = CalcMotor.canonicoDeTexto(texto)
            if (canonico == null) {
                tilCalcSecreto.error = getString(R.string.s_enter_a_valid_number_for)
                return
            }
            aplicar = { confirmarGuardadoCalc(SecureStore.CALC_OPERACION, canonico.toCharArray(), primero) }
        }

        // Cambiar la calculadora es un cambio sensible: con tercera contraseña, hay que demostrar que eres tú
        if (store.calcActiva && store.tieneTercera()) {
            pedirAutorizacion(getString(R.string.s_to_change_the_calculator_confirm)) { aplicar() }
        } else {
            aplicar()
        }
    }

    private fun confirmarGuardadoCalc(modo: String, secreto: CharArray, primero: Boolean) {
        enSegundoPlano({
            try {
                store.guardarCalculadora(modo, secreto, primero)
            } finally {
                secreto.fill(NUL)
            }
        }) { ok ->
            if (ok == true) {
                etCalcSecreto.text?.clear()
                toast(getString(R.string.s_calculator_saved_test_it_now))
            } else {
                toast(getString(R.string.s_could_not_save_try_again))
            }
            render()
        }
    }

    /** La secuencia al azar se muestra ANTES de guardar, y solo se guarda si el usuario confirma que la anotó. */
    private fun mostrarSecuenciaGenerada(secuencia: CharArray, primero: Boolean) {
        var confirmada = false
        val vista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        vista.addView(textoCentrado(getString(R.string.s_this_is_your_sequence_it), 14f))
        vista.addView(
            TextView(this).apply {
                text = secuencia.joinToString(" ")
                textSize = 26f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setTextIsSelectable(false)
                setPadding(0, dp(16), 0, dp(8))
            }
        )
        val confirmo = CheckBox(this).apply { text = getString(R.string.s_i_have_written_it_down_2) }
        vista.addView(confirmo)

        val d = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.s_calculator_sequence))
            .setView(vista)
            .setCancelable(false)
            .setNegativeButton(getString(R.string.s_cancel), null)
            .setPositiveButton(getString(R.string.s_save)) { _, _ ->
                confirmada = true
                confirmarGuardadoCalc(SecureStore.CALC_SECUENCIA, secuencia, primero)
            }
            .setOnDismissListener { if (!confirmada) secuencia.fill(NUL) }
            .create()
            .secure()
        d.setOnShowListener {
            val guardar = d.getButton(DialogInterface.BUTTON_POSITIVE)
            guardar.isEnabled = false
            confirmo.setOnCheckedChangeListener { _, marcado -> guardar.isEnabled = marcado }
        }
        mostrar(d)
    }

    private fun desactivarCalculadora() {
        val aplicar = {
            enSegundoPlano({ store.quitarCalculadora() }) {
                toast(getString(R.string.s_calculator_disabled))
                render()
            }
        }
        if (store.tieneTercera()) {
            pedirAutorizacion(getString(R.string.s_to_change_the_calculator_confirm)) { aplicar() }
        } else {
            aplicar()
        }
    }

    private fun probarCalculadora() {
        if (!store.calcActiva) {
            toast(getString(R.string.s_save_the_calculator_first))
            return
        }
        lanzar(CalculadoraActivity.intent(this, prueba = true))
    }

    // ---------- Idioma ----------

    private fun actualizarBotonIdioma() {
        findViewById<MaterialButton>(R.id.btnIdioma).text =
            getString(R.string.language_button, Idioma.nombreActual(this))
    }

    private fun elegirIdioma() {
        val d = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.language_title))
            .setItems(Idioma.etiquetas()) { _, indice -> Idioma.aplicar(Idioma.OPCIONES[indice].tag) }
            .setNegativeButton(getString(R.string.s_cancel), null)
            .create()
            .secure()
        mostrar(d)
    }

    /** La primera vez que se entra, antes de elegir el modo, se pregunta el idioma. */
    private fun preguntarIdiomaSiHaceFalta() {
        if (store.idiomaPreguntado) return
        store.idiomaPreguntado = true
        elegirIdioma()
    }

    // ---------- Permisos automáticos y Device Owner (con Shizuku o root) ----------

    /** Revisa qué permisos faltan y, con Shizuku o root, se los da la propia app. */
    private fun completarPermisos(manual: Boolean) {
        if (guardando) return
        val modo = store.modo ?: return
        val esOwner = esDeviceOwner()
        if (manual) toast(getString(R.string.s_completing_permissions))
        enSegundoPlano({ AutoPermisos.completar(this, store, modo, esOwner) }) { informe ->
            if (informe == null) {
                if (manual) toast(getString(R.string.s_the_permissions_could_not_be))
            } else if (informe.sinAcceso) {
                if (manual) toast(getString(R.string.s_there_is_no_shizuku_or))
            } else if (informe.concedidos.isNotEmpty()) {
                toast(getString(R.string.s_permissions_granted, informe.concedidos.joinToString(", ")))
            } else if (manual && informe.fallidos.isNotEmpty()) {
                toast(getString(R.string.s_could_not_grant, informe.fallidos.joinToString(", ")))
            }
            render()
        }
    }

    private fun pedirDeviceOwner() {
        if (guardando) return
        val acceso = Privilegios.via(this, store)
        if (acceso == null) {
            toast(getString(R.string.s_you_need_shizuku_with_permission))
            return
        }
        val d = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.s_enable_device_owner))
            .setMessage(
                getString(R.string.s_it_will_be_enabled_with, acceso.nombre) +
                        getString(R.string.s_the_phone_must_not_have) +
                        getString(R.string.s_if_something_goes_wrong_you)
            )
            .setNegativeButton(getString(R.string.s_cancel), null)
            .setPositiveButton(getString(R.string.s_enable)) { _, _ -> ejecutarDeviceOwner() }
            .create()
            .secure()
        mostrar(d)
    }

    private fun ejecutarDeviceOwner() {
        toast(getString(R.string.s_enabling_device_owner))
        enSegundoPlano({ DeviceOwnerHelper.activar(this, store) }) { r ->
            toast(r?.mensaje ?: getString(R.string.s_device_owner_could_not_be))
            render()
        }
    }

    private fun quitarDeviceOwner() {
        val confirmar = {
            val d = MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.s_remove_device_owner))
                .setMessage(
                    getString(R.string.s_shieldlock_will_stop_being_device) +
                            getString(R.string.s_until_you_enable_it_again)
                )
                .setNegativeButton(getString(R.string.s_cancel), null)
                .setPositiveButton(getString(R.string.s_remove)) { _, _ -> ejecutarQuitarDeviceOwner() }
                .create()
                .secure()
            mostrar(d)
        }
        // Quitar la protección es un cambio sensible: con tercera contraseña, hay que demostrar que eres tú
        if (store.tieneTercera()) {
            pedirAutorizacion(getString(R.string.s_to_remove_device_owner_confirm)) {
                confirmar()
            }
        } else {
            confirmar()
        }
    }

    private fun ejecutarQuitarDeviceOwner() {
        enSegundoPlano({ DeviceOwnerHelper.quitar(this, store) }) { r ->
            toast(r?.mensaje ?: getString(R.string.s_device_owner_could_not_be_2))
            render()
        }
    }

    // ---------- Opcionales: Shizuku (modo estándar) y root (modo máximo) ----------

    private fun registrarShizuku() {
        try {
            Shizuku.addBinderReceivedListener(shizukuConectado)
            Shizuku.addBinderDeadListener(shizukuDesconectado)
            Shizuku.addRequestPermissionResultListener(shizukuPermiso)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron registrar los listeners de Shizuku", e)
        }
    }

    private fun quitarShizuku() {
        try {
            Shizuku.removeBinderReceivedListener(shizukuConectado)
            Shizuku.removeBinderDeadListener(shizukuDesconectado)
            Shizuku.removeRequestPermissionResultListener(shizukuPermiso)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron quitar los listeners de Shizuku", e)
        }
    }

    private fun filaShizuku(): Permiso {
        val nombre = getString(R.string.s_shizuku_shizuku_optional)
        return when (ShizukuHelper.estado(this)) {
            ShizukuHelper.Estado.LISTO ->
                Permiso(nombre, getString(R.string.s_connected_and_permitted), true, null)
            ShizukuHelper.Estado.SIN_PERMISO ->
                Permiso(nombre, getString(R.string.s_shizuku_is_running_shieldlock_still), false, ::pedirShizuku, getString(R.string.s_grant_permission))
            ShizukuHelper.Estado.NO_ACTIVO ->
                Permiso(nombre, getString(R.string.s_installed_but_stopped_open_it), false, ::abrirShizuku, getString(R.string.s_open_shizuku))
            ShizukuHelper.Estado.NO_COMPATIBLE ->
                Permiso(nombre, getString(R.string.s_your_shizuku_version_is_too), false, ::descargarShizuku, getString(R.string.s_update))
            ShizukuHelper.Estado.NO_INSTALADO ->
                Permiso(nombre, getString(R.string.s_not_installed_shizuku_shizuku_or), false, ::descargarShizuku, getString(R.string.s_download))
        }
    }

    private fun pedirShizuku() {
        if (!ShizukuHelper.pedirPermiso()) {
            toast(getString(R.string.s_shizuku_blocked_the_request_grant))
            abrirShizuku()
        }
    }

    /** Abre la app de Shizuku o de Shizuku+, la que esté instalada. */
    private fun abrirShizuku() {
        val paquete = ShizukuHelper.paqueteInstalado(this)
        val intent = paquete?.let { packageManager.getLaunchIntentForPackage(it) }
        if (intent != null) lanzar(intent) else descargarShizuku()
    }

    /** Deja elegir entre Shizuku (oficial) y Shizuku+ (fork con extras). */
    private fun descargarShizuku() {
        val d = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.s_install_only_one_of_the))
            .setItems(
                arrayOf(getString(R.string.s_shizuku_official), getString(R.string.s_shizuku_fork_with_extras), getString(R.string.s_shizuku_next_new_fork))
            ) { _, indice ->
                val url = when (indice) {
                    0 -> ShizukuHelper.URL_DESCARGA
                    1 -> ShizukuHelper.URL_DESCARGA_PLUS
                    else -> ShizukuHelper.URL_DESCARGA_NEXT
                }
                lanzar(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .setNegativeButton(getString(R.string.s_cancel), null)
            .create()
            .secure()
        mostrar(d)
    }

    private fun filaRoot(): Permiso {
        val ok = store.rootVerificado
        return Permiso(
            getString(R.string.s_root_optional),
            if (ok) getString(R.string.s_verified_if_you_revoke_it)
            else getString(R.string.s_checked_when_you_tap_the),
            ok,
            ::verificarRoot,
            textoBoton = if (ok) getString(R.string.s_verify_again) else getString(R.string.s_verify),
            botonSiempre = true
        )
    }

    private fun verificarRoot() {
        if (guardando) return
        toast(getString(R.string.s_your_root_manager_will_ask))
        enSegundoPlano({ RootHelper.verificar() }) { ok ->
            store.rootVerificado = ok == true
            toast(if (ok == true) getString(R.string.s_root_verified) else getString(R.string.s_root_access_was_not_obtained))
            render()
        }
    }

    // ---------- Pantalla según el modo elegido ----------

    private fun render() {
        try {
            tvTerceraEstado.text =
                if (store.tieneTercera()) {
                    getString(R.string.s_you_already_have_a_third)
                } else {
                    getString(R.string.s_you_do_not_have_a)
                }

            val modo = store.modo
            if (modo == null) {
                permisosListos = false
                tvDescModo.text = getString(R.string.s_tap_one_of_the_two)
                cardPermisos.visibility = View.GONE
                cardPasswords.visibility = View.GONE
                cardExtras.visibility = View.GONE
                cardCalculadora.visibility = View.GONE
                return
            }

            val maximo = modo == SecureStore.MODO_MAXIMO
            val acceso = Privilegios.via(this, store) // Shizuku, root o ninguno
            val bloqueo = Permiso(
                getString(R.string.s_screen_lock_st_password),
                getString(R.string.s_the_pin_pattern_or_password),
                tieneBloqueo(), ::abrirSeguridad
            )
            val bateria = Permiso(
                getString(R.string.s_no_battery_optimization),
                getString(R.string.s_prevents_the_system_from_closing),
                bateriaIgnorada(), ::pedirBateria
            )

            val permisos: List<Permiso>
            val requeridos: List<Permiso>

            if (maximo) {
                tvTituloPermisos.text = getString(R.string.s_maximum_mode_permissions)
                tvComandoAdb.text = comandoAdb()

                val esOwner = esDeviceOwner()
                // Con root verificado se puede avanzar sin Device Owner (pero entonces hace falta administrador normal)
                val sinOwnerPorRoot = store.rootVerificado && !esOwner

                tvDescModo.text =
                    if (sinOwnerPorRoot) {
                        getString(R.string.s_with_root_you_can_continue)
                    } else {
                        getString(R.string.s_for_expert_users_uses_device)
                    }

                val owner = when {
                    esOwner -> Permiso(
                        getString(R.string.s_device_owner), getString(R.string.s_active_if_something_goes_wrong), true,
                        ::quitarDeviceOwner, textoBoton = getString(R.string.s_remove), botonSiempre = true
                    )
                    acceso != null -> Permiso(
                        getString(R.string.s_device_owner),
                        if (sinOwnerPorRoot) getString(R.string.s_not_required_because_you_verified)
                        else getString(R.string.s_can_be_enabled_without_a),
                        false, ::pedirDeviceOwner, textoBoton = getString(R.string.s_enable_with, acceso.nombre)
                    )
                    else -> Permiso(
                        getString(R.string.s_device_owner),
                        if (sinOwnerPorRoot) getString(R.string.s_not_required_because_you_verified_2)
                        else getString(R.string.s_enabled_via_adb_from_a),
                        false, null
                    )
                }
                // Aparece solo cuando se avanza por root sin Device Owner: sin este permiso no se puede bloquear la pantalla
                val adminRoot = Permiso(
                    getString(R.string.s_device_administrator),
                    getString(R.string.s_without_device_owner_this_permission),
                    adminActivo(), ::pedirAdmin
                )
                val overlay = Permiso(
                    getString(R.string.s_overlay_optional),
                    getString(R.string.s_not_needed_in_this_mode),
                    overlayActivo(), ::pedirOverlay
                )

                requeridos =
                    if (sinOwnerPorRoot) listOf(bloqueo, bateria, adminRoot) // Device Owner lo reemplaza el root
                    else listOf(bloqueo, owner, bateria)                      // overlay y root son opcionales
                permisos = listOfNotNull(
                    bloqueo, owner,
                    if (sinOwnerPorRoot) adminRoot else null,
                    bateria, overlay, filaRoot()
                )
            } else {
                tvDescModo.text = getString(R.string.s_simpler_uses_administrator_accessibility_and)
                tvTituloPermisos.text = getString(R.string.s_standard_mode_permissions)
                val adminP = Permiso(
                    getString(R.string.s_device_administrator),
                    getString(R.string.s_locks_the_screen_after_an),
                    adminActivo(), ::pedirAdmin
                )
                val acc = Permiso(
                    getString(R.string.s_accessibility),
                    getString(R.string.s_detects_when_the_phone_is),
                    accesibilidadActiva(), ::pedirAccesibilidad
                )
                val overlay = Permiso(
                    getString(R.string.s_overlay),
                    getString(R.string.s_shows_the_pin_over_other),
                    overlayActivo(), ::pedirOverlay
                )
                val notif = Permiso(
                    getString(R.string.s_background_service),
                    getString(R.string.s_notification_that_keeps_it_running),
                    notificacionesActivas(), ::pedirNotificaciones
                )
                requeridos = listOf(bloqueo, adminP, acc, overlay, notif, bateria)
                permisos = requeridos + filaShizuku() // Shizuku es opcional
            }

            val listo = requeridos.all { it.concedido }
            permisosListos = listo

            layoutAdb.visibility = if (maximo) View.VISIBLE else View.GONE
            llPermisos.removeAllViews()
            permisos.forEach { agregarPermiso(it) }
            tvEstadoModo.text = if (listo) getString(R.string.s_permissions_complete) else getString(R.string.s_permissions_missing)
            cardPermisos.visibility = View.VISIBLE
            cardPasswords.visibility = if (listo) View.VISIBLE else View.GONE
            cardExtras.visibility = if (listo) View.VISIBLE else View.GONE
            tvCancion.text = store.musicaNombre?.let { "🎵 $it" } ?: getString(R.string.s_no_song_the_alert_will)
            btnQuitarCancion.visibility = if (store.musicaUri != null) View.VISIBLE else View.GONE

            // Con acceso de Shizuku o root: si falta algún permiso, la app se lo da sola
            val faltantes = AutoPermisos.faltantes(this, modo, esDeviceOwner())
            btnAutoPermisos.visibility = if (acceso != null && faltantes.isNotEmpty()) View.VISIBLE else View.GONE
            btnAutoPermisos.text = getString(R.string.s_complete_permissions_with, acceso?.nombre ?: "Shizuku")
            if (acceso == null) {
                autoViaAnterior = null
            } else if (faltantes.isNotEmpty() && !guardando) {
                val ahora = SystemClock.elapsedRealtime()
                if (acceso != autoViaAnterior || ahora - ultimoAuto > AUTO_ESPERA_MS) {
                    autoViaAnterior = acceso
                    ultimoAuto = ahora
                    completarPermisos(manual = false)
                }
            }

            // Gestor adaptativo: qué nivel de protección hay ahora mismo
            val nivel = GestorNivel.calcular(this, store)
            val guardian = if (GuardService.activo) getString(R.string.guardian_active) else getString(R.string.guardian_off)
            tvNivel.text = getString(R.string.level_status, getString(nivel.titulo), getString(nivel.descripcion), guardian)
            layoutNavegacion.visibility = if (nivel == Nivel.MAXIMO_ADMIN_ROOT) View.VISIBLE else View.GONE
            actualizandoSwitch = true
            swNavegacion.isChecked = store.ocultarNavegacion
            actualizandoSwitch = false

            // Contraseña calculadora: exclusiva del modo máximo y solo con Device Owner
            cardCalculadora.visibility = if (maximo && listo) View.VISIBLE else View.GONE
            val calcDisponible = GestorNivel.calculadoraDisponible(this, store)
            layoutCalc.visibility = if (calcDisponible) View.VISIBLE else View.GONE
            tvCalcAviso.text = when {
                calcDisponible -> getString(R.string.s_the_calculator_is_the_th)
                nivel == Nivel.MAXIMO_ADMIN_ROOT -> getString(R.string.s_not_available_with_administrator_root)
                else -> getString(R.string.s_not_available_it_needs_device)
            }
            tvCalcEstado.text = when {
                !store.calcActiva -> getString(R.string.s_calculator_not_configured)
                store.calcModo == SecureStore.CALC_OPERACION -> getString(R.string.s_calculator_configured_operation_with_exact)
                else -> getString(R.string.s_calculator_configured_key_sequence)
            }
            btnCalcProbar.visibility = if (store.calcActiva) View.VISIBLE else View.GONE
            btnCalcDesactivar.visibility = if (store.calcActiva) View.VISIBLE else View.GONE

            // Con permisos completos y contraseñas guardadas, el guardián debe estar encendido
            if (listo && store.configurado) GuardService.iniciar(this)
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al dibujar la pantalla", e)
            toast(getString(R.string.s_an_error_occurred_while_showing))
        }
    }

    /** Fila de permiso armada desde código (sin XML extra), centrada. */
    private fun agregarPermiso(p: Permiso) {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        fila.addView(textoCentrado((if (p.concedido) "✅  " else "❌  ") + p.nombre, 16f))
        fila.addView(textoCentrado(p.descripcion, 12f, 0.7f))

        val accion = p.accion
        if ((!p.concedido || p.botonSiempre) && accion != null) {
            val boton = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = p.textoBoton ?: getString(R.string.s_enable)
                setOnClickListener { ejecutarSeguro(accion) }
            }
            fila.addView(
                boton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(6)
                }
            )
        }
        llPermisos.addView(fila)
    }

    private fun comandoAdb() = "adb shell dpm set-device-owner ${admin.flattenToString()}"

    private fun copiarComandoAdb() = ejecutarSeguro {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("adb", comandoAdb()))
        if (Build.VERSION.SDK_INT < 33) toast(getString(R.string.s_command_copied)) // Android 13+ ya muestra su propio aviso
    }

    // ---------- Guardar contraseñas ----------

    private fun onGuardar() {
        if (guardando) return
        tilTercera.error = null
        tilConfirmar.error = null

        if (!permisosListos) {
            toast(getString(R.string.s_first_complete_the_permissions_for))
            render()
            return
        }

        val quiereTriple = rgNivel.checkedRadioButtonId == R.id.rbTriple
        val nueva = leer(etTercera)
        val repetida = leer(etConfirmar)
        val coinciden = nueva.contentEquals(repetida)
        repetida.fill(NUL)

        // --- Doble contraseña ---
        if (!quiereTriple) {
            nueva.fill(NUL)
            if (store.tieneTercera()) {
                // Quitar la tercera contraseña es un cambio sensible: hay que demostrar que es el dueño
                pedirAutorizacion(getString(R.string.s_you_are_about_to_remove)) {
                    aplicarDoble()
                }
            } else {
                aplicarDoble()
            }
            return
        }

        // --- Triple contraseña ---
        if (nueva.isEmpty()) {
            if (store.tieneTercera()) {
                toast(getString(R.string.s_no_changes_your_third_password))
            } else {
                tilTercera.error =
                    getString(R.string.s_enter_a_password_of_to, SecureStore.TERCERA_MIN, SecureStore.TERCERA_MAX)
            }
            return
        }
        if (nueva.size !in SecureStore.TERCERA_MIN..SecureStore.TERCERA_MAX) {
            nueva.fill(NUL)
            tilTercera.error =
                getString(R.string.s_it_must_be_between_and, SecureStore.TERCERA_MIN, SecureStore.TERCERA_MAX)
            return
        }
        if (!coinciden) {
            nueva.fill(NUL)
            tilConfirmar.error = getString(R.string.s_the_passwords_do_not_match)
            return
        }

        descartarPendientes()
        pendienteTercera = nueva
        if (store.tieneTercera()) {
            pedirAutorizacion(
                getString(R.string.s_to_change_your_third_password)
            ) { mostrarPinRecuperacion() }
        } else {
            mostrarPinRecuperacion()
        }
    }

    private fun aplicarDoble() {
        enSegundoPlano({ store.guardarDoble() }) { ok ->
            if (ok == true) {
                etTercera.text?.clear()
                etConfirmar.text?.clear()
                GuardState.marcarVerificado()
                toast(getString(R.string.s_saved_double_password))
            } else {
                toast(getString(R.string.s_could_not_save_try_again))
            }
            render()
        }
    }

    /**
     * El PIN se muestra ANTES de guardar. La configuración solo se guarda cuando el usuario
     * confirma que lo anotó: así nunca queda una tercera contraseña activa con un PIN que nadie vio.
     */
    private fun mostrarPinRecuperacion() {
        if (pendienteTercera == null) return
        val pin = SecureStore.generarPinRecuperacion()
        pendienteRecuperacion = pin

        val vista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        vista.addView(
            textoCentrado(
                getString(R.string.s_this_pin_lets_you_in) +
                        getString(R.string.s_write_it_on_paper_or),
                14f
            )
        )
        vista.addView(
            TextView(this).apply {
                text = SecureStore.formatearPin(pin)
                textSize = 26f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setTextIsSelectable(false) // sin copiar: el portapapeles puede quedar en el historial del teclado
                setPadding(0, dp(16), 0, dp(8))
            }
        )
        val confirmo = CheckBox(this).apply { text = getString(R.string.s_i_have_written_it_down) }
        vista.addView(confirmo)

        val d = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.s_recovery_pin))
            .setView(vista)
            .setCancelable(false)
            .setNegativeButton(getString(R.string.s_cancel), null)
            .setPositiveButton(getString(R.string.s_save)) { _, _ -> confirmarGuardadoTriple() }
            .setOnDismissListener { descartarPendientes() } // si se cancela, no queda nada en memoria
            .create()
            .secure()

        d.setOnShowListener {
            val guardar = d.getButton(DialogInterface.BUTTON_POSITIVE)
            guardar.isEnabled = false
            confirmo.setOnCheckedChangeListener { _, marcado -> guardar.isEnabled = marcado }
        }
        mostrar(d)
    }

    private fun confirmarGuardadoTriple() {
        // Los arrays pasan al hilo de fondo, que los borra al terminar
        val tercera = pendienteTercera
        val recuperacion = pendienteRecuperacion
        pendienteTercera = null
        pendienteRecuperacion = null

        if (tercera == null || recuperacion == null) {
            tercera?.fill(NUL)
            recuperacion?.fill(NUL)
            toast(getString(R.string.s_something_went_wrong_try_again))
            return
        }

        enSegundoPlano({
            try {
                store.guardarTriple(tercera, recuperacion)
            } finally {
                tercera.fill(NUL)
                recuperacion.fill(NUL)
            }
        }) { ok ->
            if (ok == true) {
                etTercera.text?.clear()
                etConfirmar.text?.clear()
                GuardState.marcarVerificado()
                toast(getString(R.string.s_saved_triple_password))
            } else {
                toast(getString(R.string.s_could_not_save_try_again))
            }
            render()
        }
    }

    private fun descartarPendientes() {
        pendienteTercera?.fill(NUL)
        pendienteRecuperacion?.fill(NUL)
        pendienteTercera = null
        pendienteRecuperacion = null
    }

    // ---------- Confirmar la identidad antes de un cambio sensible ----------

    /**
     * Pide la tercera contraseña actual (o el PIN de recuperación) antes de cambiarla o quitarla.
     * Tras MAX_INTENTOS fallos bloquea el teléfono, igual que el resto de ShieldLock.
     */
    private fun pedirAutorizacion(motivo: String, alExito: () -> Unit) {
        val til = TextInputLayout(this).apply {
            hint = getString(R.string.s_third_password_or_recovery_pin)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val campo = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(20))
        }
        if (Build.VERSION.SDK_INT >= 26) campo.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        til.addView(campo)

        val contenedor = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(til)
        }

        var exito = false
        val d = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.s_confirm_it_is_you))
            .setMessage(motivo)
            .setView(contenedor)
            .setCancelable(false)
            .setNegativeButton(getString(R.string.s_cancel), null)
            .setPositiveButton(getString(R.string.s_confirm), null) // se maneja abajo para poder dejar el diálogo abierto si falla
            .setOnDismissListener { if (!exito) descartarPendientes() }
            .create()
            .secure()

        d.setOnShowListener {
            d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (guardando) return@setOnClickListener
                til.error = null
                val secreto = leer(campo)
                if (secreto.isEmpty()) {
                    til.error = getString(R.string.s_enter_your_password_or_your)
                    return@setOnClickListener
                }
                enSegundoPlano({
                    try {
                        store.verificarTerceraORecuperacion(secreto)
                    } finally {
                        secreto.fill(NUL)
                    }
                }) { ok ->
                    campo.text?.clear()
                    when (ok) {
                        true -> {
                            intentosFallidos = 0
                            exito = true
                            d.dismiss()
                            alExito()
                        }
                        false -> {
                            intentosFallidos++
                            if (intentosFallidos >= MAX_INTENTOS) {
                                intentosFallidos = 0
                                d.dismiss()
                                bloquearPantalla()
                            } else {
                                til.error = getString(R.string.s_incorrect_attempt_of, intentosFallidos, MAX_INTENTOS)
                            }
                        }
                        null -> {
                            til.error = getString(R.string.s_could_not_verify_try_again)
                        }
                    }
                }
            }
        }
        mostrar(d)
    }

    private fun bloquearPantalla() {
        autenticado = false // al volver, se vuelve a pedir el bloqueo del sistema
        try {
            if (adminActivo()) dpm.lockNow()
        } catch (e: SecurityException) {
            Log.w(TAG, "lockNow no permitido", e)
        }
    }

    // ---------- Utilidades ----------

    /** Ejecuta el trabajo en un hilo de fondo y entrega el resultado en el principal (null si falló). */
    private fun <T : Any> enSegundoPlano(trabajo: () -> T, alTerminar: (T?) -> Unit) {
        guardando = true
        btnGuardar.isEnabled = false
        try {
            io.execute {
                val resultado: T? = try {
                    trabajo()
                } catch (e: Exception) {
                    Log.e(TAG, "Fallo en segundo plano", e)
                    null
                }
                runOnUiThread {
                    guardando = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    btnGuardar.isEnabled = true
                    alTerminar(resultado)
                }
            }
        } catch (e: Exception) { // por ejemplo, el executor ya fue cerrado
            Log.e(TAG, "No se pudo lanzar la tarea", e)
            guardando = false
            btnGuardar.isEnabled = true
            alTerminar(null)
        }
    }

    private fun mostrar(d: AlertDialog) {
        if (isFinishing || isDestroyed) return
        dialogo?.let { if (it.isShowing) it.dismiss() }
        dialogo = d
        d.show()
    }

    /** Copia el texto de un campo a un CharArray para poder borrarlo de memoria después. */
    private fun leer(campo: EditText): CharArray {
        val texto = campo.text ?: return CharArray(0)
        return CharArray(texto.length) { i -> texto[i] }
    }

    private fun ejecutarSeguro(bloque: () -> Unit) {
        try {
            bloque()
        } catch (e: Exception) {
            Log.e(TAG, "Acción fallida", e)
            toast(getString(R.string.s_the_action_could_not_be))
        }
    }

    private fun textoCentrado(contenidoTexto: String, tamano: Float, opacidad: Float = 1f) =
        TextView(this).apply {
            text = contenidoTexto
            textSize = tamano
            alpha = opacidad
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun toast(mensaje: String) = Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()

    private fun dp(valor: Int) = (valor * resources.displayMetrics.density).toInt()
}