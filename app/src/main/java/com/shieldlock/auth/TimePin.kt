package com.shieldlock.auth

import android.content.Context
import android.text.format.DateFormat
import java.util.Calendar

/**
 * La 2ª contraseña: el PIN es la hora actual, sin separador y sin ceros al inicio.
 *   1:20  -> 120      12:30 -> 1230      13:05 (24 h) -> 1305
 * Respeta el formato de 12 o 24 horas que tenga configurado el teléfono.
 */
object TimePin {

    fun pinPara(context: Context, momento: Calendar): String {
        val hora = if (DateFormat.is24HourFormat(context)) {
            momento.get(Calendar.HOUR_OF_DAY)                         // 0..23
        } else {
            momento.get(Calendar.HOUR).let { if (it == 0) 12 else it } // 1..12
        }
        // Al multiplicar y convertir a número, el 0 inicial desaparece solo
        return (hora * 100 + momento.get(Calendar.MINUTE)).toString()
    }

    /**
     * Acepta el minuto actual y el anterior: si el minuto cambia mientras la persona
     * escribe, un PIN correcto no debe fallar (y un fallo bloquea el teléfono).
     */
    fun esValido(context: Context, ingresado: CharArray): Boolean {
        val ahora = Calendar.getInstance()
        val anterior = (ahora.clone() as Calendar).apply { add(Calendar.MINUTE, -1) }
        val texto = String(ingresado)
        return texto == pinPara(context, ahora) || texto == pinPara(context, anterior)
    }
}
