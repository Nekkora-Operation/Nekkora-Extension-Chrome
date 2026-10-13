<#
.SYNOPSIS
  Inicia o laboratório de simulação em http://127.0.0.1:<porta>/lab/index.html (Ctrl+C encerra).
.PARAMETER Port  Porta local (padrão 8765).
#>
[CmdletBinding()]
param([int]$Port = 8765)
$ErrorActionPreference = 'Stop'
$Repo = Resolve-Path (Join-Path $PSScriptRoot '..')
$Jar = Join-Path $Repo 'java-core\lab-server\target\nekkora-lab-server.jar'
if (-not (Test-Path $Jar)) {
  Push-Location $Repo
  try {
    & .\mvnw.cmd -q -pl java-core/lab-server -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao compilar o servidor do laboratório' }
  } finally { Pop-Location }
}
& java -jar $Jar $Port (Join-Path $Repo 'test-lab\pages')
