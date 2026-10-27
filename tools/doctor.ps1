<#
  ScreenTranslate - pre-voo de teste no aparelho.

  Uso:
    .\tools\doctor.ps1              so diagnostica (nao instala nada)
    .\tools\doctor.ps1 -Install     compila e instala antes de diagnosticar
    .\tools\doctor.ps1 -Logcat      segue o logcat no final (Ctrl+C para sair)

  Le o estado REAL do aparelho, sem depender do que a interface do app mostra.
#>
[CmdletBinding()]
param(
    [switch]$Install,
    [switch]$Logcat
)

$ErrorActionPreference = 'Stop'

$Package     = 'com.example.screentranslate'
$ProjectRoot = Split-Path -Parent $PSScriptRoot

# ----------------------------------------------------------------- utilitarios

function Write-Head {
    param([string]$Text)
    Write-Host ''
    Write-Host "== $Text " -ForegroundColor Cyan -NoNewline
    Write-Host ('=' * [Math]::Max(0, 56 - $Text.Length)) -ForegroundColor DarkCyan
}
function Write-Ok    { param([string]$Text) Write-Host "  [ok]  $Text" -ForegroundColor Green }
function Write-Note  { param([string]$Text) Write-Host "  [!]   $Text" -ForegroundColor Yellow }
function Write-Bad   { param([string]$Text) Write-Host "  [x]   $Text" -ForegroundColor Red }
function Write-Info  { param([string]$Text) Write-Host "        $Text" -ForegroundColor Gray }
function Write-Cmd   { param([string]$Text) Write-Host "    $Text" -ForegroundColor White }

function Resolve-AdbPath {
    $roots = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    ) | Where-Object { $_ -and "$_".Trim() }

    foreach ($root in $roots) {
        $candidate = Join-Path $root 'platform-tools\adb.exe'
        if (Test-Path $candidate) { return $candidate }
    }

    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    throw 'adb.exe nao encontrado. Instale com: sdkmanager "platform-tools"'
}

# ---------------------------------------------------------------- 1. ambiente

Write-Head 'Ambiente'

$script:Adb = Resolve-AdbPath
$script:Serial = $null

Write-Ok "adb: $script:Adb"

# Deriva a raiz do SDK a partir de <sdk>\platform-tools\adb.exe
$sdkRoot = Split-Path -Parent (Split-Path -Parent $script:Adb)
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot

# No PowerShell 5.1, `2>&1` num executavel nativo gera ErrorRecord; com
# ErrorActionPreference=Stop isso viraria erro fatal (ex.: "daemon not running").
function Invoke-AdbRaw {
    param([string[]]$AdbArgs)
    $base = @()
    if ($script:Serial) { $base = @('-s', $script:Serial) }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $script:Adb @base @AdbArgs 2>&1 | ForEach-Object { "$_" }
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Invoke-AdbLine {
    param([string[]]$AdbArgs)
    ((Invoke-AdbRaw -AdbArgs $AdbArgs) -join "`n").Trim()
}

$devicesRaw = Invoke-AdbRaw -AdbArgs @('devices')
$online = @(
    $devicesRaw |
        Where-Object { $_ -match '^\S+\s+device$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
)

if ($online.Count -eq 0) {
    Write-Bad 'Nenhum aparelho autorizado.'
    Write-Host ''
    Write-Host '  Como resolver:' -ForegroundColor White
    Write-Info '1. No telefone: Configuracoes > Sobre o telefone > toque 7x em "Numero da versao"'
    Write-Info '2. Opcoes do desenvolvedor > Depuracao USB > ligar'
    Write-Info '3. Conecte o cabo e aceite "Permitir depuracao USB?" no telefone'
    Write-Info '4. Rode este script de novo'
    Write-Host ''
    Write-Host '  Saida bruta do adb devices:' -ForegroundColor DarkGray
    $devicesRaw | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
    exit 1
}

$script:Serial = $online[0]
Write-Ok "aparelho: $script:Serial"

$model   = Invoke-AdbLine -AdbArgs @('shell', 'getprop', 'ro.product.model')
$release = Invoke-AdbLine -AdbArgs @('shell', 'getprop', 'ro.build.version.release')
$sdkInt  = Invoke-AdbLine -AdbArgs @('shell', 'getprop', 'ro.build.version.sdk')
Write-Ok "modelo: $model  |  Android $release (API $sdkInt)"

if ([int]$sdkInt -lt 26) {
    Write-Bad "API $sdkInt e menor que o minSdk 26 - o app nao instala neste aparelho."
    exit 1
}

# ---------------------------------------------------------------- 2. instalacao

if ($Install) {
    Write-Head 'Compilando e instalando'
    Push-Location $ProjectRoot
    try {
        & .\gradlew.bat installDebug --console=plain
        if ($LASTEXITCODE -ne 0) { Write-Bad 'Falha no build/instalacao.'; exit 1 }
        Write-Ok 'APK instalado'
    } finally {
        Pop-Location
    }
}

# ---------------------------------------------------------------- 3. pacote

Write-Head 'Pacote'

$pathLine = Invoke-AdbLine -AdbArgs @('shell', 'pm', 'path', $Package)
if ($pathLine -match '^package:') {
    Write-Ok "instalado: $($pathLine -replace '^package:', '')"
} else {
    Write-Bad 'nao instalado. Rode: .\tools\doctor.ps1 -Install'
    exit 1
}

# ---------------------------------------------------------------- 4. permissoes

Write-Head 'Permissoes'

$appops = Invoke-AdbLine -AdbArgs @('shell', 'appops', 'get', $Package, 'SYSTEM_ALERT_WINDOW')
if ($appops -match 'allow') {
    Write-Ok 'sobrepor outros apps: allow'
} elseif ($appops -match 'SYSTEM_ALERT_WINDOW') {
    Write-Bad "sobrepor outros apps: NAO concedido  -> Passo 1 da tela inicial"
} else {
    Write-Note 'sobrepor outros apps: sem registro (nunca solicitado) -> Passo 1'
}

$a11y = Invoke-AdbLine -AdbArgs @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
if ($a11y -match 'screentranslate') {
    Write-Ok 'servico de acessibilidade: ativo'
} else {
    Write-Bad 'servico de acessibilidade: INATIVO  -> Passo 2 (Configuracoes > Acessibilidade)'
}

$globalA11y = Invoke-AdbLine -AdbArgs @('shell', 'settings', 'get', 'secure', 'accessibility_enabled')
if ($globalA11y -ne '1') {
    Write-Note 'acessibilidade esta desligada no aparelho inteiro (accessibility_enabled != 1)'
}

$permDump = Invoke-AdbRaw -AdbArgs @('shell', 'dumpsys', 'package', $Package)
$notif = $permDump | Select-String -Pattern 'POST_NOTIFICATIONS: granted=(true|false)' | Select-Object -First 1
if ($notif) {
    if ($notif -match 'granted=true') { Write-Ok 'notificacoes: concedidas' }
    else { Write-Note 'notificacoes: negadas (a captura funciona, mas sem aviso visivel)' }
}

# ---------------------------------------------------------------- 5. tela

Write-Head 'Tela (compare com o log do overlay)'

(Invoke-AdbRaw -AdbArgs @('shell', 'wm', 'size'))     | Where-Object { $_.Trim() } | ForEach-Object { Write-Info $_.Trim() }
(Invoke-AdbRaw -AdbArgs @('shell', 'wm', 'density'))  | Where-Object { $_.Trim() } | ForEach-Object { Write-Info $_.Trim() }

Write-Info 'A linha "camada WxH px" no logcat deve bater com o "Physical size".'
Write-Info 'Havendo "Override size", o overlay deve seguir o override, nao o physical.'

# ---------------------------------------------------------------- 6. estado

Write-Head 'Estado em execucao'

$pid_ = Invoke-AdbLine -AdbArgs @('shell', 'pidof', $Package)
if ($pid_) { Write-Ok "app rodando (pid $pid_)" } else { Write-Info 'app nao esta em execucao agora' }

$services = Invoke-AdbRaw -AdbArgs @('shell', 'dumpsys', 'activity', 'services', $Package)
if ($services | Select-String -Pattern 'ScreenCaptureService' -Quiet) {
    Write-Ok 'ScreenCaptureService registrado (foreground service ativo)'
} else {
    Write-Info 'captura de tela nao iniciada (normal antes do Passo 3)'
}

# ---------------------------------------------------------------- 7. roteiro

Write-Head 'Roteiro'
Write-Cmd "$script:Adb -s $script:Serial logcat -c"
Write-Cmd "$script:Adb -s $script:Serial logcat -s ScreenTranslate"
Write-Host ''
Write-Info 'Linhas que confirmam cada etapa:'
Write-Info '"servico de acessibilidade conectado"          -> Passo 2'
Write-Info '"captura de tela autorizada" / "ativa em WxH"  -> Passo 3'
Write-Info '"camada WxH px - densidade D"                  -> Passo 6'
Write-Info '"N caixas - tela WxH - (l,t)-(r,b)"            -> Passo 7'
Write-Host ''
Write-Info 'Se as caixas aparecerem deslocadas, me mande essas duas linhas:'
Write-Info '- o "Physical size" acima'
Write-Info '- a linha "N caixas - tela WxH - ..." do logcat'

if ($Logcat) {
    Write-Head 'logcat (Ctrl+C para sair)'
    Invoke-AdbRaw -AdbArgs @('logcat', '-c') | Out-Null
    & $script:Adb -s $script:Serial logcat -s ScreenTranslate
}