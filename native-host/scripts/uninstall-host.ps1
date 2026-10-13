<#
.SYNOPSIS
  Remove o registro do Native Messaging Host e o diretório de instalação (dados/logs preservados
  salvo -RemoveData).
#>
[CmdletBinding()]
param(
  [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'Nekkora\ExtensionChrome\host'),
  [switch]$RemoveData
)
$ErrorActionPreference = 'Stop'
$HostName = 'com.nekkora.extension_chrome'
foreach ($b in @('Google\Chrome', 'Google\Chrome for Testing', 'Chromium')) {
  $key = "HKCU:\Software\$b\NativeMessagingHosts\$HostName"
  if (Test-Path $key) {
    Remove-Item -Path $key -Force
    Write-Host "Removido: $key"
  }
}
if (Test-Path $InstallDir) {
  Remove-Item -Recurse -Force $InstallDir
  Write-Host "Removido: $InstallDir"
}
if ($RemoveData) {
  $data = Join-Path $env:LOCALAPPDATA 'Nekkora\ExtensionChrome'
  if (Test-Path $data) {
    Remove-Item -Recurse -Force $data
    Write-Host "Removido: $data"
  }
}
Write-Host 'Native Messaging Host desinstalado.'
