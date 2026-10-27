package com.example.screentranslate.core

import java.util.Locale

data class Lang(val code: String, val label: String)

/** Idiomas suportados pelo ML Kit Translation no dispositivo. */
object Langs {

    val targets = listOf(
        Lang("pt", "Português"),
        Lang("en", "Inglês"),
        Lang("es", "Espanhol"),
        Lang("fr", "Francês"),
        Lang("de", "Alemão"),
        Lang("it", "Italiano"),
        Lang("ja", "Japonês"),
        Lang("ko", "Coreano"),
        Lang("zh", "Chinês"),
        Lang("ru", "Russo"),
        Lang("ar", "Árabe"),
        Lang("hi", "Hindi"),
        Lang("tr", "Turco"),
        Lang("th", "Tailandês")
    )

    fun label(code: String): String =
        targets.firstOrNull { it.code == code }?.label ?: code.uppercase(Locale.US)

    fun indexOf(code: String): Int =
        targets.indexOfFirst { it.code == code }.coerceAtLeast(0)
}