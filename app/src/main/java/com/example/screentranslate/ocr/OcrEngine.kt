package com.example.screentranslate.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.screentranslate.core.LogBus
import com.example.screentranslate.core.TextBlock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

/**
 * OCR on-device (ML Kit) usado quando a arvore de acessibilidade nao tem texto.
 * Devolve blocos COM posicao, ja convertidos para pixels da tela.
 */
object OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * @param scaleX fator para converter pixels do bitmap em pixels da tela
     * @param scaleY idem no eixo vertical
     */
    suspend fun recognize(bitmap: Bitmap, scaleX: Float, scaleY: Float): List<TextBlock> = try {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val blocks = ArrayList<TextBlock>(64)

        for (textBlock in result.textBlocks) {
            for (line in textBlock.lines) {
                val box = line.boundingBox ?: continue
                val text = line.text.trim()
                if (text.isEmpty()) continue

                val rect = Rect(
                    (box.left * scaleX).roundToInt(),
                    (box.top * scaleY).roundToInt(),
                    (box.right * scaleX).roundToInt(),
                    (box.bottom * scaleY).roundToInt()
                )
                val block = TextBlock(
                    text = text,
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                    fontSizePx = (rect.height() * 0.82f).coerceIn(24f, 130f)
                )
                if (block.isUsable) blocks.add(block)
            }
        }
        blocks
    } catch (t: Throwable) {
        LogBus.log("OCR", "falha: ${t.message}")
        emptyList()
    }
}