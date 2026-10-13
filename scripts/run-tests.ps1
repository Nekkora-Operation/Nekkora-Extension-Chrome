<#
.SYNOPSIS
  Executa a suíte de homologação.
.DESCRIPTION
  -Suite unit         testes unitários Java + contrato (sem navegador) + extensão (Node)
  -Suite integration  homologação no Chrome for Testing (instala o host, baixa o CfT em .tools/)
  -Suite all          ambos (padrão)
  Relatórios: reports/test-matrix-integration.md|json, reports/performance.md|json, reports/evidence/*.png
.PARAMETER Headless  Executa o Chrome sem janela (por padrão a janela é visível).
.PARAMETER Test      Filtro de classes de integração (ex.: "NegativeCasesIT,ResolutionIT").
#>
[CmdletBinding()]
param(
  [ValidateSet('unit', 'integration', 'all')][string]$Suite = 'all',
  [switch]$Headless,
  [string]$Test
)
$ErrorActionPreference = 'Stop'
$Repo = Resolve-Path (Join-Path $PSScriptRoot '..')
Push-Location $Repo
try {
  if ($Suite -in 'unit', 'all') {
    & (Join-Path $PSScriptRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Testes unitários falharam' }
  } else {
    & .\mvnw.cmd -q install -DskipTests
  }
  if ($Suite -in 'integration', 'all') {
    # PowerShell 5.1 divide argumentos '-Dchave.com.ponto=valor' ao chamar .cmd: usa cmd /c com a linha completa.
    $line = "`"$Repo\mvnw.cmd`" -f `"$Repo\pom.xml`" -pl integration-tests -Pintegration verify -Dnekkora.headless=$(([bool]$Headless).ToString().ToLower())"
    if ($Test) { $line += " -Dit.test=$Test" }
    Write-Host "> $line"
    & cmd.exe /c "`"$line`""
    $code = $LASTEXITCODE
    Write-Host ''
    Write-Host 'Relatórios: reports\test-matrix-integration.md, reports\performance.md, reports\evidence\'
    if ($code -ne 0) { throw "Homologação com falhas (código $code)" }
  }
} finally { Pop-Location }
