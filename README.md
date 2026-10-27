# ScreenTranslate

APK Android que desenha uma **sobreposição ancorada** sobre qualquer app: cada trecho
de texto visível é traduzido e a tradução é desenhada **exatamente em cima do texto
original**, no mesmo lugar e com o mesmo tamanho de fonte. Tradução **100% on-device**
(ML Kit) — nenhum texto sai do aparelho, sem chave de API, sem servidor.

---

## Como funciona

O app extrai **blocos de texto com geometria** (`TextBlock`: texto + `Rect` em pixels
da tela + tamanho de fonte estimado) e reconstrói cada um como uma caixa de overlay
na mesma posição.

```
┌────────────────────────────┐      ┌──────────────────────────────┐
│ ScreenTextAccessibilitySvc │      │ ScreenCaptureService         │
│ AccessibilityNodeInfo      │      │ MediaProjection              │
│ ~getBoundsInScreen()~      │      │ ImageReader → frame 900px    │
│ barato · instantâneo       │      │ + escala bitmap→tela         │
└─────────────┬──────────────┘      └───────────────┬──────────────┘
              │ A11yTextSource: List<TextBlock>     │ CapturedFrame
              └────────────────┬────────────────────┘
                               ▼
                     TranslationPipeline
     dedupe por texto → 1 detecção de idioma → modelo
     → tradução paralela (6) → cache → **emissão progressiva**
                               ▼
                    OverlayController
   janela de tela cheia FLAG_NOT_TOUCHABLE, alinhada às
   coordenadas da tela; uma TextView por bloco, na posição
   original → parece tradução simultânea
```

### Três modos de captura

| Modo | Como funciona | Cobre |
|---|---|---|
| `ACCESSIBILITY` | lê a árvore de acessibilidade | apps nativos, WhatsApp, browsers, redes sociais |
| `HYBRID` *(padrão)* | tenta a árvore; sem texto útil, cai para OCR | quase tudo |
| `OCR` | `MediaProjection` → frame → ML Kit Text Recognition | jogos, vídeo, imagens, canvas/OpenGL |

### O que torna a tradução "quase simultânea"

1. **Assinatura de frame** — se o conjunto de textos *e posições* não mudou, não se
   faz nada. Nenhum trabalho quando a tela está parada.
2. **Dedupe por string** — "OK" aparecendo 8 vezes é traduzido 1 vez.
3. **Cache LRU (600 entradas)** — o que já foi traduzido nunca é retraduzido.
4. **Emissão progressiva** — o que está em cache aparece na hora; o resto chega em
   lotes a cada ~70 ms, então a tela vai "preenchendo" em vez de esperar tudo.
5. **Modelo preparado antes do disparo paralelo** — evita que 6 corrotinas concorrentes
   verifiquem/baixem o modelo ao mesmo tempo.
6. **Pool de `TextView`s** — as caixas são reutilizadas, nunca recriadas por frame.
7. **Loop de OCR só quando precisa** — no modo híbrido, se a árvore de acessibilidade
   está funcionando, o OCR nem roda. E só reprocessa se o *frame id* mudou.

### Arquivos

| Arquivo | Papel |
|---|---|
| `core/TextBlock.kt` | `TextBlock` (texto + rect + fonte) e `BlockTranslation` |
| `a11y/ScreenTextAccessibilityService.kt` | lê a árvore com `getBoundsInScreen()`, debounce 400 ms, ignora próprio pacote, senhas e campos editáveis |
| `a11y/A11yTextSource.kt` | ponte via `StateFlow<List<TextBlock>>` |
| `capture/ScreenCaptureService.kt` | foreground service `MediaProjection`; expõe `CapturedFrame` (bitmap + escala + frame id) |
| `ocr/OcrEngine.kt` | ML Kit OCR; usa `line.boundingBox` e converte para pixels da tela |
| `translate/TranslationEngine.kt` | Language ID + Translation, cache LRU, `prepare()` do modelo |
| `pipeline/TranslationPipeline.kt` | orquestra tudo; tradução paralela com `Semaphore(6)` |
| `overlay/A11yWindowHost.kt` | guarda o `WindowManager` do serviço de acessibilidade (o token que autoriza a janela 2032) |
| `overlay/OverlayController.kt` | camada ancorada de tela cheia + bolha de controle arrastável |
| `MainActivity.kt` | permissões, idioma, modo, log, gancho de automação por Intent |
| `tools/emulator.ps1` | `up`/`down`/`status`/`provision` do AVD `st_api36` |
| `tools/wireless.ps1` | `-Discover`/`-Pair`/`-Connect`/`-Status`/`-Reset` do ADB sem fio |
| `tools/run.ps1` | ciclo completo (instalar → reabilitar acessibilidade → esperar o vínculo → abrir → print + log) |

---

## Compilar

### Requisitos (já instalados nesta máquina)

| Ferramenta | Versão | Caminho |
|---|---|---|
| JDK | Temurin 21.0.12 | `C:\tools\jdk-21` |
| Gradle | 8.14.3 | wrapper em `gradlew.bat` |
| Android SDK | `android-36`, `build-tools 36.0.0` | `%LOCALAPPDATA%\Android\Sdk` |
| AGP / Kotlin | 8.13.2 / 2.2.21 | — |

```powershell
cd C:\Users\dgcav\ScreenTranslate
.\gradlew.bat assembleDebug
```

Saída: `app\build\outputs\apk\debug\app-debug.apk`

No VS Code: **Ctrl+Shift+B**. Outras tarefas em `.vscode/tasks.json`:
`installDebug`, `ADB: instalar APK de debug`.

---

## Testar no celular

### 1. Pré-voo (recomendado)

```powershell
cd C:\Users\dgcav\ScreenTranslate
.\tools\doctor.ps1 -Install -Logcat
```

O script lê o estado **real** do aparelho por `adb` — sem depender do que a UI do app
mostra — e imprime:

- modelo, versão do Android e API;
- `SYSTEM_ALERT_WINDOW` via `appops get`;
- `enabled_accessibility_services` via `settings get`;
- `wm size` e `wm density` (para comparar com o log do overlay);
- se o `ScreenCaptureService` está registrado.

Depois deixa o `logcat` filtrado na tag `ScreenTranslate`.

Sem argumentos, apenas diagnostica. Variações: `-Install`, `-Logcat`.

### 2. Manual

1. Ative **Depuração USB** e conecte o aparelho (`adb devices` deve mostrar `device`).
2. ```powershell
   .\gradlew.bat installDebug
   ```
3. Abra o **ScreenTranslate** e siga os 3 passos:

   | Passo | O que acontece |
   |---|---|
   | 1. Permissão de sobreposição | `Configurações → Sobrepor outros apps` |
   | 2. Leitura de tela | `Configurações → Acessibilidade` → ative **ScreenTranslate** |
   | 3. Captura de tela | diálogo do sistema (só necessário nos modos OCR/híbrido) |

4. Ligue **Traduzir automaticamente**, escolha o **idioma de destino** e toque em
   **Mostrar tradução na tela**.
5. Abra qualquer app estrangeiro. O texto aparece traduzido **no lugar**.

### 3. Log

Tudo que o app registra vai para a `TextView` da tela inicial **e** para o `logcat`:

```powershell
adb logcat -c
adb logcat -s ScreenTranslate
```

| Linha no log | Confirma |
|---|---|
| `serviço de acessibilidade conectado` | Passo 2 |
| `captura de tela autorizada` / `ativa em WxH @ Ddpi` | Passo 3 |
| `camada WxH px · densidade D` | overlay montado |
| `camada ancorada adicionada · tipo 2032 · alpha 1.0` | o token do serviço de acessibilidade foi usado (a 2038 sairia limitada a 80 %) |
| `aguardando o serviço de acessibilidade` | o app está esperando o token em vez de cair para 2038 |
| `origem da camada X,Y` | recuo da janela sob a barra de status, a compensar |
| `conferência: bloco (l,t) · offset camada X,Y · margem m,n · caixa real (l,t) · largura W altura H · fonte Fpx` | a caixa caiu exatamente onde o texto estava |
| `N caixas · tela WxH · (l,t)-(r,b) \| ...` | tradução ancorada desenhada |
| `eco corrigido: "..." -> "..."` | o modelo tinha devolvido o próprio texto e a emoldura resolveu |
| `bolha em X,Y · area de texto coberta Npx²` | onde a bolha foi colocada e quanto ela cobre de texto |

**Se as caixas aparecerem deslocadas**, essas duas linhas são o diagnóstico:
compare `adb shell wm size` (Physical size) com a linha `camada WxH px`. Se os dois
`WxH` batem, o offset é barra de status/notch; se não batem, é o `maximumWindowMetrics`.
A amostra `(l,t)-(r,b)` mostra se o erro é de **posição** (valores plausíveis mas
deslocados) ou de **escala** (valores ~densidade× errados).

### A bolha

| Gesto | Ação |
|---|---|
| **toque** | liga/desliga as caixas de tradução (fica cinza quando desligada) |
| **arrastar** | move a bolha |
| **segurar 0,7 s** | remove o overlay inteiro |

O número dentro da bolha é a **quantidade de blocos traduzidos** na tela naquele momento.
Ela é **semitransparente** (45 % com a tradução ligada) porque fica numa janela *acima*
das caixas — opaca, ela esconde o texto que cobre.

---

## Testar sem USB (ADB sem fio) e no emulador

O PC não reconhece o aparelho por USB, então todo o ciclo (instalar, abrir, ler o
logcat, tirar print) acontece por ADB sem fio.

### Descobrir o aparelho

No celular: **Opções do desenvolvedor → Depuração sem fio**. O `IP:porta` muda **toda
vez** que a tela de depuração sem fio é fechada — nunca reaproveite a porta de uma
sessão antiga.

```powershell
.\tools\wireless.ps1 -Discover           # o que o mDNS anuncia
.\tools\wireless.ps1 -Pair -Port 37xxx   # uma vez só: o pareamento fica salvo
.\tools\wireless.ps1 -Connect -Port 37xxx
.\tools\wireless.ps1 -Status
```

Se o `adb mdns services` voltar vazio, o anúncio mDNS não saiu (ou a tela de
depuração sem fio foi fechada). O caminho que sempre funciona é copiar o `IP:porta`
mostrado em **Depuração sem fio** e conectar direto: `adb connect 192.168.1.11:37xxx`.

| Sintoma | Causa |
|---|---|
| `protocol fault (couldn't read status message)` | porta/código de pareamento vencidos — o diálogo expira em segundos |
| `connection refused (10061)` | a porta mudou (pegue a atual na tela de depuração sem fio) |
| `offline` no `adb devices` | a tela do celular apagou; acorde e reconecte |

### Ciclo completo em um comando

```powershell
.\tools\run.ps1 -Serial 192.168.1.11:37xxx `
                -Apk app\build\outputs\apk\debug\app-debug.apk `
                -Url 'https://en.wikipedia.org/wiki/Android_version_history' `
                -Shot phone.png -Log log_phone.txt

# no emulador
.\tools\run.ps1 -Serial emulator-5554 -Apk app\build\outputs\apk\debug\app-debug.apk -Shot emu.png
```

O script existe por causa de **duas armadilhas** que custaram várias rodadas:

1. `adb shell am force-stop <pacote>` **remove o serviço de acessibilidade** da lista
   de serviços habilitados. Reabilitar *antes* do force-stop não adianta — a ordem tem
   de ser force-stop → reabilitar.
2. Depois de reabilitado, o serviço leva ~1 s para **vincular de verdade**. Se o app
   abrir antes disso, a janela cai para `TYPE_APPLICATION_OVERLAY` (2038) e o sistema
   a limita a **80 % de opacidade** — o texto original volta a transparecer.

O `run.ps1` faz exatamente: force-stop → reabilitar → esperar o `dumpsys accessibility`
mostrar `Bound services:` → abrir o app → abrir a URL → esperar → print + log.

### Emulador

```powershell
.\tools\emulator.ps1 -Action up         # sobe o AVD st_api36 e espera o boot
.\tools\emulator.ps1 -Action provision  # appops + acessibilidade + tela sempre acesa
.\tools\emulator.ps1 -Action status
.\tools\emulator.ps1 -Action down
```

Geometria de referência (compare com o log do overlay):

| | Tamanho | Densidade | Barra de status |
|---|---|---|---|
| `st_api36` (emulador) | 1080×2400 | 420 (2.625) | **136 px** |
| 23129RAA4G (celular) | 1080×2400 | 440 (2.75) | **94 px** |

O emulador apaga a tela sozinho e um `screencap` com a tela apagada sai **preto**, sem
dump nenhum. Antes de capturar: `input keyevent KEYCODE_WAKEUP` +
`settings put system screen_off_timeout 1800000`.

### HyperOS (23129RAA4G) bloqueia

| Tentativa | Resultado |
|---|---|
| `adb install -g` | falha; os appops vão em separado (`appops set ... SYSTEM_ALERT_WINDOW allow`) |
| `adb shell input tap/keyevent` | `INJECT_EVENTS` negado, **mesmo** com "Instalar via USB" e "Depuração USB (Configurações de segurança)" ligadas |
| `adb shell wm dismiss-keyguard` | **não** acorda a tela; o celular precisa estar desperto e desbloqueado |

Como não dá para injetar toques, o app tem um **gancho de automação por Intent**:

```powershell
adb shell am start -n com.example.screentranslate/.MainActivity --ez auto true --ez overlay true
```

`auto` liga a tradução automática, `overlay` mostra as caixas. A `MainActivity` é
`singleTop` e trata o Intent também em `onNewIntent`, então o comando pode ser repetido
sem reiniciar o app.

### No celular, o `uiautomator` atrapalha

`adb shell uiautomator dump` **desloca o serviço de acessibilidade e derruba as janelas
do overlay**. Se você precisa da árvore "de verdade" para comparar com o que o app leu,
dumpe **antes** de mostrar o overlay. E o `uiautomator` não vê conteúdo de `WebView`: a
estrutura interna de uma página no Chrome só aparece no nosso `A11yTree`/`A11yDump`.

---

## Decisões de implementação (e o que quebrou até chegar nelas)

### Tipo de janela: 2032, nunca 2038

`TYPE_ACCESSIBILITY_OVERLAY` (2032) só pode ser criada pelo `WindowManager` **do
serviço de acessibilidade** — é ele que carrega o token. Criada pelo `WindowManager` da
aplicação, ela falha com `BadTokenException: token null is not valid`.

`TYPE_APPLICATION_OVERLAY` (2038) funciona sempre, mas uma janela **não tocável** desse
tipo é limitada pelo sistema a `MAX_OBSCURING_OPACITY = 0.8` (visível no `dumpsys window`
como `alpha=0.8`), justamente para o usuário ainda enxergar a tela por baixo — era isso
que fazia o texto original transparecer sob a tradução.

Por isso o `show()` **espera** o serviço de acessibilidade (até 2,5 s) antes de criar as
janelas, em vez de cair para 2038 na primeira oportunidade.

### A origem da camada não é (0,0)

Uma janela de acessibilidade nasce recuada abaixo da barra de status: a camada fica em
**(0, 136)** no emulador e **(0, 94)** no celular, e não em (0,0). Como `getBoundsInScreen()`
devolve coordenadas **de tela**, sem compensar esse recuo toda caixa apareceria ~136 px
abaixo do texto. O `layerOffsetX/Y` é lido da posição real da camada e a margem de cada
caixa é `block.left - layerOffsetX - 1dp`.

Duas linhas do log confirmam isso:

```
Overlay — origem da camada 0,136 · vista 1080x2400
Overlay — conferência: bloco (42,477) · offset camada 0,136 · margem 40,475 · caixa real 40,475
```

Se `bloco` e `caixa real` divergirem, o recuo não está sendo compensado.

### Nunca teste em `com.android.settings`

As janelas do sistema são protegidas por `HIDE_NON_SYSTEM_OVERLAY_WINDOWS`: as caixas
simplesmente não aparecem sobre o app de Configurações. Use Chrome, Clock, WhatsApp.

### A árvore do Chrome vem picada

Um parágrafo do `WebView` chega dividido em vários nós **irmãos** ("The version history
of the", "Android", "mobile operating system", …) e existe um nó "envelope" que cobre a
página inteira. O pipeline é

```
dropContainers(mergeOverlapping(dedupe(found)))
```

- `mergeOverlapping` junta fragmentos **apenas entre irmãos** (mesmo `parentId`). Essa
  regra estrutural é o que impede um contêiner de engolir os próprios filhos — foi o que
  quebrou o card "Alarme" do Clock, que virou uma coluna de letras empilhadas.
- `dedupe` remove nós repetidos (mesmo retângulo + mesmo texto).
- `dropContainers` remove o que cobre uma fração grande da tela mas contém outro texto
  (`WRAPPER_SCREEN_FRACTION`, `CONTAINER_TEXT_LIMIT`, `CONTAINER_RATIO`) — foi o que
  eliminou o retângulo preto gigante sobre a página e a coluna de letras.

### A fonte é medida, não estimada

`extraRenderingInfo.textSizeInPx` vem **inflado** no `WebView` (um parágrafo de 130 px de
altura reportou fonte 196). O `fitInBox` mede o `TextView` de verdade (`measure()` +
`layout.lineCount`) e desce a fonte em passos de 6 % até o texto caber **na largura e na
altura**:

- `longestLine` pega o transbordo horizontal (que a altura sozinha não denuncia);
- `longestWord` pega a palavra quebrada no meio ("Salvar" → "Sal"/"var"), porque o
  Android não transborda palavra longa: ele a parte, e as duas metades "cabem".

Outros dois detalhes que só apareceram na prática:

- a altura da caixa é a **do nó original** (não `wrap_content`), senão a caixa abraça o
  texto e a tradução sobe, deixando o original visível embaixo;
- o piso da fonte é `dp(6)` e o limite direito compara o vizinho com **`block.left`**
  (não `block.right`): um vizinho que começa alguns pixels *dentro* do bloco era
  ignorado, a caixa crescia por cima dele e a palavra saía cortada.

### O modelo às vezes devolve o próprio texto

O NMT on-device **ecoa** rótulos curtos em vez de traduzir: "Android version history"
ficava em inglês no meio de uma página inteira traduzida. Medido no emulador (en→pt),
com a mesma entrada:

| Entrada | Saída |
|---|---|
| `Android version history` | ecoa |
| `ANDROID VERSION HISTORY` / `android version history` | ecoa |
| `Android version history` + quebra de linha | ecoa |
| `Android version history.` (ponto) | **Histórico de versão do Android.** |
| `- Android version history` | **- Histórico de versão do Android** |
| `"Android version history"` | **"Histórico de versão do Android"** |

Como a mesma entrada ora traduz ora ecoa, o motor tenta uma **escada** (ponto → item de
lista) e fica com a primeira resposta diferente da entrada; se as duas ecoarem, mantém o
original. Sem isso, o título ficava em inglês.

### A bolha tapava o texto

A bolha é uma janela **acima** da camada: tudo o que ela cobre fica escondido. Num artigo
longo ela caiu no meio do primeiro parágrafo, que parecia "cortado" e na verdade estava
por baixo dela. Agora, a cada conteúdo novo, ela mede quanta área de texto cada posição
candidata (cantos + meio-esquerda/direita) cobriria e escolhe a menor; depois que o
usuário arrasta a bolha, o posicionamento automático para.

---

## Detalhes que costumam quebrar (já tratados)

- **Android 14+**: o foreground service sobe com `foregroundServiceType="mediaProjection"`
  **antes** de `getMediaProjection()`, e o consentimento é pedido a cada sessão.
- **Alinhamento de coordenadas**: a camada usa `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`
  com o tamanho de `maximumWindowMetrics`, o que faz o canto (0,0) da janela coincidir com
  o canto (0,0) que `getBoundsInScreen()` reporta.
- **`rowStride` do `ImageReader`**: tratado no `Image.toBitmap()` (padding de linha).
- **Escala OCR → tela**: o frame é reduzido a 900 px, então cada `boundingBox` é
  multiplicado por `screenWidth / bitmapWidth` antes de virar bloco.
- **Sem `use-after-recycle`**: os bitmaps de frame **nunca** são reciclados, porque o OCR
  pode estar lendo enquanto um novo frame chega.
- **Sem loop de tradução**: o serviço de acessibilidade ignora eventos do próprio
  pacote, e o walk ignora nós do próprio pacote — o overlay nunca traduz a si mesmo.

---

## Limitações conhecidas

- **Texto com fonte muito grande ou muito pequena** pode não casar perfeitamente: o
  tamanho da fonte é lido de `AccessibilityNodeInfo.getExtraRenderingInfo().textSizeInPx`
  no **Android 14+**; abaixo disso é **estimado** pela altura do nó e ajustado ao espaço.
- **Traduções mais longas** fazem a caixa crescer para a direita (até 2,6× a largura
  original, limitado pela borda da tela) e encolher a fonte. Em layouts muito densos
  ainda pode haver sobreposição.
- **OCR é assíncrono**: o `ImageReader` só entrega frame quando a tela muda.
- **Language ID pode devolver um código que o tradutor não suporta** (ex.: `tl`) — o
  bloco simplesmente não é traduzido.
- **Primeira tradução de cada par de idiomas baixa o modelo** (~30 MB): precisa de
  internet uma vez, depois funciona offline.
- **Janelas com `FLAG_SECURE`** aparecem em preto na captura (bancos, DRM).
- **Play Store**: usar `AccessibilityService` para ler a tela de outros apps é restrito
  e exige declaração + justificativa. Para uso pessoal, sideload funciona normalmente.

---

## Próximos passos sugeridos

1. **Reduzir o APK** (112 MB em debug por causa das libs nativas do ML Kit nas 4 ABIs):
   ```kotlin
   android {
       splits {
           abi {
               isEnable = true
               reset()
               include("arm64-v8a", "armeabi-v7a", "x86_64")
               isUniversalApk = true
           }
       }
   }
   ```
2. **Cor de fundo adaptativa**: amostrar a cor média do frame ao redor do bloco e pintar
   a caixa com essa cor, para o overlay "sumir" no layout do app.
3. **Seguir scroll**: interpolar posições entre frames para a caixa acompanhar o
   movimento em vez de "pular".
4. **Cache em disco** (Room) para reaproveitar traduções entre sessões.
5. **Filtro de idioma por bloco**: usar Language ID por linha para não traduzir nomes
   próprios e endereços.