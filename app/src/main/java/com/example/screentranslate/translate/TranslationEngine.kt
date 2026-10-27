package com.example.screentranslate.translate

import com.example.screentranslate.core.LogBus
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Traducao 100% on-device com ML Kit (modelos baixados na primeira vez por par de
 * idiomas). Sem servidor, sem chave de API, sem envio de dados.
 *
 * Como o overlay ancorado traduz varios blocos por frame, o cache e o fator mais
 * importante para a sensacao de simultaneidade: cada string e traduzida uma unica
 * vez e reaproveitada enquanto o texto nao mudar.
 */
object TranslationEngine {

    private val identifier by lazy { LanguageIdentification.getClient() }

    private val translators = HashMap<String, Translator>()
    private val readyPairs = HashSet<String>()
    private val cache = LinkedHashMap<String, String>()

    private fun pairKey(source: String, target: String) = "$source>$target"

    private fun cacheKey(source: String, target: String, text: String) =
        "${pairKey(source, target)}#${text.length}:${text.hashCode()}"

    /** Detecta o idioma (BCP-47). Null quando nao ha confianca suficiente. */
    suspend fun detectLanguage(text: String): String? = try {
        val code = identifier.identifyLanguage(text.take(200)).await()
        if (code == "und") null else code
    } catch (t: Throwable) {
        LogBus.log("Idioma", "detecção falhou: ${t.message}")
        null
    }

    /** Devolve a traducao se ja estiver em cache (sem tocar na rede/CPU). */
    fun cached(text: String, source: String, target: String): String? =
        synchronized(cache) { cache[cacheKey(source, target, text)] }

    /**
     * Garante que o modelo do par esta baixado ANTES de disparar a traducao paralela
     * dos blocos - assim as N chamadas concorrentes nao disputam a verificacao.
     */
    suspend fun prepare(source: String, target: String) {
        if (source == target) return
        synchronized(readyPairs) { if (readyPairs.contains(pairKey(source, target))) return }
        try {
            translatorFor(source, target).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            synchronized(readyPairs) { readyPairs.add(pairKey(source, target)) }
            LogBus.log("Tradução", "modelo $source>$target pronto")
        } catch (t: Throwable) {
            LogBus.log("Tradução", "falha ao baixar modelo $source>$target: ${t.message}")
            throw t
        }
    }

    suspend fun translate(text: String, source: String, target: String): String {
        if (source == target) return text
        cached(text, source, target)?.let { return it }

        val translator = translatorFor(source, target)
        val alreadyReady = synchronized(readyPairs) { readyPairs.contains(pairKey(source, target)) }
        if (!alreadyReady) {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            synchronized(readyPairs) { readyPairs.add(pairKey(source, target)) }
        }

        var output = translator.translate(text).await()

        // Eco do modelo: em rotulos curtos (sem pontuacao, no formato "titulo") o NMT
        // on-device as vezes devolve a propria entrada em vez de traduzir - foi o que
        // aconteceu com "Android version history", que ficava em ingles no meio de um
        // paragrafo todo traduzido. Nao existe uma emoldura que funcione sempre: no
        // mesmo aparelho, com a mesma entrada, ora volta traduzido, ora volta ecoado.
        // Por isso tentamos duas e ficamos com a primeira que veio DIFERENTE.
        if (output == text && text.any { it.isLetter() }) {
            val recovered = breakEcho(translator, text)
            if (recovered != null) {
                output = recovered
                LogBus.log("Tradução", "eco corrigido: \"${text.take(40)}\" -> \"${output.take(40)}\"")
            } else {
                LogBus.log("Tradução", "eco persistente (mantido): \"${text.take(40)}\"")
            }
        }

        synchronized(cache) {
            cache[cacheKey(source, target, text)] = output
            if (cache.size > 600) {
                val iterator = cache.keys.iterator()
                repeat(150) {
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
        }
        return output
    }

    // ------------------------------------------------------- antifurto de eco

    /**
     * Tenta tirar o modelo do modo "isto e um nome proprio".
     *
     * Medido no emulador (en>pt), com "Android version history":
     *   cru / MAIUSCULA / minuscula / com quebra de linha  -> ecoa
     *   com ponto final / "- " na frente / entre aspas     -> traduz
     * A primeira tentativa e o ponto, que quase nao mexe no texto; a segunda e o
     * item de lista, que existe porque a primeira pode falhar sozinha.
     *
     * Devolve null quando nenhuma emoldura funcionou (mantemos o original).
     */
    private suspend fun breakEcho(translator: Translator, text: String): String? {
        val base = text.trim()

        val dotted = runCatching { translator.translate("$base.").await() }.getOrNull()
        if (dotted != null && dotted != "$base." && dotted != base) {
            // Devolvemos sem a pontuacao que nos mesmos pusemos, a nao ser que o
            // texto original ja terminasse em ponto.
            return if (text.trimEnd().endsWith(".")) dotted else dotted.trimEnd('.')
        }

        val bulleted = runCatching { translator.translate("- $base").await() }.getOrNull()
        if (bulleted != null && bulleted != "- $base") {
            val clean = bulleted.removePrefix("- ").removePrefix("-\u00A0").trimStart()
            if (clean.isNotEmpty() && clean != base) return clean
        }

        return null
    }

    private fun translatorFor(source: String, target: String): Translator =
        synchronized(translators) {
            translators.getOrPut(pairKey(source, target)) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(target)
                        .build()
                )
            }
        }
}