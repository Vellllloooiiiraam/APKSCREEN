package com.example.screentranslate.core

/**
 * Um trecho de texto visivel na tela, com posicao em pixels da TELA (nao da janela).
 * Serve tanto para a arvore de acessibilidade quanto para o OCR.
 */
data class TextBlock(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val fontSizePx: Float
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** Descarta fragmentos minusculos (icones, separadores, texto cortado). */
    val isUsable: Boolean
        get() = text.length >= 2 && width >= 8 && height >= 8 && text.any { it.isLetter() }

    /** Chave estavel para deduplicar e detectar mudancas entre dois frames. */
    val key: String get() = "$text@$left,$top,$right,$bottom"
}

/** Texto de um bloco ja traduzido, pronto para ser desenhado ancorado na tela. */
data class BlockTranslation(
    val source: String,
    val translated: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val fontSizePx: Float
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    companion object {
        fun of(block: TextBlock, translated: String) = BlockTranslation(
            source = block.text,
            translated = translated,
            left = block.left,
            top = block.top,
            right = block.right,
            bottom = block.bottom,
            fontSizePx = block.fontSizePx
        )
    }
}