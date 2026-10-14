<#
.SYNOPSIS
  Compila o motor Java (core, host, laboratório) e executa os testes unitários Java e da extensão.
.PARAMETER SkipTests  Somente compila.
.PARAMETER Mode       DEV (padrão: extensão em chrome-extension\) ou PRD (gera também dist\prd\ com BUILD_MODE='PRD',
                      sem o harness de testes). PRD não autoriza distribuição nem inspeção de sites reais.
#>
[CmdletBinding()]
param([switch]$SkipTests, [ValidateSet('DEV', 'PRD')][string]$Mode = 'DEV')
$ErrorActionPreference = 'Stop'
$Repo = Resolve-Path (Join-Path $PSScriptRoot '..')
Push-Location $Repo
try {
  $mvnArgs = @('-q', 'install')
  if ($SkipTests) { $mvnArgs += '-DskipTests' }
  Write-Host "> mvnw $($mvnArgs -join ' ')"
  & .\mvnw.cmd @mvnArgs
  if ($LASTEXITCODE -ne 0) { throw "Falha no build Java ($LASTEXITCODE)" }
  if (-not $SkipTests) {
    if (Get-Command node -ErrorAction SilentlyContinue) {
      Push-Location chrome-extension
      try {
        Write-Host '> npm test (extensão)'
        & npm test --silent
        if ($LASTEXITCODE -ne 0) { throw "Falha nos testes da extensão ($LASTEXITCODE)" }
      } finally { Pop-Location }
    } else {
      Write-Warning 'Node.js não encontrado: testes unitários da extensão ignorados.'
    }
  }
  if ($Mode -eq 'PRD') {
    $dist = Join-Path $Repo 'dist\prd'
    if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
    New-Item -ItemType Directory -Force $dist | Out-Null
    Copy-Item (Join-Path $Repo 'chrome-extension\manifest.json') $dist
    Copy-Item -Recurse (Join-Path $Repo 'chrome-extension\icons') $dist
    Copy-Item -Recurse (Join-Path $Repo 'chrome-extension\src') $dist
    # O harness de homologação não faz parte da construção PRD.
    Remove-Item -Force (Join-Path $dist 'src\ui\harness.html'), (Join-Path $dist 'src\ui\harness.js')
    $modeFile = Join-Path $dist 'src\shared\build-mode.js'
    $text = [System.IO.File]::ReadAllText($modeFile) -replace "BUILD_MODE = 'DEV'", "BUILD_MODE = 'PRD'"
    [System.IO.File]::WriteAllText($modeFile, $text, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "  Extensão PRD: $dist"
  }
  Write-Host ''
  Write-Host 'Build concluído.'
  Write-Host "  Host nativo : java-core\native-host\target\nekkora-native-host.jar"
  Write-Host "  Laboratório : java-core\lab-server\target\nekkora-lab-server.jar"
  Write-Host "  Extensão    : chrome-extension\ (carregar sem compactação)"
} finally { Pop-Location }
