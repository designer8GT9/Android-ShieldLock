package com.shieldlock.auth

import android.app.Dialog
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Todas las pantallas de la app deben heredar de esta clase (no de AppCompatActivity).
 * Así FLAG_SECURE queda aplicado en TODA la app sin acordarte de ponerlo en cada una.
 */
abstract class SecureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Va ANTES de super.onCreate para que ni el primer fotograma se pueda capturar
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        super.onCreate(savedInstanceState)
    }
}

/**
 * Los diálogos son ventanas aparte: FLAG_SECURE de la Activity NO los cubre.
 * Úsalo en cada diálogo: MaterialAlertDialogBuilder(this)....create().secure()
 */
fun <T : Dialog> T.secure(): T {
    window?.setFlags(
        WindowManager.LayoutParams.FLAG_SECURE,
        WindowManager.LayoutParams.FLAG_SECURE
    )
    return this
}