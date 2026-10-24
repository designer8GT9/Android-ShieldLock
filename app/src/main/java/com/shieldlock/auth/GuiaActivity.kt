package com.shieldlock.auth

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton

/**
 * Guía para el usuario: explica cómo funciona ShieldLock.
 * Hereda de SecureActivity, así que también bloquea capturas de pantalla.
 */
class GuiaActivity : SecureActivity() {

    private class Seccion(val titulo: String, val texto: String)

    private val secciones = listOf(
        Seccion(
            "¿Qué es ShieldLock?",
            "ShieldLock agrega capas de seguridad DESPUÉS del bloqueo de tu teléfono.\n\n" +
                "Cuando desbloqueas con tu contraseña, patrón o huella, ShieldLock tapa toda la pantalla y te pide un PIN. " +
                "Si lo configuraste, después te pide una tercera contraseña. Si alguien consigue desbloquear tu teléfono, " +
                "todavía tiene que pasar estas capas."
        ),
        Seccion(
            "Las tres contraseñas",
            "1ª — El bloqueo de tu teléfono. Es el PIN, patrón o contraseña de Android. Lo configuras en los ajustes " +
                "del sistema; ShieldLock no lo administra.\n\n" +
                "2ª — El PIN de la hora. Es la hora actual, sin dos puntos y sin ceros al inicio. " +
                "Ejemplos: 1:20 → 120. 12:30 → 1230. 13:05 en formato de 24 horas → 1305. " +
                "Cambia cada minuto y respeta si tu teléfono usa 12 o 24 horas. " +
                "También se acepta el minuto anterior, por si la hora cambia mientras escribes. " +
                "En formato de 24 horas, entre las 00:00 y las 00:59 el PIN es muy corto (por ejemplo, 00:05 → 5).\n\n" +
                "3ª — Tu contraseña propia. Es opcional y tiene de 6 a 15 caracteres. Solo tú la sabes: " +
                "si alguien conoce la app y sabe que el PIN es la hora, esta es la que lo detiene."
        ),
        Seccion(
            "Un solo error bloquea el teléfono",
            "Si te equivocas una vez, en el PIN o en la tercera contraseña, ShieldLock bloquea la pantalla. " +
                "Al volver a desbloquear tendrás que escribir todo otra vez.\n\n" +
                "La pantalla no da pistas: no dice cuál paso falló ni cuántos pasos hay."
        ),
        Seccion(
            "Canción de alerta",
            "Puedes elegir una canción de tus archivos. Suena en bucle desde que aparece la pantalla de bloqueo " +
                "y se detiene cuando aciertas todo, o cuando te equivocas (en ese caso el teléfono se bloquea)."
        ),
        Seccion(
            "PIN de recuperación",
            "Al guardar una tercera contraseña, ShieldLock genera un PIN de recuperación de 12 caracteres al azar. " +
                "Se muestra UNA sola vez: anótalo en una hoja o en otro teléfono.\n\n" +
                "Si olvidas tu tercera contraseña, escribe ese PIN en el mismo campo donde va la tercera contraseña. " +
                "No se puede volver a ver ni elegir: solo se renueva si cambias la tercera contraseña."
        ),
        Seccion(
            "Cómo empezar",
            "1. Elige un modo: estándar o máximo.\n" +
                "2. Activa los permisos de ese modo con el botón Activar de cada fila.\n" +
                "3. Elige doble o triple contraseña y toca Guardar contraseñas.\n" +
                "4. Si quieres, elige una canción de alerta.\n" +
                "5. Toca Probar pantalla de bloqueo. En la prueba, equivocarte NO bloquea el teléfono."
        ),
        Seccion(
            "Modo estándar",
            "Es el modo para la mayoría de las personas. Necesita:\n\n" +
                "• Bloqueo de pantalla: tu PIN, patrón o contraseña de Android.\n" +
                "• Administrador del dispositivo: permite bloquear la pantalla cuando hay un error.\n" +
                "• Accesibilidad: detecta si alguien intenta salir de la pantalla de bloqueo y la vuelve a abrir; " +
                "también cierra el panel de notificaciones.\n" +
                "• Superposición: permite mostrar la pantalla de bloqueo sobre otras apps.\n" +
                "• Servicio en segundo plano: una notificación que mantiene vivo el guardián.\n" +
                "• Sin optimización de batería: evita que el sistema cierre la app."
        ),
        Seccion(
            "Modo máximo (usuarios expertos)",
            "Usa Device Owner, el permiso con más poder que Android da a una app sin necesitar root.\n\n" +
                "Se puede activar de tres formas: por ADB desde una computadora (botón Copiar comando), " +
                "o desde el propio teléfono con Shizuku o con root. " +
                "El teléfono NO debe tener cuentas agregadas (Google, Samsung, Xiaomi…) ni más de un usuario.\n\n" +
                "Si tienes root verificado, puedes avanzar sin Device Owner usando el administrador normal. " +
                "El botón Quitar, en la fila de Device Owner, lo desactiva.\n\n" +
                "El modo kiosco, un bloqueo todavía más fuerte, aún no está incluido."
        ),
        Seccion(
            "Shizuku y root (opcionales)",
            "Shizuku (también sirve Shizuku+ y Shizuku Next) y root le dan a ShieldLock más poder:\n\n" +
                "• Si alguien le quita un permiso, la app se lo vuelve a dar sola.\n" +
                "• Si Android no deja abrir la pantalla de bloqueo, la app la fuerza.\n\n" +
                "Shizuku se detiene cada vez que reinicias el teléfono: hay que iniciarlo de nuevo. " +
                "Mientras esté detenido, ShieldLock sigue funcionando, pero no se puede reparar sola.\n\n" +
                "Con administrador y root, sin Device Owner, hay un interruptor experimental que oculta los botones " +
                "y gestos de navegación mientras se pide el PIN."
        ),
        Seccion(
            "Después de reiniciar el teléfono",
            "ShieldLock se reactiva solo cuando desbloqueas. En algunos teléfonos (Xiaomi, Huawei, Oppo, Vivo) " +
                "hay que activar manualmente el Inicio automático de la app en los ajustes del sistema, o el reinicio " +
                "la dejará sin activar."
        ),
        Seccion(
            "Si algo no funciona",
            "• No aparece al desbloquear: revisa que veas la notificación ShieldLock activo y que todos los " +
                "permisos estén en verde. En la pantalla principal, la línea Guardián debe decir activo.\n\n" +
                "• Olvidé mi tercera contraseña: escribe el PIN de recuperación en su lugar.\n\n" +
                "• Me quedé sin botones de navegación (modo experimental): toca Restaurar botones y gestos, " +
                "o reinicia el teléfono.\n\n" +
                "• El teléfono se bloquea solo: recuerda que un solo error en el PIN o en la tercera contraseña lo bloquea."
        ),
        Seccion(
            "Lo que ShieldLock NO hace",
            "No es imposible de saltar. Apagar el teléfono o usar el modo de recuperación puede borrar los datos del " +
                "teléfono. ShieldLock suma capas de seguridad: no sustituye el bloqueo de tu teléfono ni tus copias de seguridad.\n\n" +
                "Toda la app bloquea las capturas y grabaciones de pantalla."
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        columna.addView(texto("Guía para el Usuario", 26f, negrita = true, centrado = true))
        columna.addView(
            texto("Cómo funciona ShieldLock, paso a paso.", 15f, centrado = true, alpha = 0.7f)
                .also { (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8) }
        )

        for (seccion in secciones) {
            columna.addView(
                texto(seccion.titulo, 19f, negrita = true)
                    .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(24) }
            )
            columna.addView(
                texto(seccion.texto, 15f)
                    .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) }
            )
        }

        columna.addView(
            MaterialButton(this).apply {
                text = "Entendido"
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(32) }
        )

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(columna)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val barras = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = barras.left, top = barras.top, right = barras.right, bottom = barras.bottom)
            insets
        }
        setContentView(scroll)
    }

    private fun texto(
        contenido: String,
        tamano: Float,
        negrita: Boolean = false,
        centrado: Boolean = false,
        alpha: Float = 1f
    ): TextView = TextView(this).apply {
        text = contenido
        textSize = tamano
        this.alpha = alpha
        if (negrita) setTypeface(typeface, Typeface.BOLD)
        if (centrado) gravity = Gravity.CENTER
        setLineSpacing(0f, 1.15f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun dp(valor: Int) = (valor * resources.displayMetrics.density).toInt()
}
