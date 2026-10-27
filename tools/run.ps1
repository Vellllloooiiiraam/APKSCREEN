<#
.SYNOPSIS
  Sobe o overlay de traducao num aparelho/emulador ja conectado, abre uma URL
  (opcional) e recolhe a evidencia: screenshot + logcat.

.DESCRIPTION
  Existe porque a sequencia manual tem duas armadilhas que ja custaram varias
  rodadas de depuracao:

    1. `adb shell am force-stop <pacote>` LIMPA o servico de acessibilidade da
       lista de servicos habilitados. Se o servico for reabilitado e o app for
       forcado a parar em seguida, ele some de novo.
    2. O servico leva alguns segundos para vincular. Se o app abrir antes disso,
       a janela cai para TYPE_APPLICATION_OVERLAY (2038), que o sistema limita a
       80% de opacidade - e o texto original transparece sob a traducao.

  Por isso a ordem aqui e: force-stop -> reabilitar -> ESPERAR o vinculo ->
  abrir o app -> abrir a URL.

.EXAMPLE
  .\run.ps1 -Serial emulator-5554 -Url 'https://en.wikipedia.org/wiki/Android_version_history' -Shot emu.png
  .\run.ps1 -Serial 192.168.1.11:42139 -Apk ..\app\build\outputs\apk\debug\app-debug.apk -Shot phone.png
#>
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Apk = "",
    [string]$Url = "",
    [string]$Component = "",
    [int]$Wait = 25,
    [string]$Shot = "",
    [string]$Log = "",
    [switch]$Provision,
    [switch]$NoOverlay
)

$ErrorActionPreference = 'Continue'
$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$app = "com.example.screentranslate"
$component = "$app/.a11y.ScreenTextAccessibilityService"
$tmp = "$env:LOCALAPPDATA\Temp\opencode"
if (-not (Test-Path $tmp)) { New-Item -ItemType Directory -Path $tmp | Out-Null }

function Adb { & $adb -s $Serial @args 2>&1 }

if ($Apk -ne "") {
    Adb shell am force-stop $app | Out-Null
    "instalando $Apk ..."
    Adb install -r $Apk | Select-Object -Last 1
    # Trocar o APK faz o sistema reavaliar os servicos de acessibilidade e, as vezes,
    # limpar a lista um instante DEPOIS da instalacao terminar. Deixe o pacote
    # assentar antes de escrever a lista.
    Start-Sleep -Seconds 2
}

# 1. Parar o app (isto tambem derruba o servico de acessibilidade).
Adb shell am force-stop $app | Out-Null

# 1b. Conceder os appops que o HyperOS nao deixa passar pelo `install -g`.
#     SYSTEM_ALERT_WINDOW e o que permite a janela comum (2038) como ultimo recurso;
#     PROJECT_MEDIA e a captura de tela do modo OCR/hibrido.
if ($Provision) {
    foreach ($op in @('SYSTEM_ALERT_WINDOW', 'PROJECT_MEDIA', 'ACCESS_RESTRICTED_SETTINGS')) {
        $r = Adb shell appops set $app $op allow
        "appops ${op}: $r"
    }
}

# 2. Reabilitar o servico de acessibilidade: e ele que empresta o token de
#    TYPE_ACCESSIBILITY_OVERLAY.
Adb shell settings put secure enabled_accessibility_services $component | Out-Null
Adb shell settings put secure accessibility_enabled 1 | Out-Null

# 3. Esperar o vinculo de verdade, em vez de dormir um numero magico de segundos.
#
#    Se em 20 s o servico nao apareceu, o mais provavel nao e demora: e a lista ter
#    sido limpa de novo (troca de APK, outro servico de acessibilidade entrando em
#    cena). Reaplicar de tempo em tempo e barato e resolve o caso; sem isso o overlay
#    sobe como 2038 e o texto original transparece.
$bound = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 1
    $svc = Adb shell dumpsys accessibility | Select-String -Pattern 'Bound services:\{Service'
    if ($svc) { $bound = $true; "serviço vinculado em $($i + 1)s"; break }
    if ($i % 3 -eq 2) {
        Adb shell settings put secure enabled_accessibility_services $component | Out-Null
        Adb shell settings put secure accessibility_enabled 1 | Out-Null
    }
}
if (-not $bound) { "AVISO: o serviço nao vinculou - o overlay vai cair para 2038" }

if ($Url -ne "") {
    Adb shell am force-stop com.android.chrome | Out-Null
}
Adb logcat -c

if (-not $NoOverlay) {
    Adb shell am start -n "$app/.MainActivity" --ez auto true --ez overlay true | Out-Null
    Start-Sleep -Seconds 6
}

if ($Url -ne "") {
    Adb shell am start -a android.intent.action.VIEW -d $Url | Out-Null
}

if ($Component -ne "") {
    Adb shell am start -n $Component | Out-Null
}

Start-Sleep -Seconds $Wait

if ($Shot -ne "") {
    Adb shell screencap -p /sdcard/$Shot | Out-Null
    Adb pull /sdcard/$Shot "$tmp\$Shot" | Out-Null
    "screenshot: $tmp\$Shot"
}

$logPath = if ($Log -ne "") { "$tmp\$Log" } else { "$tmp\run-$($Serial -replace '[:.]', '_').txt" }
Adb logcat -d -s ScreenTranslate > $logPath
"log: $logPath"

"--- tipo de janela ---"
Get-Content $logPath -Encoding UTF8 | Select-String -Pattern 'camada ancorada adicionada|aguardando o servico|janela comum' |
    ForEach-Object { $_.ToString() -replace '^.*ScreenTranslate: ', '  ' } | Select-Object -First 3
"--- bolha ---"
Get-Content $logPath -Encoding UTF8 | Select-String -Pattern 'bolha em' |
    ForEach-Object { $_.ToString() -replace '^.*ScreenTranslate: ', '  ' }
"--- traducao ---"
Get-Content $logPath -Encoding UTF8 | Select-String -Pattern 'eco corrigido|eco persistente|blocos unicos|blocos únicos|caixas · tela' |
    ForEach-Object {
        $s = $_.ToString() -replace '^.*ScreenTranslate: ', '  '
        if ($s.Length -gt 150) { $s = $s.Substring(0, 150) }
        $s
    } | Select-Object -First 8
