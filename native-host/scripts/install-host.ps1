<#
.SYNOPSIS
  Instala o Native Messaging Host Java do Nekkora Extension Chrome para o usuário atual (Windows).

.DESCRIPTION
  1. Compila o host (se necessário) e copia o JAR para o diretório de instalação.
  2. Gera nekkora-host.cmd apontando para um java.exe 21+ resolvido NA INSTALAÇÃO (o Chrome não
     herda o PATH do terminal de forma confiável).
  3. Gera o manifesto do host com allowed_origins = chrome-extension://<id>/, onde <id> é calculado a
     partir da chave pública em chrome-extension/manifest.json.
  4. Registra o manifesto em HKCU para Google Chrome, Chrome for Testing e Chromium.
  Não requer privilégios de administrador. Não altera nada fora de HKCU e do diretório de instalação.

.PARAMETER ExtensionId   Força um id de extensão (padrão: calculado do manifest.json).
.PARAMETER JavaExe       Caminho do java.exe 21+ (padrão: JAVA_HOME ou java no PATH).
.PARAMETER InstallDir    Destino (padrão: %LOCALAPPDATA%\Nekkora\ExtensionChrome\host).
.PARAMETER SkipBuild     Não compila; usa o JAR existente.
#>
[CmdletBinding()]
param(
  [string]$ExtensionId,
  [string]$JavaExe,
  [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'Nekkora\ExtensionChrome\host'),
  [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$HostName = 'com.nekkora.extension_chrome'
$Repo = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$Jar = Join-Path $Repo 'java-core\native-host\target\nekkora-native-host.jar'

function Resolve-Java {
  param([string]$Explicit)
  $candidates = @()
  if ($Explicit) { $candidates += $Explicit }
  if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
  $cmd = Get-Command java -ErrorAction SilentlyContinue
  if ($cmd) { $candidates += $cmd.Source }
  foreach ($c in $candidates) {
    if (-not (Test-Path $c)) { continue }
    $out = & $c -XshowSettings:properties -version 2>&1 | Out-String
    if ($out -match 'java\.specification\.version = (\d+)') {
      if ([int]$Matches[1] -ge 21) { return (Resolve-Path $c).Path }
      Write-Warning "Ignorando $c (Java $($Matches[1]); requer 21+)"
    }
  }
  throw 'Java 21+ não encontrado. Instale um JDK 21 ou informe -JavaExe / JAVA_HOME.'
}

$java = Resolve-Java $JavaExe
Write-Host "Java: $java"

if (-not $SkipBuild -or -not (Test-Path $Jar)) {
  Write-Host 'Compilando o host (mvnw package)...'
  Push-Location $Repo
  try {
    & .\mvnw.cmd -q -pl java-core/native-host -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Falha na compilação (código $LASTEXITCODE)" }
  } finally { Pop-Location }
}
if (-not (Test-Path $Jar)) { throw "JAR não encontrado: $Jar" }

if (-not $ExtensionId) {
  $manifest = Join-Path $Repo 'chrome-extension\manifest.json'
  $ExtensionId = (& $java -cp $Jar com.nekkora.extension.host.tools.ExtensionId $manifest | Out-String).Trim()
}
if ($ExtensionId -notmatch '^[a-p]{32}$') { throw "Id de extensão inválido: '$ExtensionId'" }
Write-Host "Extensão autorizada: $ExtensionId"

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Copy-Item $Jar (Join-Path $InstallDir 'nekkora-native-host.jar') -Force

$launcher = Join-Path $InstallDir 'nekkora-host.cmd'
@"
@echo off
rem Gerado por install-host.ps1. stdout pertence ao protocolo Native Messaging: nada de echo aqui.
"$java" -Xss8m -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "%~dp0nekkora-native-host.jar" %*
"@ | Set-Content -Path $launcher -Encoding ascii

$manifestPath = Join-Path $InstallDir "$HostName.json"
$hostManifest = [ordered]@{
  name            = $HostName
  description     = 'Nekkora Studio - motor Java 21 de interpretacao de paginas (Native Messaging)'
  path            = $launcher
  type            = 'stdio'
  allowed_origins = @("chrome-extension://$ExtensionId/")
}
($hostManifest | ConvertTo-Json -Depth 3) | Set-Content -Path $manifestPath -Encoding utf8

$browsers = @('Google\Chrome', 'Google\Chrome for Testing', 'Chromium')
foreach ($b in $browsers) {
  $key = "HKCU:\Software\$b\NativeMessagingHosts\$HostName"
  New-Item -Path $key -Force | Out-Null
  Set-Item -Path $key -Value $manifestPath
  Write-Host "Registrado: $key"
}

Write-Host ''
Write-Host 'Native Messaging Host instalado.'
Write-Host "  Manifesto : $manifestPath"
Write-Host "  Launcher  : $launcher"
Write-Host "  Logs      : $(Join-Path $env:LOCALAPPDATA 'Nekkora\ExtensionChrome\logs')"
