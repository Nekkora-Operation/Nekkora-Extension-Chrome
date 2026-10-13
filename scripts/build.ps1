<#
.SYNOPSIS
  Compila o motor Java (core, host, laboratório) e executa os testes unitários Java e da extensão.
.PARAMETER SkipTests  Somente compila.
#>
[CmdletBinding()]
param([switch]$SkipTests)
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
  Write-Host ''
  Write-Host 'Build concluído.'
  Write-Host "  Host nativo : java-core\native-host\target\nekkora-native-host.jar"
  Write-Host "  Laboratório : java-core\lab-server\target\nekkora-lab-server.jar"
  Write-Host "  Extensão    : chrome-extension\ (carregar sem compactação)"
} finally { Pop-Location }
