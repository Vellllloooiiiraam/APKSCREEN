<#
  ScreenTranslate - ciclo de vida e provisionamento do emulador.

  Uso:
    .\tools\emulator.ps1 -Action up          sobe o AVD e espera terminar o boot
    .\tools\emulator.ps1 -Action provision   instala o APK e concede tudo por adb
    .\tools\emulator.ps1 -Action status      mostra dispositivos e estado do boot
    .\tools\emulator.ps1 -Action down        desliga o emulador

  Por que provisionar por adb: no emulador da para conceder sobreposicao, acesso
  a midia de projecao e o servico de acessibilidade sem tocar na tela. Isso
  remove 3 fluxos de UI do caminho e deixa o teste reproduzivel.
#>
[CmdletBinding()]
param(
    [ValidateSet('up', 'down', 'status', 'provision')]
    [string]$Action = 'status',

    [string]$Avd = 'st_api36',
    [int]$BootTimeoutSec = 420
)

$ErrorActionPreference = 'Stop'

$Package     = 'com.example.screentranslate'
$A11yService = 'com.example.screentranslate/.a11y.ScreenTextAccessibilityService'
$ProjectRoot = Split-Path -Parent $PSScriptRoot

function Write-Head {
    param([string]$Text)
    Write-Host ''
    Write-Host "== $Text " -ForegroundColor Cyan -NoNewline
    Write-Host ('=' * [Math]::Max(0, 56 - $Text.Length)) -ForegroundColor DarkCyan
}
function Write-Ok   { param([string]$Text) Write-Host "  [ok]  $Text" -ForegroundColor Green }
function Write-Note { param([string]$Text) Write-Host "  [!]   $Text" -ForegroundColor Yellow }
function Write-Bad  { param([string]$Text) Write-Host "  [x]   $Text" -ForegroundColor Red }
function Write-Info { param([string]$Text) Write-Host "        $Text" -ForegroundColor Gray }

function Resolve-Tool {
    param([string]$Relative)
    $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) |
        Where-Object { $_ -and "$_".Trim() }
    foreach ($root in $roots) {
        $candidate = Join-Path $root $Relative
        if (Test-Path $candidate) { return $candidate }
    }
    throw "Nao encontrei $Relative no SDK."
}

$Adb      = Resolve-Tool 'platform-tools\adb.exe'
$Emulator = Resolve-Tool 'emulator\emulator.exe'

function Adb-Run {
    param([string[]]$AdbArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Adb @AdbArgs 2>&1 | ForEach-Object { "$_" } }
    finally { $ErrorActionPreference = $prev }
}
function Adb-Line {
    param([string[]]$AdbArgs)
    ((Adb-Run -AdbArgs $AdbArgs) -join "`n").Trim()
}

# Ativa o servico de acessibilidade e confirma pelo dumpsys. A vinculacao e
# assincrona: a escrita na setting pode nao ter efeito se o processo ainda nao
# existir, entao tentamos algumas vezes em vez de confiar no "settings get".
function Enable-A11y {
    param([string]$Serial)
    for ($i = 1; $i -le 4; $i++) {
        Adb-Run -AdbArgs @('-s', $Serial, 'shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1') | Out-Null
        Adb-Run -AdbArgs @('-s', $Serial, 'shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $A11yService) | Out-Null
        Start-Sleep -Seconds 2
        $dumpText = (Adb-Run -AdbArgs @('-s', $Serial, 'shell', 'dumpsys', 'accessibility')) -join "`n"
        if ($dumpText -match 'Bound services:\{Service\[label=ScreenTranslate') { return $true }
        if ($i -lt 4) { Write-Info "tentativa $i : ainda nao vinculou..." }
    }
    return $false
}

function Get-Serial {
    $raw = Adb-Run -AdbArgs @('devices')
    $match = $raw | Where-Object { $_ -match '^(emulator-\d+)\s+device' } | Select-Object -First 1
    if ($match) { return ($match -split '\s+')[0] }
    return $null
}

function Wait-Boot {
    param([string]$Serial)
    Write-Info "aguardando boot (ate $BootTimeoutSec s)..."
    $deadline = (Get-Date).AddSeconds($BootTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $done = Adb-Line -AdbArgs @('-s', $Serial, 'shell', 'getprop', 'sys.boot_completed')
        if ($done -eq '1') {
            # boot_completed pode chegar antes do PackageManager terminar
            $pm = Adb-Line -AdbArgs @('-s', $Serial, 'shell', 'pm', 'path', 'android')
            if ($pm -match 'package:') { return $true }
        }
        Start-Sleep -Seconds 3
    }
    return $false
}

switch ($Action) {

    'down' {
        Write-Head 'Desligando emulador'
        $serial = Get-Serial
        if ($serial) {
            Adb-Run -AdbArgs @('-s', $serial, 'emu', 'kill') | Out-Null
            Write-Ok "$serial encerrado"
        } else {
            Write-Note 'nenhum emulador rodando'
        }
    }

    'status' {
        Write-Head 'Dispositivos'
        Adb-Run -AdbArgs @('devices', '-l') | Where-Object { $_.Trim() } | ForEach-Object { Write-Info $_ }
        $serial = Get-Serial
        if ($serial) {
            $boot = Adb-Line -AdbArgs @('-s', $serial, 'shell', 'getprop', 'sys.boot_completed')
            Write-Ok "$serial (boot_completed=$boot)"
            if ($boot -eq '1') {
                Write-Info (Adb-Line -AdbArgs @('-s', $serial, 'shell', 'wm', 'size'))
                Write-Info (Adb-Line -AdbArgs @('-s', $serial, 'shell', 'wm', 'density'))
            }
        } else {
            Write-Note 'emulador nao esta rodando'
        }
    }

    'up' {
        Write-Head "Subindo AVD '$Avd'"
        $serial = Get-Serial
        if ($serial) {
            Write-Ok "ja esta rodando: $serial"
        } else {
            $proc = Get-Process -Name 'qemu-system-x86_64', 'emulator' -ErrorAction SilentlyContinue
            if (-not $proc) {
                Write-Info "iniciando emulator.exe -avd $Avd ..."
                Start-Process -FilePath $Emulator `
                    -ArgumentList @('-avd', $Avd, '-gpu', 'host', '-no-boot-anim', '-no-audio',
                                    '-netdelay', 'none', '-netspeed', 'full')
            } else {
                Write-Note 'processo do emulador ja existe; aguardando registrar no adb'
            }
            $deadline = (Get-Date).AddSeconds(120)
            while (-not (Get-Serial) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
            $serial = Get-Serial
            if (-not $serial) { Write-Bad 'o emulador nao apareceu no adb'; exit 1 }
            Write-Ok "apareceu: $serial"
        }

        if (-not (Wait-Boot -Serial $serial)) {
            Write-Bad "boot nao terminou em $BootTimeoutSec s"
            exit 1
        }
        Write-Ok 'boot concluido'

        Write-Host ''
        Write-Info 'Geometria (compare com o log do overlay):'
        Write-Info (Adb-Line -AdbArgs @('-s', $serial, 'shell', 'wm', 'size'))
        Write-Info (Adb-Line -AdbArgs @('-s', $serial, 'shell', 'wm', 'density'))
        $sdk = Adb-Line -AdbArgs @('-s', $serial, 'shell', 'getprop', 'ro.build.version.sdk')
        Write-Info "API $sdk"
    }

    'provision' {
        Write-Head 'Provisionando'
        $serial = Get-Serial
        if (-not $serial) { Write-Bad 'emulador nao esta rodando. Use -Action up'; exit 1 }
        Write-Ok "dispositivo: $serial"

        $apk = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
        if (-not (Test-Path $apk)) { Write-Bad "APK nao encontrado: $apk"; exit 1 }

        Write-Info 'instalando APK...'
        Adb-Run -AdbArgs @('-s', $serial, 'install', '-r', '-g', $apk) |
            Where-Object { $_ -match 'Success|Failure|Error' } | ForEach-Object { Write-Info $_ }

        Write-Host ''
        Write-Info 'concedendo sobreposicao (dispensa o Passo 1 da UI)...'
        Adb-Run -AdbArgs @('-s', $serial, 'shell', 'appops', 'set', $Package, 'SYSTEM_ALERT_WINDOW', 'allow') | Out-Null

        Write-Info 'concedendo PROJECT_MEDIA (dispensa o dialogo do MediaProjection)...'
        Adb-Run -AdbArgs @('-s', $serial, 'shell', 'appops', 'set', $Package, 'PROJECT_MEDIA', 'allow') | Out-Null

        # A partir do Android 13, app instalado por sideload tem "restricted
        # settings": o servico de acessibilidade NUNCA vincula ate liberar isto.
        Write-Info 'liberando restricted settings (senao a acessibilidade nao vincula)...'
        Adb-Run -AdbArgs @('-s', $serial, 'shell', 'appops', 'set', $Package, 'ACCESS_RESTRICTED_SETTINGS', 'allow') | Out-Null

        Write-Info 'notificacoes...'
        Adb-Run -AdbArgs @('-s', $serial, 'shell', 'pm', 'grant', $Package, 'android.permission.POST_NOTIFICATIONS') | Out-Null

        # O servico so vincula com o processo vivo: abre o app antes de ativar.
        Write-Host ''
        Write-Info 'abrindo o app (necessario antes de ativar a acessibilidade)...'
        Adb-Run -AdbArgs @('-s', $serial, 'shell', 'am', 'start', '-n', "$Package/.MainActivity") |
            Where-Object { $_ -match 'Error|Starting' } | ForEach-Object { Write-Info $_ }
        Start-Sleep -Seconds 3

        Write-Host ''
        Write-Head 'Verificacao'
        $appops = Adb-Line -AdbArgs @('-s', $serial, 'shell', 'appops', 'get', $Package, 'SYSTEM_ALERT_WINDOW')
        if ($appops -match 'allow') { Write-Ok 'sobreposicao: allow' } else { Write-Bad "sobreposicao: $appops" }

        $media = Adb-Line -AdbArgs @('-s', $serial, 'shell', 'appops', 'get', $Package, 'PROJECT_MEDIA')
        if ($media -match 'allow') { Write-Ok 'PROJECT_MEDIA: allow' } else { Write-Note "PROJECT_MEDIA: $media" }

        $restricted = Adb-Line -AdbArgs @('-s', $serial, 'shell', 'appops', 'get', $Package, 'ACCESS_RESTRICTED_SETTINGS')
        if ($restricted -match 'allow') { Write-Ok 'restricted settings: liberado' } else { Write-Bad "restricted settings: $restricted" }

        if (Enable-A11y -Serial $serial) {
            Write-Ok 'servico de acessibilidade: vinculado'
        } else {
            Write-Bad 'servico de acessibilidade NAO vinculou'
            Write-Info 'verifique: adb -s emulator-5554 shell dumpsys accessibility'
        }

        Write-Host ''
        Write-Head 'Proximo passo'
        Write-Info 'deixe o logcat aberto:'
        Write-Host "    $Adb -s $serial logcat -c; $Adb -s $serial logcat -s ScreenTranslate" -ForegroundColor White
        Write-Info 'no app: ligue "Traduzir automaticamente" e toque em "Mostrar tradução na tela"'
    }
}