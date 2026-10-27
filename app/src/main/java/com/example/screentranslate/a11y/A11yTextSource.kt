package com.example.screentranslate.a11y

import com.example.screentranslate.core.TextBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ponte entre o AccessibilityService e o pipeline de traducao.
 * Publica os blocos de texto visiveis com suas posicoes na tela.
 */
object A11yTextSource {

    private val _blocks = MutableStateFlow<List<TextBlock>>(emptyList())
    val blocks: StateFlow<List<TextBlock>> = _blocks.asStateFlow()

    @Volatile
    var connected: Boolean = false
        private set

    fun setConnected(value: Boolean) {
        connected = value
        if (!value) _blocks.value = emptyList()
    }

    fun publish(blocks: List<TextBlock>) {
        if (blocks != _blocks.value) _blocks.value = blocks
    }
}