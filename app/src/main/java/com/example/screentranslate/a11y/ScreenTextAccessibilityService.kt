package com.example.screentranslate.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.screentranslate.core.LogBus
import com.example.screentranslate.core.TextBlock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Le a arvore de acessibilidade do app em primeiro plano e publica cada trecho
 * de texto COM a sua posicao na tela ([TextBlock]).
 *
 * E o caminho barato e praticamente instantaneo: cobre a maioria dos apps.
 * Nao enxerga conteudo desenhado em Canvas/OpenGL - para isso existe o OCR.
 */
class ScreenTextAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val extracting = AtomicBoolean(false)
    private var pending: Runnable? = null
    private var lastPublishedCount = -1

    /** Sequencia de nos da ultima leitura, usada para identificar irmaos. */
    private var nodeSeq = 0

    /**
     * Bloco lido da arvore junto do id do no PAI. Dois blocos com o mesmo pai sao
     * pedacos do mesmo texto (o Chrome pica o paragrafo em varios nos irmaos).
     */
    private data class Candidate(val block: TextBlock, val parent: Int)

    override fun onServiceConnected() {
        super.onServiceConnected()
        A11yTextSource.setConnected(true)
        // Este WindowManager carrega o token do servico de acessibilidade: e o que
        // permite criar as janelas TYPE_ACCESSIBILITY_OVERLAY do overlay ancorado.
        A11yWindowHost.attach(getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        LogBus.log("A11y", "serviço de acessibilidade conectado")
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        A11yTextSource.setConnected(false)
        A11yWindowHost.attach(null)
        LogBus.log("A11y", "serviço de acessibilidade desconectado")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        A11yWindowHost.attach(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        // Eventos gerados pelo proprio app (UI e overlay) nao interessam.
        if (e.packageName == packageName) return

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> schedule()
        }
    }

    private fun schedule() {
        pending?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { extract() }
        pending = runnable
        handler.postDelayed(runnable, DEBOUNCE_MS)
    }

    private fun extract() {
        if (!extracting.compareAndSet(false, true)) return
        try {
            val root = rootInActiveWindow
            if (root == null) {
                LogBus.log("A11y", "sem janela ativa para ler")
                A11yTextSource.publish(emptyList())
                return
            }
            val found = ArrayList<Candidate>(MAX_BLOCKS)
            nodeSeq = 0
            walk(root, found, 0, -1)
            val unique = dropContainers(mergeOverlapping(dedupe(found)))
            // Diagnostico: se a tela nao for traduzida, este e o primeiro lugar a
            // olhar - diz se o problema esta na leitura da arvore ou depois.
            if (unique.size != lastPublishedCount) {
                lastPublishedCount = unique.size
                LogBus.log("A11y", "${unique.size} blocos de texto na tela")
            }
            if (DEBUG_LAYOUT) {
                dumpCandidates(found)
                dump(unique)
            }
            A11yTextSource.publish(unique)
        } catch (t: Throwable) {
            LogBus.log("A11y", "falha ao ler a árvore: ${t.message}")
        } finally {
            extracting.set(false)
        }
    }

    /**
     * Diagnostico temporario: imprime cada bloco com texto integral e retangulo.
     * Serve para desenhar a regra de deduplicacao/mercao com dados reais em vez de
     * suposicoes sobre a arvore do Chrome.
     */
    private fun dump(blocks: List<TextBlock>) {
        blocks.forEachIndexed { index, b ->
            LogBus.log(
                "A11yDump",
                "#$index \"${b.text}\" (${b.left},${b.top})-(${b.right},${b.bottom}) f=${b.fontSizePx.toInt()}"
            )
        }
    }

    /** Diagnostico: arvore CRUA, antes de deduplicar/juntar, com o pai de cada no. */
    private fun dumpCandidates(candidates: List<Candidate>) {
        candidates.forEachIndexed { index, c ->
            val b = c.block
            LogBus.log(
                "A11yTree",
                "#$index pai=${c.parent} \"${b.text}\" (${b.left},${b.top})-(${b.right},${b.bottom})"
            )
        }
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        out: MutableList<Candidate>,
        depth: Int,
        parentId: Int
    ): Int {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_BLOCKS) return -1
        // Ignora a propria UI (inclusive o overlay) para nao traduzir a traducao.
        if (node.packageName == packageName) return -1

        val id = nodeSeq++

        if (node.isVisibleToUser && !node.isPassword && !node.isEditable) {
            val label = node.text?.toString()?.trim().orEmpty()
                .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }

            if (label.isNotEmpty()) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val block = TextBlock(
                    text = label,
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                    fontSizePx = fontSizeOf(node, rect)
                )
                if (block.isUsable) out.add(Candidate(block, parentId))
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            walk(node.getChild(i), out, depth + 1, id)
        }
        return id
    }

    /**
     * O tamanho real da fonte so esta disponivel a partir do Android 14
     * (API 34) via [AccessibilityNodeInfo.getExtraRenderingInfo]. Nas versoes
     * anteriores estimamos pela altura do no e deixamos o overlay refinar.
     */
    private fun fontSizeOf(node: AccessibilityNodeInfo, rect: Rect): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val real = node.extraRenderingInfo?.textSizeInPx ?: 0f
            if (real > 0f) return real.coerceIn(MIN_FONT_PX, MAX_FONT_PX)
        }
        return (rect.height() * FONT_HEIGHT_RATIO).coerceIn(MIN_FONT_PX, MAX_FONT_PX)
    }

    /**
     * Remove duplicatas do MESMO texto.
     *
     * Tres situacoes aparecem na pratica:
     *
     *  - nos aninhados (um container e o rotulo dentro dele): fica o **mais
     *    justo** - o rotulo. Antes ficavamos com o container, e numa aba como a do
     *    Clock a traducao era centralizada sobre o icone em vez de cair onde o
     *    texto realmente esta.
     *  - caixas que se sobrepoem sem estar aninhadas (o Chrome repete "Download
     *    PDF" numa area de toque sobre o botao): fica a **maior**.
     *  - caixas lado a lado na mesma linha, uma minuscula e outra grande (o
     *    cabecalho de secao da Wikipedia expoe "Overview" no rotulo e no chevron):
     *    fica a **maior**, desde que a pequena seja bem menor.
     */
    private fun dedupe(candidates: List<Candidate>): List<Candidate> {
        val seen = HashSet<String>(candidates.size)
        val unique = ArrayList<Candidate>(candidates.size)
        for (c in candidates) if (seen.add(c.block.key)) unique.add(c)

        val dropped = HashSet<String>()
        for (outer in unique) {
            val o = outer.block
            if (o.key in dropped) continue
            for (inner in unique) {
                val i = inner.block
                if (i.key == o.key || i.key in dropped) continue
                if (i.text != o.text) continue
                val oArea = area(o)
                val iArea = area(i)
                val contains = o.left <= i.left && o.top <= i.top &&
                    o.right >= i.right && o.bottom >= i.bottom
                val innerContains = i.left <= o.left && i.top <= o.top &&
                    i.right >= o.right && i.bottom >= o.bottom
                when {
                    contains -> dropped.add(if (oArea > iArea) o.key else i.key)
                    innerContains -> dropped.add(if (iArea > oArea) i.key else o.key)
                    overlapRatio(o, i) > 0f -> dropped.add(if (oArea >= iArea) i.key else o.key)
                    sameLine(o, i) && iArea * 2 < oArea -> dropped.add(i.key)
                    sameLine(o, i) && oArea * 2 < iArea -> dropped.add(o.key)
                }
            }
        }
        return unique.filterNot { it.block.key in dropped }
    }

    /**
     * Descarta blocos que apenas agrupam outros textos.
     *
     * Dois casos concretos:
     *
     *  - a raiz do conteudo web do Chrome: um no com a descricao da pagina e o
     *    retangulo da area visivel inteira. Desenhado, virava um retangulo preto
     *    enorme sobre o topo da pagina.
     *  - um cartao nativo como o do Clock: o cartao carrega o texto "Alarm"
     *    (etiqueta de acessibilidade) e o retangulo do cartao inteiro, mas o
     *    "Alarme" que se ve na tela e OUTRO no, no titulo. O cartao contem os
     *    filhos de verdade ("8:30 AM", "Mon, Tue..."), entao um bloco curto que
     *    abraca outros e etiqueta, nao texto para traduzir.
     *
     * Um paragrafo longo que por acaso contenha um trecho continua: o texto dele
     * e justamente o que interessa ([CONTAINER_TEXT_LIMIT]).
     */
    private fun dropContainers(blocks: List<TextBlock>): List<TextBlock> {
        if (blocks.size < 2) return blocks
        val wrapperLimit = displayArea() * WRAPPER_SCREEN_FRACTION
        return blocks.filterNot { b ->
            val contained = blocks.filter { other ->
                other !== b &&
                    other.left >= b.left && other.top >= b.top &&
                    other.right <= b.right && other.bottom <= b.bottom
            }
            if (contained.isEmpty()) return@filterNot false
            val wrapper = area(b) > wrapperLimit
            val label = b.text.length <= CONTAINER_TEXT_LIMIT &&
                area(b) >= contained.maxOf { area(it) } * CONTAINER_RATIO
            wrapper || label
        }
    }

    /** Dois blocos ocupam boa parte da mesma faixa vertical. */
    private fun sameLine(a: TextBlock, b: TextBlock): Boolean {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        val smaller = min(a.height, b.height).coerceAtLeast(1)
        return overlap.toFloat() / smaller >= 0.5f
    }

    /**
     * Junta num unico bloco os pedacos que a arvore expoe sobrepostos.
     *
     * O Chrome nao entrega um paragrafo como um no: entrega cada trecho de linha
     * como um no proprio (inclusive os links no meio da frase). Pior, alguns
     * desses nos recebem o retangulo do "inline box" inteiro, que quando a frase
     * quebra linha cobre varias linhas e comeca na borda esquerda do paragrafo:
     *
     *     "The version history of the"                ( 42,844)-( 525,897)
     *     "Android"                                   (522,844)-( 672,897)
     *     "mobile operating system"                   ( 42,844)-( 997,965)  <- duas linhas
     *     "began with the public release of its first"(175,913)-( 931,965)
     *     "beta"                                      (929,913)-(1013,965)
     *
     * Desenhados como estao, viravam caixas empilhadas uma em cima da outra. Aqui
     * eles viram um bloco so: retangulo = uniao, texto = os trechos na ORDEM DA
     * ARVORE (que ja e a ordem de leitura) unidos por espaco. Reconstruir a frase
     * por coordenada erra, porque e justamente o retangulo que esta errado.
     *
     * A trava e estrutural: so junta pedacos IRMAOS na arvore (mesmo no pai). No
     * Chrome o paragrafo quebra em varios filhos do mesmo no, e junta-los
     * reconstroi a frase. Um cartao nativo como o "Alarme" do Clock tem o horario
     * como FILHO do cartao - nivel diferente -, entao continua com caixas
     * separadas como deve.
     *
     * Um limite de cobertura chegou a ser tentado aqui (so juntar se o resto do
     * grupo cobrisse o bloco maior), mas ele barrava justamente os paragrafos
     * bons: o ultimo pedaco de um paragrafo longo e grande e so e tocado de leve
     * pelos pedacos de cima. Com a exigencia de mesmo pai o caso ruim (container x
     * conteudo) ja nao acontece, entao a cobertura so atrapalhava.
     */
    private fun mergeOverlapping(candidates: List<Candidate>): List<TextBlock> {
        if (candidates.size < 2) return candidates.map { it.block }

        val wrapperLimit = displayArea() * WRAPPER_SCREEN_FRACTION
        val parent = IntArray(candidates.size) { it }

        fun root(index: Int): Int {
            var r = index
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }

        for (i in candidates.indices) {
            if (area(candidates[i].block) > wrapperLimit) continue
            for (j in i + 1 until candidates.size) {
                if (candidates[i].parent != candidates[j].parent) continue
                if (area(candidates[j].block) > wrapperLimit) continue
                if (overlapRatio(candidates[i].block, candidates[j].block) < MERGE_OVERLAP) continue
                val ri = root(i)
                val rj = root(j)
                if (ri != rj) parent[rj] = ri
            }
        }

        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (i in candidates.indices) groups.getOrPut(root(i)) { ArrayList() }.add(i)
        if (groups.size == candidates.size) return candidates.map { it.block }

        val merged = ArrayList<TextBlock>(groups.size)
        for (members in groups.values) {
            if (members.size == 1) {
                merged.add(candidates[members[0]].block)
                continue
            }
            merged.add(join(candidates, members))
        }
        return merged
    }

    /** Bloco unico com a uniao dos retangulos e o texto na ordem de leitura. */
    private fun join(candidates: List<Candidate>, members: List<Int>): TextBlock {
        val first = candidates[members.first()].block
        var left = first.left
        var top = first.top
        var right = first.right
        var bottom = first.bottom
        var font = first.fontSizePx
        val text = StringBuilder()
        for (m in members) {
            val b = candidates[m].block
            left = min(left, b.left)
            top = min(top, b.top)
            right = max(right, b.right)
            bottom = max(bottom, b.bottom)
            font = max(font, b.fontSizePx)
            if (text.isNotEmpty()) text.append(' ')
            text.append(b.text)
        }
        return TextBlock(text.toString(), left, top, right, bottom, font)
    }

    private fun overlapRatio(a: TextBlock, b: TextBlock): Float {
        val width = min(a.right, b.right) - max(a.left, b.left)
        val height = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (width <= 0 || height <= 0) return 0f
        val intersection = width.toLong() * height.toLong()
        return intersection.toFloat() / min(area(a), area(b)).coerceAtLeast(1L)
    }

    private fun area(b: TextBlock): Long = b.width.toLong().coerceAtLeast(1L) * b.height.toLong()

    private fun displayArea(): Long {
        val metrics = resources.displayMetrics
        return metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
        const val MAX_DEPTH = 45
        const val MAX_BLOCKS = 80
        const val FONT_HEIGHT_RATIO = 0.72f
        const val MIN_FONT_PX = 24f
        const val MAX_FONT_PX = 200f

        /** Despeja a arvore lida no log. Desligar depois de ajustar a deduplicacao. */
        const val DEBUG_LAYOUT = true

        /** Sobreposicao minima (fracao do menor bloco) para considerar os dois o mesmo texto. */
        const val MERGE_OVERLAP = 0.4f

        /** Acima desta fracao da tela o bloco e container de rolagem, nao texto. */
        const val WRAPPER_SCREEN_FRACTION = 0.5

        /** Texto no maximo deste tamanho pode ser etiqueta de um container. */
        const val CONTAINER_TEXT_LIMIT = 24

        /** Quanto o container precisa ser maior que o filho para ser tratado como etiqueta. */
        const val CONTAINER_RATIO = 2
    }
}