<#
  ScreenTranslate - ADB sem fio (Depuracao sem fio do Android 11+).

  Nao usa cabo nem driver USB. Fluxo:

    1. No telefone: Configuracoes > Sistema > Opcoes do desenvolvedor
       > "Depuracao sem fio" > ligar.
    2. Toque em "Parear dispositivo com codigo de pareamento".
       O telefone mostra  DUAS  coisas diferentes:
         - IP:PORTA  e um codigo de 6 digitos   (para parear)
         - IP:PORTA                              (para conectar, na tela anterior)
    3. Rode:

         .\tools\wireless.ps1 -Pair 192.168.1.50:37123 -Code 123456 -Connect 192.168.1.50:40000

  Uso:
    .\tools\wireless.ps1 -Discover                      procura telefones na rede (mDNS)
    .\tools\wireless.ps1 -Pair IP:PORTA -Code 123456    so pareia
    .\tools\wireless.ps1 -Connect IP:PORTA              so conecta
    .\tools\wireless.ps1 -Status                        mostra o estado atual
    .\tools\wireless.ps1 -Reset                         reinicia o servidor adb
#>
[CmdletBinding()]
param(
    [string]$Pair,
    [string]$Code,
    [string]$Connect,
    [switch]$Discover,
    [switch]$Status,
    [switch]$Reset
)

$ErrorActionPreference = 'Stop'

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
function Write-Cmd  { param([string]$Text) Write-Host "    $Text" -ForegroundColor White }

function Resolve-AdbPath {
    $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) |
        Where-Object { $_ -and "$_".Trim() }
    foreach ($root in $roots) {
        $candidate = Join-Path $root 'platform-tools\adb.exe'
        if (Test-Path $candidate) { return $candidate }
    }
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'adb.exe nao encontrado. Instale com: sdkmanager "platform-tools"'
}

$Adb = Resolve-AdbPath
"adb: $Adb"

# No PowerShell 5.1, `2>&1` num executavel nativo gera ErrorRecord; com
# ErrorActionPreference=Stop isso viraria erro fatal (ex.: "daemon not running").
# Por isso baixamos a preferencia aqui e convertemos os registros em texto.
function Adb-Run {
    param([string[]]$AdbArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Adb @AdbArgs 2>&1 | ForEach-Object { "$_" }
    } finally {
        $ErrorActionPreference = $prev
    }
}

if ($Reset) {
    Write-Head 'Reiniciando servidor adb'
    Adb-Run @('kill-server') | Out-Null
    Adb-Run @('start-server') | Out-Null
    Write-Ok 'servidor reiniciado'
}

if ($Discover) {
    Write-Head 'Descoberta na rede (mDNS)'
    $check = (Adb-Run @('mdns', 'check') -join ' ').Trim()
    Write-Info "mdns check: $check"
    Write-Host ''
    $services = Adb-Run @('mdns', 'services')
    if (($services -join '') -match '_adb') {
        $services | ForEach-Object { Write-Ok $_.Trim() }
        Write-Host ''
        Write-Info 'Use o IP:PORTA do servico "_adb-tls-pairing" em -Pair,'
        Write-Info 'e o de "_adb-tls-connect" em -Connect.'
    } else {
        Write-Note 'Nenhum servico encontrado.'
        Write-Info 'Verifique: telefone e PC na MESMA rede; "Depuracao sem fio" ligada no telefone.'
        Write-Info 'Redes de visitante costumam isolar os clientes entre si (AP isolation).'
    }
}

if ($Pair) {
    Write-Head "Pareando com $Pair"
    if (-not $Code) {
        Write-Bad 'Falta -Code. O codigo de 6 digitos aparece na tela do telefone.'
        exit 1
    }
    $out = Adb-Run @('pair', $Pair, $Code)
    $out | ForEach-Object { $_.Trim() } | Where-Object { $_ } | ForEach-Object { Write-Info $_ }
    if (($out -join ' ') -match 'Successfully paired') {
        Write-Ok 'pareado'
    } else {
        Write-Bad 'pareamento falhou'
        Write-Info 'Causas comuns: codigo expirado (regenere no telefone), porta errada,'
        Write-Info 'ou ja pareado antes (nesse caso e so conectar).'
        exit 1
    }
}

if ($Connect) {
    Write-Head "Conectando a $Connect"
    $out = Adb-Run @('connect', $Connect)
    $out | ForEach-Object { $_.Trim() } | Where-Object { $_ } | ForEach-Object { Write-Info $_ }
    if (($out -join ' ') -match 'connected to') {
        Write-Ok 'conectado'
    } else {
        Write-Bad 'conexao falhou'
        Write-Info 'Depois de parear, a PORTA MUDA. Use a porta mostrada na tela principal'
        Write-Info 'de "Depuracao sem fio", nao a porta de pareamento.'
        exit 1
    }
}

# ------------------------------------------------------------------ estado final

Write-Head 'Estado'
$devicesRaw = Adb-Run @('devices', '-l')
$devicesRaw | ForEach-Object { if ($_.Trim()) { Write-Info $_.Trim() } }

$online = @($devicesRaw | Where-Object { $_ -match '^\S+\s+device\b' })
if ($online.Count -gt 0) {
    $serial = ($online[0] -split '\s+')[0]
    Write-Host ''
    Write-Ok "pronto: $serial"
    $model = (& $Adb -s $serial shell getprop ro.product.model 2>&1 | Out-String).Trim()
    $rel   = (& $Adb -s $serial shell getprop ro.build.version.release 2>&1 | Out-String).Trim()
    $sdk   = (& $Adb -s $serial shell getprop ro.build.version.sdk 2>&1 | Out-String).Trim()
    Write-Ok "modelo: $model | Android $rel (API $sdk)"

    Write-Host ''
    Write-Info 'Proximo passo - pre-voo completo (instala e acompanha o log):'
    Write-Cmd '.\tools\doctor.ps1 -Install -Logcat'
    Write-Host ''
    Write-Info 'Se o Wi-Fi cair, basta reconectar (nao precisa parear de novo):'
    Write-Cmd ".\tools\wireless.ps1 -Connect $Connect"
} else {
    Write-Host ''
    Write-Note 'nenhum aparelho em estado "device" ainda'
    Write-Info 'Se aparece "offline" ou nada, rode: .\tools\wireless.ps1 -Reset'
}