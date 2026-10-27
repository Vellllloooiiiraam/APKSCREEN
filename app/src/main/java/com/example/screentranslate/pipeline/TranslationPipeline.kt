package com.example.screentranslate.pipeline

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.example.screentranslate.a11y.A11yTextSource
import com.example.screentranslate.capture.ScreenCaptureService
import com.example.screentranslate.core.BlockTranslation
import com.example.screentranslate.core.Langs
import com.example.screentranslate.core.LogBus
import com.example.screentranslate.core.TextBlock
import com.example.screentranslate.ocr.OcrEngine
import com.example.screentranslate.translate.TranslationEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** De onde o texto da tela e obtido. */
enum class CaptureMode {
    /** So arvore de acessibilidade: rapido, cobre a maioria dos apps. */
    ACCESSIBILITY,

    /** Hibrido: acessibilidade primeiro, OCR quando nao ha texto acessivel. */
    HYBRID,

    /** So OCR: enxerga jogos, video, imagens e apps sem arvore util. */
    OCR
}

data class PipelineState(
    val blocks: List<BlockTranslation> = emptyList(),
    val sourceLanguage: String = "",
    val busy: Boolean = false,
    val status: String = "pronto",
    val mode: CaptureMode = CaptureMode.HYBRID
)

/**
 * Orquestra o fluxo: blocos de texto -> deteccao de idioma -> traducao paralela
 * com cache -> lista de blocos traduzidos para o overlay ancorado.
 *
 * Estrategias para a traducao parecer simultanea:
 *  - so trabalha quando o conjunto de textos E posicoes muda (assinatura);
 *  - strings repetidas na tela sao traduzidas uma unica vez (dedupe por texto);
 *  - o que ja esta em cache e emitido imediatamente, o resto vai chegando em
 *    lotes a cada ~70 ms (emissao progressiva);
 *  - modelos sao preparados antes do disparo paralelo, evitando N verificacoes.
 */
object TranslationPipeline {

    private const val PREFS = "screen_translate"
    private const val KEY_TARGET = "target_language"
    private const val KEY_AUTO = "auto_translate"
    private const val KEY_MODE = "capture_mode"

    private const val MAX_BLOCKS = 80
    private const val MAX_PARALLEL = 6
    private const val DEBOUNCE_MS = 250L
    private const val EMIT_THROTTLE_MS = 70L
    private const val OCR_COOLDOWN_MS = 900L
    private const val OCR_LOOP_INTERVAL_MS = 1500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null

    @Volatile
    var target: String = "pt"
        private set

    @Volatile
    var auto: Boolean = false
        private set

    private var watcher: Job? = null
    private var debounceJob: Job? = null
    private var workJob: Job? = null

    /** Assinatura da ultima requisicao aplicada; descarta resultados obsoletos. */
    private var currentRequest = ""
    private var lastAppliedSignature = ""

    private var a11yActive = false
    private var lastOcrAt = 0L
    private var lastOcrFrameId = -1L

    /** Ultimo conjunto de blocos utilizavel, para retraduzir ao trocar idioma/modo. */
    private var lastBlocks: List<TextBlock> = emptyList()
    private var lastEngine: String = "acessibilidade"

    /**
     * Ultimo idioma de origem identificado com confianca.
     *
     * A deteccao e feita sobre a tela inteira, mas telas dominadas por numeros e
     * abreviacoes (lista de alarmes, teclado numerico) fazem o ML Kit devolver
     * "indeterminado". Guardar o ultimo resultado bom evita que a tela inteira fique
     * sem traducao por causa de uma amostra ruim.
     */
    private var lastSourceLanguage: String? = null

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store

        target = store.getString(KEY_TARGET, "pt") ?: "pt"
        auto = store.getBoolean(KEY_AUTO, false)
        val modeName = store.getString(KEY_MODE, CaptureMode.HYBRID.name) ?: CaptureMode.HYBRID.name
        val mode = runCatching { CaptureMode.valueOf(modeName) }.getOrDefault(CaptureMode.HYBRID)

        _state.update { it.copy(mode = mode, status = "pronto") }
        startWatching()
        startOcrLoop()
        LogBus.log("Pipeline", "iniciado · destino=${Langs.label(target)} · modo=${modeName(mode)}")
    }

    // ------------------------------------------------------------------- ajustes

    fun setTarget(code: String) {
        if (code == target) return
        target = code
        prefs?.edit()?.putString(KEY_TARGET, code)?.apply()
        invalidate()
        _state.update { it.copy(blocks = emptyList(), status = "idioma destino: ${Langs.label(code)}") }
        retranslateLast()
    }

    fun setAuto(enabled: Boolean) {
        auto = enabled
        prefs?.edit()?.putBoolean(KEY_AUTO, enabled)?.apply()
        if (enabled) {
            invalidate()
            _state.update { it.copy(status = "tradução automática ligada") }
            retranslateLast()
        } else {
            debounceJob?.cancel()
            workJob?.cancel()
            _state.update { it.copy(busy = false, blocks = emptyList(), status = "tradução automática desligada") }
        }
    }

    fun setMode(mode: CaptureMode) {
        prefs?.edit()?.putString(KEY_MODE, mode.name)?.apply()
        invalidate()
        _state.update { it.copy(mode = mode, blocks = emptyList(), status = "modo: ${modeName(mode)}") }
        if (mode == CaptureMode.OCR) requestOcr(force = true) else retranslateLast()
    }

    fun modeName(mode: CaptureMode): String = when (mode) {
        CaptureMode.ACCESSIBILITY -> "somente acessibilidade"
        CaptureMode.HYBRID -> "híbrido (acessibilidade + OCR)"
        CaptureMode.OCR -> "somente OCR"
    }

    /** Botao "Traduzir agora": ignora a arvore e vai direto para o OCR. */
    fun requestOcrNow() {
        if (!ScreenCaptureService.isRunning) {
            _state.update { it.copy(status = "captura de tela desligada") }
            LogBus.log("Pipeline", "OCR manual pedido, mas a captura está desligada")
            return
        }
        requestOcr(force = true)
    }

    private fun invalidate() {
        lastAppliedSignature = ""
        currentRequest = ""
    }

    /** Retraduz o que ja estava na tela (troca de idioma/modo nao espera o proximo evento). */
    private fun retranslateLast() {
        val blocks = lastBlocks
        if (blocks.isEmpty()) return
        schedule(blocks, lastEngine)
    }

    // ------------------------------------------------------- acessibilidade

    private fun startWatching() {
        watcher?.cancel()
        watcher = scope.launch {
            A11yTextSource.blocks.collect { blocks -> onA11yBlocks(blocks) }
        }
    }

    private fun onA11yBlocks(blocks: List<TextBlock>) {
        if (!auto) return
        if (_state.value.mode == CaptureMode.OCR) return

        val usable = prepare(blocks)
        a11yActive = usable.isNotEmpty()
        if (usable.isNotEmpty()) {
            lastBlocks = usable
            lastEngine = "acessibilidade"
        }

        if (usable.isEmpty()) {
            // Híbrido: a tela não tem árvore útil -> deixa o OCR assumir.
            if (_state.value.mode == CaptureMode.HYBRID) requestOcr(force = false)
            return
        }
        schedule(usable, "acessibilidade")
    }

    // ------------------------------------------------------------------- OCR

    private fun startOcrLoop() {
        scope.launch {
            while (isActive) {
                delay(OCR_LOOP_INTERVAL_MS)
                if (!auto) continue
                val mode = _state.value.mode
                if (mode == CaptureMode.ACCESSIBILITY) continue
                // No híbrido, se a árvore de acessibilidade está funcionando, o OCR é desnecessário.
                if (mode == CaptureMode.HYBRID && a11yActive) continue
                requestOcr(force = false)
            }
        }
    }

    private fun requestOcr(force: Boolean) {
        if (!ScreenCaptureService.isRunning) {
            if (force) _state.update { it.copy(status = "captura de tela desligada") }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastOcrAt < OCR_COOLDOWN_MS) return
        lastOcrAt = now

        workJob?.cancel()
        workJob = scope.launch {
            val frame = ScreenCaptureService.grabFrame()
            if (frame == null) {
                if (force) _state.update { it.copy(busy = false, status = "nenhum frame capturado ainda") }
                return@launch
            }
            if (!force && frame.id == lastOcrFrameId) return@launch
            lastOcrFrameId = frame.id

            _state.update { it.copy(busy = true, status = "lendo tela (OCR)…") }
            val found = OcrEngine.recognize(frame.bitmap, frame.scaleX, frame.scaleY)
            val usable = prepare(found)
            if (usable.isEmpty()) {
                _state.update { it.copy(busy = false, blocks = emptyList(), status = "OCR não encontrou texto") }
                return@launch
            }
            lastBlocks = usable
            lastEngine = "OCR"
            translate(usable, "OCR")
        }
    }

    // -------------------------------------------------------------- traducao

    private fun prepare(blocks: List<TextBlock>): List<TextBlock> =
        blocks.asSequence()
            .filter { it.isUsable }
            .take(MAX_BLOCKS)
            .toList()

    /**
     * Monta a amostra usada na deteccao de idioma.
     *
     * A ordem dos blocos na arvore de acessibilidade nao tem relacao com a
     * quantidade de texto: os primeiros podem ser apenas numeros de horas de uma
     * lista de alarmes. Aqui priorizamos os blocos com mais letras, que sao os que
     * carregam sinal linguistico de verdade, e so depois juntamos os 12 melhores
     * numa amostra curta (o ML Kit so usa os primeiros caracteres).
     */
    private fun buildLanguageSample(blocks: List<TextBlock>): String =
        blocks.asSequence()
            .filter { block -> block.text.count { it.isLetter() } >= 3 }
            .sortedByDescending { block -> block.text.count { it.isLetter() } }
            .take(12)
            .joinToString(" ") { it.text }
            .take(300)

    private fun schedule(blocks: List<TextBlock>, engine: String) {
        val signature = buildSignature(engine, blocks)
        if (signature == lastAppliedSignature) return
        lastAppliedSignature = signature
        currentRequest = signature

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            translate(blocks, engine)
        }
    }

    private fun buildSignature(engine: String, blocks: List<TextBlock>): String {
        var hash = 17
        for (b in blocks) hash = hash * 31 + b.key.hashCode()
        return "$engine|${_state.value.mode}|$target|${blocks.size}|$hash"
    }

    private suspend fun translate(blocks: List<TextBlock>, engine: String) {
        val request = currentRequest
        val targetCode = target

        _state.update { it.copy(busy = true, status = "traduzindo ($engine)…") }

        // Uma unica deteccao de idioma para a tela inteira.
        //
        // Quando ela nao conclui nada (telas dominadas por numeros e abreviacoes,
        // como uma lista de alarmes), reaproveitamos o ultimo idioma identificado
        // com confianca. Sem isso a tela simplesmente ficava sem traducao nenhuma.
        val detected = TranslationEngine.detectLanguage(buildLanguageSample(blocks))
        if (detected != null) lastSourceLanguage = detected
        val source = detected ?: lastSourceLanguage ?: targetCode

        if (source == targetCode) {
            // Nao da para ficar em silencio aqui: "nao traduziu nada" e exatamente o
            // sintoma que o usuario relata, e sem esta linha nao da para saber se o
            // problema foi a deteccao de idioma ou o texto ja estar traduzido.
            val detail = when {
                detected != null -> "a tela já está em ${Langs.label(targetCode)} ($detected)"
                lastSourceLanguage != null ->
                    "a tela já está em ${Langs.label(targetCode)} ($lastSourceLanguage)"
                else -> "não consegui identificar o idioma da tela — nada a traduzir"
            }
            LogBus.log("Pipeline", "$detail — nada a traduzir")
            if (request == currentRequest) {
                _state.update {
                    it.copy(
                        busy = false,
                        blocks = emptyList(),
                        sourceLanguage = source,
                        status = "a tela já está em ${Langs.label(targetCode)}"
                    )
                }
            }
            return
        }

        val unique = LinkedHashSet<String>(blocks.size)
        blocks.forEach { unique.add(it.text) }

        val done = HashMap<String, String>(unique.size)
        unique.forEach { text ->
            TranslationEngine.cached(text, source, targetCode)?.let { done[text] = it }
        }
        emit(blocks, done, request)

        val pending = unique.filterNot { done.containsKey(it) }
        if (pending.isEmpty()) {
            if (request == currentRequest) {
                _state.update {
                    it.copy(busy = false, status = statusLine(engine, source, targetCode, blocks.size))
                }
            }
            return
        }

        // Baixa/valida o modelo uma vez, antes do disparo paralelo.
        runCatching { TranslationEngine.prepare(source, targetCode) }

        val semaphore = Semaphore(MAX_PARALLEL)
        var lastEmit = SystemClock.elapsedRealtime()

        coroutineScope {
            pending.map { text ->
                async {
                    semaphore.withPermit {
                        val output = runCatching {
                            TranslationEngine.translate(text, source, targetCode)
                        }.getOrNull() ?: return@withPermit

                        synchronized(done) { done[text] = output }

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmit >= EMIT_THROTTLE_MS) {
                            lastEmit = now
                            emit(blocks, done, request)
                        }
                    }
                }
            }.awaitAll()
        }

        if (request != currentRequest) return
        emit(blocks, done, request, final = true)
        _state.update {
            it.copy(busy = false, status = statusLine(engine, source, targetCode, done.size))
        }
        LogBus.log("Pipeline", "$engine: ${done.size} blocos únicos, ${source} → $targetCode")
    }

    /**
     * Monta os blocos ancorados a partir das traducoes prontas e publica o estado.
     *
     * [final] separa a passagem progressiva (so pinta o que ja esta pronto, o resto
     * ainda esta em voo) da passagem conclusiva. Sem essa distincao o log acusava
     * "sem tradução" para todo bloco que ainda nao havia chegado, enchendo o logcat
     * com um falso negativo a cada quadro.
     */
    private fun emit(
        blocks: List<TextBlock>,
        translations: Map<String, String>,
        request: String,
        final: Boolean = false
    ) {
        if (request != currentRequest) return
        val snapshot = synchronized(translations) { HashMap(translations) }

        val out = ArrayList<BlockTranslation>(blocks.size)
        for (block in blocks) {
            val translated = snapshot[block.text]
            if (translated == null) {
                // Sem traducao gravada: nas passagens intermediarias isso e esperado,
                // so na conclusao significa falha real do motor.
                if (final) {
                    LogBus.log("Pipeline", "sem tradução: \"${block.text.take(60)}\"")
                }
                continue
            }
            if (translated == block.text) {
                // O motor devolveu o proprio original. Nao desenhamos caixa (nao ha o
                // que trocar), mas registramos: um titulo que fica em ingles no meio de
                // um texto traduzido precisa aparecer aqui para ser investigado.
                if (final) {
                    LogBus.log("Pipeline", "igual ao original: \"${block.text.take(60)}\"")
                }
                continue
            }
            out.add(BlockTranslation.of(block, translated))
        }
        _state.update { it.copy(blocks = out) }
    }

    private fun statusLine(engine: String, source: String, target: String, count: Int) =
        "$engine · ${Langs.label(source)} → ${Langs.label(target)} · $count blocos"
}