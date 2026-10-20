#Requires -Version 5.1
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$exe = Join-Path $root 'sunshine.exe'
try {
    $service = Get-Service -Name ApolloService -ErrorAction SilentlyContinue
    if ($service -and $service.Status -eq 'Running') {
        throw 'ApolloService is already running. Use the existing host. This package will not stop or replace it.'
    }
    if (Get-Process -Name sunshine -ErrorAction SilentlyContinue) {
        throw 'An Apollo/Sunshine host is already running. Close it deliberately before starting another host.'
    }
    foreach ($port in @(47984,47989,47990,48010)) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
            throw "TCP port $port is already in use. No process or service was changed."
        }
    }
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw 'sunshine.exe is missing. Extract the whole portable ZIP or use the setup package.' }
    $configRoot = Join-Path $root 'config'
    $configPath = Join-Path $configRoot 'sunshine.conf'
    if (-not (Test-Path -LiteralPath $configPath)) {
        New-Item -ItemType Directory -Path $configRoot -Force | Out-Null
        $template = [IO.File]::ReadAllBytes((Join-Path $root 'config-template\sunshine.conf'))
        $file = [IO.File]::Open($configPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read)
        try { $file.Write($template, 0, $template.Length) } finally { $file.Dispose() }
    }
    Write-Host 'Starting Apollo Mic Local. Setup: https://localhost:47990'
    Write-Host 'This launcher does not install drivers, register services, open firewall ports or change default audio devices.'
    Push-Location -LiteralPath $root
    try { & $exe; exit $LASTEXITCODE } finally { Pop-Location }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Yellow
    Write-Host "Guide: $(Join-Path $root 'README.ko.txt')"
    Read-Host 'Press Enter to close' | Out-Null
    exit 1
}
