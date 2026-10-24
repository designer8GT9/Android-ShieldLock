package com.shieldlock.auth

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom

/**
 * Motor de la calculadora: evalúa expresiones con BigDecimal (exacto: 0.1+0.2 da 0.3, no 0.30000000000000004)
 * y genera secuencias aleatorias. En el texto interno se usan: + - * / % . dígitos, y 'r' para la raíz cuadrada.
 */
object CalcMotor {

    const val MAX_LARGO = 24
    const val SECUENCIA_MIN = 4
    const val SECUENCIA_MAX = 16
    private const val LARGO_AZAR = 8

    /** Teclas que pueden formar parte de una secuencia secreta. */
    const val TECLAS_SECRETO = "0123456789+-*/.%"
    private const val ALFABETO_AZAR = "0123456789+-*/"

    /** valor = resultado; operaciones = cuántas operaciones se hicieron (+ - * / % y raíz). */
    class Resultado(val valor: BigDecimal, val operaciones: Int)

    /** Secuencia de 8 teclas al azar con SecureRandom. */
    fun generarSecuencia(): CharArray {
        val azar = SecureRandom()
        return CharArray(LARGO_AZAR) { ALFABETO_AZAR[azar.nextInt(ALFABETO_AZAR.length)] }
    }

    /** Forma única de escribir un resultado: 50.0 y 50.00 son siempre "50". */
    fun canonico(valor: BigDecimal): String =
        if (valor.signum() == 0) "0" else valor.stripTrailingZeros().toPlainString()

    /** Para el número objetivo que escribe el usuario. null si no es un número válido. */
    fun canonicoDeTexto(texto: String): String? {
        val limpio = texto.trim()
        if (!Regex("-?\\d{1,12}(\\.\\d{1,6})?").matches(limpio)) return null
        return canonico(BigDecimal(limpio))
    }

    /** null si la expresión no es válida (división entre cero, raíz de un negativo, teclas mal puestas...). */
    fun evaluar(expresion: String): Resultado? = try {
        Analizador(expresion).resolver()
    } catch (e: ArithmeticException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    // ---------- Para mostrar en pantalla ----------

    fun paraMostrar(buffer: String): String =
        buffer.replace('*', '×').replace('/', '÷').replace('-', '−').replace('r', '√')

    fun ultimoNumero(buffer: String): String {
        var fin = buffer.length
        while (fin > 0 && !esNumero(buffer[fin - 1])) fin--
        var ini = fin
        while (ini > 0 && esNumero(buffer[ini - 1])) ini--
        return buffer.substring(ini, fin)
    }

    /** Tecla +/-: cambia el signo del último número (si el texto termina en uno). */
    fun alternarSigno(buffer: String): String {
        if (buffer.isEmpty() || !esNumero(buffer.last())) return buffer
        var ini = buffer.length
        while (ini > 0 && esNumero(buffer[ini - 1])) ini--
        val antes = ini - 1
        val esUnario = antes >= 0 && buffer[antes] == '-' && (antes == 0 || buffer[antes - 1] in "+-*/r")
        return if (esUnario) buffer.removeRange(antes, antes + 1) else buffer.substring(0, ini) + "-" + buffer.substring(ini)
    }

    private fun esNumero(c: Char) = c in '0'..'9' || c == '.'

    // ---------- Analizador: expresión := término (+|- término)*  ----------
    private class Analizador(private val s: String) {
        private var i = 0
        private var operaciones = 0

        fun resolver(): Resultado {
            require(s.isNotEmpty())
            val v = expresion()
            require(i == s.length)
            require(v.precision() <= 60) // evita números gigantes
            return Resultado(v, operaciones)
        }

        private fun expresion(): BigDecimal {
            var v = termino()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                operaciones++
                val d = termino()
                v = if (op == '+') v.add(d) else v.subtract(d)
            }
            return v
        }

        private fun termino(): BigDecimal {
            var v = unario()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                operaciones++
                val d = unario()
                v = if (op == '*') v.multiply(d) else v.divide(d, 12, RoundingMode.HALF_UP)
            }
            return v
        }

        private fun unario(): BigDecimal {
            require(i < s.length)
            return when (s[i]) {
                '-' -> { i++; unario().negate() }
                'r' -> { i++; operaciones++; raiz(unario()) }
                else -> postfijo()
            }
        }

        private fun postfijo(): BigDecimal {
            var v = numero()
            while (i < s.length && s[i] == '%') {
                i++
                operaciones++
                v = v.movePointLeft(2)
            }
            return v
        }

        private fun numero(): BigDecimal {
            val inicio = i
            var puntos = 0
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.')) {
                if (s[i] == '.') puntos++
                i++
            }
            require(i > inicio && puntos <= 1)
            val texto = s.substring(inicio, i)
            require(texto != ".")
            return BigDecimal(texto)
        }

        private fun raiz(x: BigDecimal): BigDecimal {
            if (x.signum() < 0) throw ArithmeticException("raíz de un negativo")
            return BigDecimal.valueOf(Math.sqrt(x.toDouble())).setScale(12, RoundingMode.HALF_UP)
        }
    }
}
