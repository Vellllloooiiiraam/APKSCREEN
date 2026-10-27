package com.example.screentranslate.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log do app: aparece na tela inicial E no logcat (tag `ScreenTranslate`). */
object LogBus {

    private const val MAX_LINES = 250
    private const val TAG = "ScreenTranslate"

    private val lock = Any()
    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun log(tag: String, message: String) {
        val line = synchronized(lock) { "[${format.format(Date())}] $tag — $message" }
        Log.d(TAG, "$tag — $message")
        _lines.update { current -> (current + line).takeLast(MAX_LINES) }
    }
}