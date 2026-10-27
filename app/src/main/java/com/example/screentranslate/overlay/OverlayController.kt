package com.example.screentranslate.overlay

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.example.screentranslate.R
import com.example.screentranslate.a11y.A11yWindowHost
import com.example.screentranslate.core.BlockTranslation
import com.example.screentranslate.core.LogBus
import com.example.screentranslate.pipeline.PipelineState
import com.example.screentranslate.pipeline.TranslationPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * Traducao ANCORADA: uma janela de tela cheia, sem toque, que desenha cada traducao
 * exatamente sobre o texto original (usando as coordenadas em pixels da tela).
 *
 * A janela usa FLAG_NOT_TOUCHABLE, entao os toques atravessam e o app de baixo
 * continua utilizavel. Uma bolha arrastavel fica por cima como controle.
 */
object OverlayController {

    private val main = Handler(Looper.getMainLooper())

    /**
     * Depois de [A11Y_WAIT_MS] sem o servico de acessibilidade, seguimos assim mesmo.
     *
     * Sem essa saida o app ficaria esperando para sempre num aparelho onde o servico
     * esta desligado; e e o WindowManager da aplicacao que cria a janela comum.
     */
    private val a11yWaitTimeout = Runnable {
        if (!isShown) {
            a11yWaitElapsed = true
            LogBus.log("Overlay", "serviço de acessibilidade não apareceu — usando janela comum")
            appContext?.let { showInternal(it) }
        }
    }

    private var windowManager: WindowManager? = null

    private var anchorLayer: FrameLayout? = null
    private var anchorParams: WindowManager.LayoutParams? = null

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    /** O usuario arrastou a bolha: a partir dai nenhuma posicao automatica mexe nela. */
    private var bubbleDragged = false

    private val boxes = ArrayList<TextView>()
    private var scope: CoroutineScope? = null

    private var paintedCount = Int.MIN_VALUE
    private var paintedVisible: Boolean? = null
    private var lastDiagnosticSignature = ""

    private var screenWidth = 0
    private var screenHeight = 0

    // Origem real da camada ancorada na tela. Uma janela de acessibilidade e
    // recuada sob a status bar (~136 px), entao a camada nasce em (0,136) e nao em
    // (0,0). Guardamos esse deslocamento para posicionar cada caixa pela coordenada
    // de TELA do texto original, e nao pela coordenada interna da camada.
    private var layerOffsetX = 0
    private var layerOffsetY = 0

    private var appContext: Context? = null

    // Reconstroi as janelas quando a tela gira ou muda de tamanho.
    private val configCallback = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            main.post { if (isShown) rebuild("tela girou") }
        }

        override fun onLowMemory() = Unit
        override fun onTrimMemory(level: Int) = Unit
    }

    private var configCallbackRegistered = false

    // `show()` foi pedido mas ainda nao havia como criar a janela (servico de
    // acessibilidade reconectando). Ficamos aguardando em vez de desistir.
    private var pendingShow = false

    /**
     * Ja esperamos o servico de acessibilidade nesta exibicao. Na segunda tentativa
     * (apos [A11Y_WAIT_MS]) seguimos com o WindowManager da aplicacao, que ainda
     * funciona para uma janela TYPE_APPLICATION_OVERLAY - com a limitacao de opacidade
     * que o sistema impoe a esse tipo.
     */
    private var a11yWaitElapsed = false

    @Volatile
    var isShown: Boolean = false
        private set

    @Volatile
    var anchorsVisible: Boolean = true
        private set

    fun toggle(context: Context) {
        if (isShown) hide() else show(context)
    }

    fun show(context: Context) {
        val app = context.applicationContext
        main.post { showInternal(app) }
    }

    fun hide() {
        main.post { hideInternal() }
    }

    /** Alterna apenas as caixas de traducao, mantendo a bolha como controle. */
    fun toggleAnchors() {
        main.post {
            anchorsVisible = !anchorsVisible
            if (!anchorsVisible) hideAllBoxes()
            paintBubble(TranslationPipeline.state.value.blocks.size, force = true)
            LogBus.log("Overlay", if (anchorsVisible) "tradução na tela ligada" else "tradução na tela desligada")
        }
    }

    // --------------------------------------------------------------- montagem

    private fun showInternal(context: Context) {
        if (isShown) return
        appContext = context

        // Se o servico de acessibilidade cair e voltar (por exemplo quando outro
        // servico de acessibilidade entra em cena), o sistema remove as nossas
        // janelas junto com o token. Aqui recriamos tudo no novo token.
        A11yWindowHost.onAttached = { main.post { onA11yAttached() } }

        // Preferimos o WindowManager do servico de acessibilidade: so ele carrega o
        // token que autoriza TYPE_ACCESSIBILITY_OVERLAY. Sem o servico vinculado,
        // caimos para uma janela de sobreposicao comum, que exige a permissao de
        // sobrepor outros apps.
        val a11yWm = A11yWindowHost.windowManager
        if (a11yWm == null && !a11yWaitElapsed) {
            // Nao e um erro definitivo: logo depois de instalar/religar o app o
            // servico de acessibilidade leva alguns segundos para reconectar, e e o
            // WindowManager dele que autoriza TYPE_ACCESSIBILITY_OVERLAY.
            //
            // Esperamos SEMPRE, mesmo com a permissao de sobrepor concedida: com ela o
            // app tentava criar uma janela 2032 pelo WindowManager da aplicacao, levava
            // BadTokenException e caia para TYPE_APPLICATION_OVERLAY - que o sistema
            // limita a 80% de opacidade, deixando o texto original transparecer.
            pendingShow = true
            LogBus.log("Overlay", "aguardando o serviço de acessibilidade para criar o overlay")
            main.postDelayed(a11yWaitTimeout, A11Y_WAIT_MS)
            return
        }
        pendingShow = false
        main.removeCallbacks(a11yWaitTimeout)

        if (!configCallbackRegistered) {
            context.registerComponentCallbacks(configCallback)
            configCallbackRegistered = true
        }

        val wm = a11yWm ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        windowManager = wm
        val size = screenSize(wm)
        screenWidth = size.x
        screenHeight = size.y

        // --- camada ancorada: tela cheia, sem toque, alinhada ao sistema de coordenadas da tela
        //
        // Tipo ACCESSIBILITY_OVERLAY quando ha servico de acessibilidade, e nao
        // APPLICATION_OVERLAY: o sistema limita a opacidade de uma janela
        // TYPE_APPLICATION_OVERLAY "nao tocavel" e nao confiavel a
        // LayoutParams.MAX_OBSCURING_OPACITY = 0.8, justamente para que o usuario
        // ainda enxergue a tela por baixo de um overlay que nao recebe toque
        // (documentado em FLAG_NOT_TOUCHABLE). Era isso que fazia o texto original
        // transparecer sob a traducao (dumpsys: "mAttrs={... alpha=0.8}").
        // Overlays de servico de acessibilidade sao confiaveis: sem esse limite.
        val overlayType = if (a11yWm != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

        val layer = FrameLayout(context)
        anchorLayer = layer
        anchorParams = WindowManager.LayoutParams(
            screenWidth,
            screenHeight,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 1f
            // Uma janela de acessibilidade recua por padrao sob as barras do sistema
            // (status bar ~136 px), o que deslocaria TODAS as caixas para baixo.
            // Zerar os insets mantem a origem da camada em (0,0), e aí as
            // coordenadas de tela da acessibilidade valem direto.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }

        // --- bolha de controle, adicionada DEPOIS para ficar por cima da camada
        val params = WindowManager.LayoutParams(
            dp(context, BUBBLE_DP),
            dp(context, BUBBLE_DP),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Encostada na borda direita, logo abaixo da barra de status: e a faixa
            // com menos texto traduzido na maioria dos apps.
            x = screenWidth - dp(context, BUBBLE_DP) - dp(context, 8)
            y = dp(context, 8)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
        bubbleParams = params

        val bubbleView = TextView(context).apply {
            text = context.getString(R.string.overlay_bubble_text)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            isClickable = true
        }
        bubble = bubbleView
        paintBubble(0)
        attachBubbleTouch(bubbleView, params)

        addOverlayWindow(wm, layer, anchorParams!!, "camada ancorada")
        // A bolha herda o tipo que a camada conseguiu: as duas precisam ser da
        // mesma familia para a bolha continuar acima da camada.
        params.type = anchorParams!!.type
        addOverlayWindow(wm, bubbleView, params, "bolha")

        // A origem REAL da camada: e o valor que confirma se ela cobre a tela toda.
        // Se nao for (0,0), as caixas nascem deslocadas em relacao ao texto original.
        layer.post {
            updateLayerOffset()
            LogBus.log("Overlay", "origem da camada $layerOffsetX,$layerOffsetY · vista ${layer.width}x${layer.height}")
            // O primeiro render pode ter acontecido ANTES deste layout, com offset
            // (0,0). Repintamos com o offset ja correto para as caixas caírem no lugar.
            render(TranslationPipeline.state.value)
        }

        isShown = true
        anchorsVisible = true
        startObserving()
        LogBus.log("Overlay", "camada ${screenWidth}x$screenHeight px · densidade ${context.resources.displayMetrics.density}")
    }

    /**
     * Adiciona uma janela de overlay e registra o resultado.
     *
     * Nao usamos `runCatching` mudo aqui: se o sistema recusar o tipo (por exemplo,
     * `TYPE_ACCESSIBILITY_OVERLAY` exige um servico de acessibilidade ativo),
     * precisamos saber. Nesse caso caimos para `TYPE_APPLICATION_OVERLAY`, que
     * funciona sempre - com a ressalva de que o sistema pode limitar a opacidade a
     * 0.8 por ser uma janela que nao recebe toque.
     */
    private fun addOverlayWindow(
        wm: WindowManager,
        view: View,
        params: WindowManager.LayoutParams,
        label: String
    ) {
        try {
            wm.addView(view, params)
            LogBus.log("Overlay", "$label adicionada · tipo ${params.type} · alpha ${params.alpha}")
            return
        } catch (t: Throwable) {
            LogBus.log("Overlay", "$label recusada no tipo ${params.type}: ${t.javaClass.simpleName}: ${t.message}")
        }

        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        try {
            wm.addView(view, params)
            LogBus.log("Overlay", "$label adicionada no fallback · tipo ${params.type}")
        } catch (t: Throwable) {
            LogBus.log("Overlay", "$label FALHOU: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // --------------------------------------------------------------- render

    private fun render(state: PipelineState) {
        if (!isShown) return
        updateLayerOffset()
        paintBubble(state.blocks.size)

        if (!anchorsVisible) {
            hideAllBoxes()
            return
        }

        val blocks = state.blocks
        // `logDiagnostics` so registra quando o conjunto de blocos muda; o retorno diz
        // se ESTA renderizacao e a primeira desse conteudo, o que tambem controla o
        // log de conferencia (antes era so a primeira caixa, o que escondia defeitos
        // em qualquer outro bloco - foi assim que o paragrafo cortado passou batido).
        val firstOfContent = logDiagnostics(blocks)

        var used = 0
        for (block in blocks) {
            val box = obtainBox(used) ?: break
            bindBox(box, block, rightLimit(box.context, block, blocks), debug = firstOfContent)
            used++
        }
        for (i in used until boxes.size) {
            if (boxes[i].visibility != View.GONE) boxes[i].visibility = View.GONE
        }

        if (firstOfContent) placeBubble(blocks)
    }

    /**
     * Escolhe onde a bolha de controle atrapalha menos.
     *
     * A bolha e uma janela ACIMA da camada, entao tudo o que ela cobre fica
     * escondido. Com um canto fixo isso funcionava ate a pagina encher a tela de
     * texto - no artigo da Wikipedia ela caiu no meio do primeiro paragrafo, que
     * parecia "cortado" e na verdade estava por baixo dela. Aqui medimos quanta
     * area de texto cada posicao candidata cobriria e ficamos com a menor.
     *
     * Depois que o usuario arrasta a bolha, nao mexemos mais: a escolha dele vale.
     */
    private fun placeBubble(blocks: List<BlockTranslation>) {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        if (bubbleDragged || blocks.isEmpty()) return

        val size = dp(view.context, BUBBLE_DP)
        val margin = dp(view.context, 8)
        val maxX = (screenWidth - size - margin).coerceAtLeast(margin)
        // A janela ja nasce abaixo da barra de status (e o mesmo recuo que
        // `layerOffsetY` mede), e o rodape fica livre para nao cair na barra de gestos.
        val maxY = (screenHeight - layerOffsetY - size - margin).coerceAtLeast(margin)
        val middleY = (margin + maxY) / 2

        val spots = listOf(
            Point(maxX, margin),        // topo-direita
            Point(margin, margin),      // topo-esquerda
            Point(maxX, maxY),          // rodape-direita
            Point(margin, maxY),        // rodape-esquerda
            Point(maxX, middleY),       // meio-direita
            Point(margin, middleY)      // meio-esquerda
        )

        var best = spots.first()
        var bestScore = Long.MAX_VALUE
        for (spot in spots) {
            val score = coveredArea(spot, size, blocks)
            if (score < bestScore) {
                bestScore = score
                best = spot
            }
        }

        // Histerese: so mexemos na bolha se onde ela esta realmente atrapalhar E
        // existir um lugar bem melhor. Sem isso, rolar uma pagina (que troca o
        // conjunto de blocos a cada quadro) fazia a bolha pular de canto em canto.
        val currentScore = coveredArea(Point(params.x, params.y), size, blocks)
        if (currentScore <= BUBBLE_OK_AREA) return
        if (bestScore > currentScore / 2) return

        if (best.x == params.x && best.y == params.y) return
        params.x = best.x
        params.y = best.y
        runCatching { windowManager?.updateViewLayout(view, params) }
        LogBus.log("Overlay", "bolha em ${best.x},${best.y} · area de texto coberta ${bestScore}px²")
    }

    /**
     * Area (em px²) dos blocos que a bolha cobriria se ficasse em `spot`.
     *
     * A posicao da bolha e coordenada de JANELA; os blocos vem em coordenada de
     * TELA. Somamos o mesmo recuo que a camada sofre ([layerOffsetX]/[layerOffsetY])
     * para comparar as duas no mesmo sistema.
     */
    private fun coveredArea(spot: Point, size: Int, blocks: List<BlockTranslation>): Long {
        val left = spot.x + layerOffsetX
        val top = spot.y + layerOffsetY
        val right = left + size
        val bottom = top + size
        var total = 0L
        for (block in blocks) {
            val ox = minOf(block.right, right) - maxOf(block.left, left)
            val oy = minOf(block.bottom, bottom) - maxOf(block.top, top)
            if (ox > 0 && oy > 0) total += ox.toLong() * oy.toLong()
        }
        return total
    }

    /**
     * Registra uma amostra das coordenadas recebidas. E o primeiro lugar a olhar se
     * as caixas aparecerem deslocadas: aqui estao os pixels que o pipeline mandou.
     */
    private fun logDiagnostics(blocks: List<BlockTranslation>): Boolean {
        if (blocks.isEmpty()) return false
        val first = blocks.first()
        val signature = "${blocks.size}|${first.left},${first.top},${first.right},${first.bottom}"
        if (signature == lastDiagnosticSignature) return false
        lastDiagnosticSignature = signature

        // Inclui o texto original: e o que permite casar cada caixa com o no real
        // da arvore de acessibilidade (via `uiautomator dump`) e medir o desvio.
        // Registrado em blocos de 6 para nao truncar o logcat quando a tela tem
        // muitos textos.
        blocks.chunked(6).forEachIndexed { index, chunk ->
            val sample = chunk.joinToString(" | ") {
                "\"${it.source.take(24)}\" (${it.left},${it.top})-(${it.right},${it.bottom}) f=${it.fontSizePx.toInt()}"
            }
            val tag = if (index == 0) "${blocks.size} caixas · tela ${screenWidth}x$screenHeight" else "  ·"
            LogBus.log("Overlay", "$tag · $sample")
        }
        return true
    }

    private fun obtainBox(index: Int): TextView? {
        val layer = anchorLayer ?: return null
        if (index < boxes.size) return boxes[index]
        if (index >= MAX_BOXES) return null

        val context = layer.context
        val box = TextView(context).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(dp(context, 3), dp(context, 1), dp(context, 3), dp(context, 1))
            background = roundRect(
                dp(context, 5),
                ANCHOR_FILL,
                ANCHOR_STROKE,
                dp(context, 1)
            )
            visibility = View.GONE
        }
        layer.addView(box, FrameLayout.LayoutParams(0, 0))
        boxes.add(box)
        return box
    }

    /**
     * Limite direito (em pixels de TELA) que uma caixa pode ocupar sem invadir
     * outro trecho de texto traduzido.
     *
     * A traducao costuma ser mais longa que o original ("Stopwatch" ->
     * "Cronometro"). Sem esse limite, ou a caixa corta a palavra, ou cobre o texto
     * vizinho na barra de navegacao.
     */
    private fun rightLimit(context: Context, block: BlockTranslation, all: List<BlockTranslation>): Int {
        val gap = dp(context, 6)
        var limit = screenWidth - dp(context, 6)
        for (other in all) {
            if (other === block) continue
            // So limita quem comeca de fato a direita e divide a mesma faixa
            // vertical.
            //
            // A comparacao e contra `block.left`, NAO contra `block.right`: um
            // vizinho que comeca alguns pixels DENTRO do retangulo deste bloco
            // (a WebView encosta os botoes da barra de ferramentas) era
            // ignorado, a caixa crescia por cima dele e o texto saia cortado
            // ("Salvar" virava "Salv" sob "Ver fonte").
            if (other.left <= block.left) continue
            if (other.bottom <= block.top || other.top >= block.bottom) continue
            val candidate = other.left - gap
            if (candidate < limit) limit = candidate
        }
        return limit
    }

    private fun bindBox(
        box: TextView,
        block: BlockTranslation,
        rightEdge: Int,
        debug: Boolean = false
    ) {
        val context = box.context

        val originalWidth = block.width.coerceAtLeast(dp(context, 20))
        val availableHeight = block.height.coerceAtLeast(dp(context, 14))

        // A caixa tem padding (3dp horizontal, 1dp vertical): o texto so pode usar
        // o espaco interno, senao encosta na borda e vira reticencia.
        val padH = dp(context, 3) * 2
        val padV = dp(context, 1) * 2
        // Piso da fonte. Vale para rotulos estreitos (barra de ferramentas): sem um
        // piso baixo a medida "desiste" e o texto quebra em colunas de uma letra
        // ("Pesquisar" -> "Pesq/u/r"). Mantem-se legivel porque e o ultimo recurso.
        val minPx = dp(context, 6).toFloat()
        val maxPx = dp(context, 44).toFloat()

        // Enquanto medimos, a caixa nao pode cortar nada: `maxLines` ilimitado e sem
        // reticencias. O corte e decidido no fim, com o numero real de linhas.
        box.maxLines = Int.MAX_VALUE
        box.ellipsize = null

        // 1. Fonte de partida: a lida da arvore, limitada pelo que o texto ORIGINAL
        //    ocupa de fato no proprio retangulo.
        //
        //    A leitura da arvore nao e confiavel: em WebView o
        //    `extraRenderingInfo.textSizeInPx` vem inflado (um paragrafo de 130 px de
        //    altura reportou fonte 196). Medimos o layout real, com uma folga
        //    ([SOURCE_FIT_TOLERANCE]) porque o retangulo do no nem sempre abraca a
        //    caixa de texto inteira.
        var fontSize = block.fontSizePx.coerceIn(minPx, maxPx)
        fontSize = min(
            fontSize,
            fitInBox(
                box,
                block.source,
                (originalWidth - padH).coerceAtLeast(dp(context, 8)),
                ((availableHeight - padV) * SOURCE_FIT_TOLERANCE).toInt()
                    .coerceAtLeast(dp(context, 6)),
                minPx,
                maxPx
            )
        )

        // 2. A caixa pode crescer para a direita para caber a traducao, mas nunca
        //    invade o proximo trecho de texto nem sai da tela.
        val maxWidth = (rightEdge - block.left).coerceAtLeast(dp(context, 20))
        val estimated = (block.translated.length * fontSize * CHAR_WIDTH_FACTOR).toInt()
        val width = min(maxWidth, maxOf(originalWidth, estimated + padH))
            .coerceAtLeast(dp(context, 20))

        // 3. Fonte final: medida real do texto TRADUZIDO dentro da largura final.
        //
        //    O portugues costuma ser mais longo que o ingles; sem esta segunda
        //    medicao a traducao era cortada ("Salv...", "Mais op...", o paragrafo
        //    "sistema operacional mov...").
        fontSize = min(
            fontSize,
            fitInBox(box, block.translated, width - padH, availableHeight - padV, minPx, fontSize)
        )

        // 4. Numero de linhas do layout real nesta fonte.
        //
        //    Com `maxLines` menor que o real o TextView corta a ultima linha
        //    ("Temporizad..." no rodape do Clock); com o numero exato nada e perdido.
        box.text = block.translated
        box.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
        box.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST)
        )
        val maxLines = maxOf(1, box.layout?.lineCount ?: 1)
        box.maxLines = maxLines
        box.ellipsize = TextUtils.TruncateAt.END

        val lp = box.layoutParams as FrameLayout.LayoutParams
        lp.width = width
        // Altura = a do retangulo do no original, nao WRAP_CONTENT.
        //
        // O no de acessibilidade engloba padding acima/abaixo dos glifos, entao
        // uma caixa que "abraça" o texto da traducao fica no TOPO do retangulo e
        // a traducao aparece deslocada para cima, deixando o original visivel
        // embaixo. Cobrindo o retangulo inteiro, o original fica escondido e o
        // texto centralizado (gravity CENTER_VERTICAL) cai onde ele estava.
        lp.height = availableHeight
        lp.leftMargin = block.left - layerOffsetX - dp(context, 1)
        lp.topMargin = block.top - layerOffsetY - dp(context, 1)
        box.layoutParams = lp

        if (box.visibility != View.VISIBLE) box.visibility = View.VISIBLE

        // Conferencia objetiva: compara a coordenada que o pipeline mandou com a
        // posicao real da caixa na tela. Se divergirem, o deslocamento da camada nao
        // esta sendo compensado.
        if (debug) {
            box.post {
                val loc = IntArray(2)
                box.getLocationOnScreen(loc)
                LogBus.log(
                    "Overlay",
                    "conferência: bloco (${block.left},${block.top})-(${block.right},${block.bottom}) " +
                        "· offset camada ${layerOffsetX},${layerOffsetY} " +
                        "· margem ${lp.leftMargin},${lp.topMargin} · caixa real ${loc[0]},${loc[1]} " +
                        "· largura $width altura $availableHeight · fonte ${fontSize.toInt()}px " +
                        "· linhas $maxLines · \"${block.translated.take(24)}\""
                )
            }
        }
    }

    private fun hideAllBoxes() {        for (box in boxes) {
            if (box.visibility != View.GONE) box.visibility = View.GONE
        }
    }

    private fun paintBubble(blockCount: Int, force: Boolean = false) {
        val view = bubble ?: return
        if (!force && blockCount == paintedCount && anchorsVisible == paintedVisible) return
        paintedCount = blockCount
        paintedVisible = anchorsVisible

        view.text = if (blockCount > 0) {
            blockCount.toString()
        } else {
            view.context.getString(R.string.overlay_bubble_text)
        }
        val fill = if (anchorsVisible) BUBBLE_ON else BUBBLE_OFF
        view.background = circle(dp(view.context, 1), fill, BUBBLE_STROKE)
        // A bolha e uma janela ACIMA da camada de traducao: tudo o que ela cobre
        // (um paragrafo inteiro, se cair no meio da pagina) fica escondido - foi
        // exatamente o que aconteceu com o primeiro paragrafo do artigo, que parecia
        // "cortado" e na verdade estava por baixo dela. Semitransparente, o texto
        // continua legivel por baixo; opaca so enquanto o dedo esta nela.
        view.alpha = restingAlpha()
    }

    /** Opacidade da bolha em repouso: mais discreta quando ha caixas por baixo. */
    private fun restingAlpha(): Float = if (anchorsVisible) BUBBLE_ALPHA_ON else BUBBLE_ALPHA_OFF

    // --------------------------------------------------------------- interacao

    private fun attachBubbleTouch(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false

        val longPress = Runnable { hide() }

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    v.alpha = 1f
                    v.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(v.context, 6) || abs(dy) > dp(v.context, 6)) {
                        if (!dragging) {
                            dragging = true
                            bubbleDragged = true
                            v.removeCallbacks(longPress)
                        }
                    }
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { windowManager?.updateViewLayout(v, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    v.alpha = restingAlpha()
                    if (!dragging) toggleAnchors()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    v.alpha = restingAlpha()
                    true
                }

                else -> false
            }
        }
    }

    // --------------------------------------------------------------- ciclo de vida

    private fun startObserving() {
        scope?.cancel()
        val newScope = CoroutineScope(Dispatchers.Main.immediate)
        scope = newScope
        newScope.launch {
            TranslationPipeline.state.collect { render(it) }
        }
    }

    private fun hideInternal() {
        val wm = windowManager
        bubble?.let { runCatching { wm?.removeView(it) } }
        anchorLayer?.let { runCatching { wm?.removeView(it) } }

        bubble = null
        bubbleParams = null
        bubbleDragged = false
        a11yWaitElapsed = false
        main.removeCallbacks(a11yWaitTimeout)
        anchorLayer = null
        anchorParams = null
        boxes.clear()
        paintedCount = Int.MIN_VALUE
        paintedVisible = null

        scope?.cancel()
        scope = null
        windowManager = null
        isShown = false
        pendingShow = false
        anchorsVisible = true
        if (configCallbackRegistered) {
            appContext?.unregisterComponentCallbacks(configCallback)
            configCallbackRegistered = false
        }
        LogBus.log("Overlay", "overlay removido")
    }

    // --------------------------------------------------------------- utilidades

    /**
     * Recria as janelas depois de o servico de acessibilidade reconectar.
     *
     * O sistema remove as janelas de acessibilidade quando o token do servico cai,
     * entao sem isso a traducao simplesmente desaparece da tela e nao volta.
     */
    private fun recreateAfterReconnect() {
        rebuild("acessibilidade reconectada")
    }

    /**
     * Remove e recria as janelas.
     *
     * Dois motivos: o servico de acessibilidade reconectou (o token antigo morreu e
     * o sistema levou as janelas junto) ou a tela girou/mudou de tamanho (as janelas
     * tem tamanho explicito em pixels e nao acompanham a rotacao - o MainActivity
     * declara `configChanges`, entao nao e recriada).
     */
    private fun rebuild(reason: String) {
        val context = appContext ?: return
        if (!isShown) return
        LogBus.log("Overlay", "$reason — recriando as janelas")
        hideInternal()
        showInternal(context)
    }

    /**
     * O servico de acessibilidade (re)conectou.
     *
     * Dois casos: (1) o overlay ja estava na tela e o sistema removeu as janelas
     * junto com o token antigo -> recria; (2) o `show()` foi pedido enquanto o
     * servico ainda subia -> mostra agora.
     */
    private fun onA11yAttached() {
        if (isShown) {
            recreateAfterReconnect()
            return
        }
        if (!pendingShow) return
        val context = appContext ?: return
        showInternal(context)
    }

    /**
     * Le a posicao real da camada na tela e guarda o deslocamento.
     *
     * A janela do overlay de acessibilidade e colocada abaixo da status bar, entao a
     * camada fica em (0, 136) em vez de (0, 0). Sem compensar isso, TODA caixa
     * aparece ~136 px abaixo do texto que deveria cobrir.
     */
    private fun updateLayerOffset() {
        val layer = anchorLayer ?: return
        // Enquanto a camada nao foi medida, `getLocationOnScreen` devolve (0,0) e
        // gravar isso zeraria a compensacao (todas as caixas desceriam ~136 px). So
        // aceitamos a leitura depois do primeiro layout.
        if (layer.height == 0 || layer.width == 0) return
        val loc = IntArray(2)
        layer.getLocationOnScreen(loc)
        layerOffsetX = loc[0]
        layerOffsetY = loc[1]
    }

    /**
     * Maior tamanho de fonte com que [text] cabe numa caixa de [widthPx] de largura
     * e [heightPx] de altura de texto.
     *
     * Mede a PROPRIA [TextView] (`measure` + `layout`), nao uma copia do `Paint`:
     * e o unico jeito de bater exatamente com o que sera desenhado, incluindo
     * tipografia, padding e quebra de palavra. Uma copia do `Paint` errava por
     * poucos pixels nos casos-limite e o texto saia cortado.
     *
     * A checagem de altura NAO basta: o Android quebra uma palavra maior que a
     * largura no meio dos caracteres, entao a caixa continua com a altura certa e
     * o defeito passa despercebido ("Pesquisar" virava "Pesq"/"uisar"). Por isso
     * alem de [longestLine] exigimos que a maior PALAVRA caiba ([longestWord]): e
     * o unico jeito de impedir a quebra no meio.
     *
     * Desce a fonte em passos de 6% ate o texto caber, com um limite de iteracoes
     * para nunca travar. Deixa a caixa com o texto e a fonte escolhidos.
     */
    private fun fitInBox(
        box: TextView,
        text: String,
        widthPx: Int,
        heightPx: Int,
        minPx: Float,
        maxPx: Float
    ): Float {
        if (text.isEmpty() || widthPx <= 0 || heightPx <= 0 || maxPx <= minPx) return maxPx

        val specWidth = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val specHeight = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST)
        box.text = text
        box.maxLines = Int.MAX_VALUE
        box.ellipsize = null

        var size = maxPx
        repeat(MAX_FIT_STEPS) {
            box.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            box.measure(specWidth, specHeight)
            val layout = box.layout ?: return size
            if (layout.height <= heightPx &&
                longestLine(layout) <= widthPx &&
                longestWord(box.paint, text) <= widthPx
            ) {
                return size
            }
            if (size <= minPx) return minPx
            size = maxOf(minPx, size * FIT_STEP)
        }
        return minPx
    }

    /**
     * Largura da linha mais larga do layout. Uma palavra que nao cabe na largura
     * nao quebra - transborda - entao a altura sozinha nao denuncia o problema.
     */
    private fun longestLine(layout: android.text.Layout): Float {
        var widest = 0f
        for (i in 0 until layout.lineCount) {
            widest = maxOf(widest, layout.getLineWidth(i))
        }
        return widest
    }

    /**
     * Largura da maior palavra de [text] na fonte atual do [paint].
     *
     * E a trava contra a quebra no meio da palavra. O Android nao transborda uma
     * palavra longa: parte no meio dos caracteres ("Salvar" -> "Sal"/"var"), e as
     * duas metades ficam menores que a caixa, entao [longestLine] aprova. Exigindo
     * que a palavra inteira caiba, a fonte desce ate "Salvar" caber numa linha so.
     */
    private fun longestWord(paint: android.text.TextPaint, text: String): Float {
        var widest = 0f
        var start = 0
        for (i in 0..text.length) {
            if (i == text.length || text[i].isWhitespace()) {
                if (i > start) widest = maxOf(widest, paint.measureText(text, start, i))
                start = i + 1
            }
        }
        return widest
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun screenSize(wm: WindowManager): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    private fun circle(strokeWidth: Int, fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(strokeWidth, stroke)
        }

    private fun roundRect(radius: Int, fill: Int, stroke: Int, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke(strokeWidth, stroke)
            cornerRadius = radius.toFloat()
        }

    private const val MAX_BOXES = 90
    private const val LONG_PRESS_MS = 700L

    // Metricas usadas para estimar a largura que a traducao precisa e para medir o
    // encaixe real da fonte. Ver [fitInBox].
    private const val CHAR_WIDTH_FACTOR = 0.58f

    // O retangulo do no de acessibilidade nem sempre abraca a caixa de texto
    // inteira (padding, entrelinha), entao exigir encaixe perfeito do texto
    // ORIGINAL encolheria fontes que estao certas. A folga serve para conter so os
    // valores claramente inflados que o WebView reporta.
    private const val SOURCE_FIT_TOLERANCE = 1.6f

    // Passo e teto de iteracoes da busca pela maior fonte que cabe na caixa.
    //
    // 40 passos de 6% cobrem a descida inteira (de 44dp ate o piso de 6dp). Com
    // menos passos a busca parava no meio e devolvia o piso direto, encolhendo o
    // texto mais do que o necessario.
    private const val FIT_STEP = 0.94f
    private const val MAX_FIT_STEPS = 40

    // Opaco de proposito: a caixa precisa ESCONDER o texto original. Com
    // translucidez o original aparece por baixo da traducao (dois textos
    // sobrepostos). Era 0xE6 (90%); agora que o sistema nao forca mais alpha 0.8 na
    // janela, o opaco e de fato opaco.
    private val ANCHOR_FILL = 0xFF141416.toInt()
    private val ANCHOR_STROKE = 0x3DFFFFFF
    private val BUBBLE_ON = 0xFF1F6FEB.toInt()
    private val BUBBLE_OFF = 0xFF4A4A52.toInt()
    private val BUBBLE_STROKE = 0x66FFFFFF

    /** Tamanho da bolha de controle, em dp. */
    private val BUBBLE_DP = 40

    /** Quanto esperamos o servico de acessibilidade antes de aceitar a janela comum. */
    private val A11Y_WAIT_MS = 2500L

    /**
     * Area de texto (px²) que a bolha pode cobrir sem que valha a pena move-la.
     *
     * A bolha inteira tem ~11 000 px² (40 dp); este limite e ~27% dela. Abaixo disso
     * ela esta encostada na borda de uma caixa, nao por cima de um paragrafo.
     */
    private val BUBBLE_OK_AREA = 3000L

    // A bolha e desenhada ACIMA das caixas: no meio da tela ela escondia o texto
    // traduzido. Em repouso fica semitransparente (o texto aparece por baixo) e so
    // fica opaca enquanto o dedo a segura.
    private val BUBBLE_ALPHA_ON = 0.45f
    private val BUBBLE_ALPHA_OFF = 0.85f
}