package com.shieldlock.auth

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * El guardián. Vive en primer plano y vigila dos cosas:
 *  - Pantalla apagada: la verificación se invalida (y, si el nivel lo permite, se ocultan los botones).
 *  - Teléfono desbloqueado: abre la pantalla de bloqueo (PIN de la hora y, si existe, la tercera contraseña).
 *
 * Si Android no deja abrir la pantalla desde segundo plano y hay root, la abre con root.
 */
class GuardService : Service() {

    companion object {
        private const val TAG = "ShieldLockGuard"
        private const val CANAL = "shieldlock_guardia"
        private const val ID_NOTIFICACION = 1001
        private const val ESPERA_REFUERZO_MS = 1500L
        private const val ENFRIAMIENTO_MS = 500L
        private const val REPARACION_ENFRIAMIENTO_MS = 3000L

        @Volatile
        var activo: Boolean = false

        /** Seguro de llamar varias veces: si ya está activo, no hace nada. */
        fun iniciar(context: Context) {
            if (activo) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, GuardService::class.java))
            } catch (e: Exception) {
                // Android 12+ no deja iniciar servicios en primer plano desde segundo plano en algunos casos
                Log.w(TAG, "No se pudo iniciar el servicio", e)
            }
        }
    }

    private lateinit var store: SecureStore
    private val handler = Handler(Looper.getMainLooper())
    private var ultimoLanzamiento = 0L
    private var receptorActivo = false

    // Autorreparación de permisos (con Shizuku o root)
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val reparando = AtomicBoolean(false)
    private var ultimaReparacion = 0L
    private var observadorActivo = false

    /** Avisa al instante cuando alguien enciende o apaga un servicio de accesibilidad. */
    private val observador = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            repararPermisos()
        }
    }

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> alApagarPantalla()
                Intent.ACTION_USER_PRESENT -> {
                    mostrarBloqueo()   // primero se tapa la pantalla...
                    repararPermisos()  // ...y mientras tanto se repara lo que falte
                }
            }
        }
    }

    // ---------- Ciclo de vida ----------

    override fun onCreate() {
        super.onCreate()
        activo = true
        store = SecureStore(this)
        crearCanal()
        if (!iniciarEnPrimerPlano()) {
            stopSelf()
            return
        }
        registrarReceptor()
        registrarObservador()
        // Tras un reinicio, o si el sistema reinició el servicio con el teléfono ya desbloqueado
        mostrarBloqueo()
        repararPermisos()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (receptorActivo) {
            try {
                unregisterReceiver(receptor)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "El receptor ya estaba desregistrado", e)
            }
            receptorActivo = false
        }
        handler.removeCallbacksAndMessages(null)
        if (observadorActivo) {
            try {
                contentResolver.unregisterContentObserver(observador)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo quitar el observador", e)
            }
            observadorActivo = false
        }
        io.shutdown()
        // Si el servicio muere, que no se queden los botones ocultos sin nadie que los devuelva
        try {
            if (GestorNivel.calcular(this, store) == Nivel.MAXIMO_ADMIN_ROOT) NavigationLock.liberarAsync()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo restaurar la navegación al cerrar", e)
        }
        activo = false
        super.onDestroy()
    }

    // ---------- Primer plano ----------

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            val canal = NotificationChannel(CANAL, getString(R.string.s_protection_active), NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(canal)
        }
    }

    private fun iniciarEnPrimerPlano(): Boolean {
        val notificacion = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.s_shieldlock_active))
            .setContentText(getString(R.string.s_protecting_your_phone))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID_NOTIFICACION, notificacion, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID_NOTIFICACION, notificacion)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo pasar a primer plano", e)
            false
        }
    }

    private fun registrarReceptor() {
        try {
            val filtro = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(this, receptor, filtro, ContextCompat.RECEIVER_NOT_EXPORTED)
            receptorActivo = true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo registrar el receptor", e)
        }
    }

    // ---------- Autorreparación de permisos ----------

    private fun registrarObservador() {
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observador
            )
            observadorActivo = true
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo observar los ajustes de accesibilidad", e)
        }
    }

    /**
     * Si falta algún permiso y hay acceso con Shizuku o root, la app se lo da sola.
     * Se llama al desbloquear, al arrancar y en cuanto alguien toca la accesibilidad.
     * No retrasa la pantalla de bloqueo: se hace en segundo plano mientras la pantalla ya está tapada.
     */
    private fun repararPermisos() {
        val modo = store.modo ?: return
        if (!store.configurado) return

        val ahora = SystemClock.elapsedRealtime()
        if (ahora - ultimaReparacion < REPARACION_ENFRIAMIENTO_MS) return
        if (!reparando.compareAndSet(false, true)) return
        ultimaReparacion = ahora

        try {
            io.execute {
                try {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val esOwner = dpm.isDeviceOwnerApp(packageName)
                    if (AutoPermisos.faltantes(this, modo, esOwner).isEmpty()) return@execute

                    val informe = AutoPermisos.completar(this, store, modo, esOwner)
                    Log.i(
                        TAG,
                        "Reparación: concedidos=${informe.concedidos} fallidos=${informe.fallidos} sinAcceso=${informe.sinAcceso}"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudieron reparar los permisos", e)
                } finally {
                    reparando.set(false)
                }
            }
        } catch (e: Exception) { // el executor ya estaba cerrado
            reparando.set(false)
        }
    }

    // ---------- Reacciones ----------

    private fun alApagarPantalla() {
        GuardState.invalidar()
        if (GestorNivel.refuerzos(this, store).contains(Refuerzo.OCULTAR_NAVEGACION)) {
            NavigationLock.bloquearAsync() // así no hay hueco entre desbloquear y que aparezca el PIN
        }
    }

    private fun mostrarBloqueo() {
        if (!store.configurado || GuardState.verificado || LockActivity.visible) return
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) return // el teléfono sigue bloqueado: todavía no toca

        val ahora = SystemClock.elapsedRealtime()
        if (ahora - ultimoLanzamiento < ENFRIAMIENTO_MS) return
        ultimoLanzamiento = ahora

        try {
            startActivity(LockActivity.intent(this))
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo abrir la pantalla de bloqueo", e)
        }
        handler.postDelayed({ reforzarLanzamiento() }, ESPERA_REFUERZO_MS)
    }

    /** Android no avisa cuando bloquea abrir una pantalla desde segundo plano: se comprueba si apareció. */
    private fun reforzarLanzamiento() {
        if (GuardState.verificado || LockActivity.visible) return
        if (GestorNivel.refuerzos(this, store).contains(Refuerzo.LANZAR_CON_PRIVILEGIOS)) {
            val componente = ComponentName(this, LockActivity::class.java).flattenToString()
            // Con Shizuku o root, "am start" no está sujeto a las restricciones de segundo plano
            Thread({ Privilegios.ejecutar(this, store, "am start -n $componente") }, "ShieldLock-am-start").start()
        }
    }
}