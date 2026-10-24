package com.shieldlock.auth

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Idioma de la app: español, inglés, portugués y chino (simplificado).
 * AppCompat guarda la elección y recrea las pantallas abiertas al cambiarla.
 */
object Idioma {

    class Opcion(val tag: String, val nombre: String)

    val OPCIONES = listOf(
        Opcion("es", "Español"),
        Opcion("en", "English"),
        Opcion("pt", "Português"),
        Opcion("zh", "中文")
    )

    fun etiquetas(): Array<String> = OPCIONES.map { it.nombre }.toTypedArray()

    fun aplicar(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /** Nombre del idioma que se está mostrando ahora. Si el teléfono usa otro idioma, la app muestra inglés. */
    fun nombreActual(context: Context): String {
        val codigo = context.resources.configuration.locales[0].language
        return (OPCIONES.firstOrNull { it.tag == codigo } ?: OPCIONES[1]).nombre
    }
}
