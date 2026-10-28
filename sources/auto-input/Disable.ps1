#Requires -Version 5.1
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$entryName = 'MicBridge-AutoInput'
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$expectedFolder = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'MicBridge\AutoInput')).TrimEnd('\')
$actualFolder = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\')
if (![string]::Equals($actualFolder, $expectedFolder, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Run Disable.ps1 from the installed LOCALAPPDATA\MicBridge\AutoInput folder. No changes were made.'
}
$exe = Join-Path $actualFolder 'MicBridge-AutoInput.exe'
if (!(Test-Path -LiteralPath $exe -PathType Leaf)) { throw 'Installed helper executable is missing. Recovery files were retained.' }

function Read-StartupValue {
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Software\Microsoft\Windows\CurrentVersion\Run', $false)
    if ($null -eq $key) { return $null }
    try {
        if ($key.GetValueNames() -contains $entryName) { return [string]$key.GetValue($entryName) }
        return $null
    } finally { $key.Close() }
}
$startupValue = Read-StartupValue
if ($null -ne $startupValue) {
    if ($startupValue -notmatch '\A[ \t]*"([^"\r\n]+)"(?:[ \t]+[^\r\n]*)?\z') {
        throw 'The startup value is not owned by this installation. No changes were made.'
    }
    $startupExe = [IO.Path]::GetFullPath($Matches[1])
    if (![string]::Equals($startupExe, $exe, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The startup value points to another executable. No changes were made.'
    }
}
$helpers = @(Get-Process -Name 'MicBridge-AutoInput' -ErrorAction SilentlyContinue)
foreach ($helper in $helpers) {
    if (![string]::Equals($helper.Path, $exe, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Another helper process is running outside this installation. No changes were made.'
    }
}
if ($helpers.Count -gt 1) { throw 'Multiple helper processes are present. No changes were made.' }
# No scheduled tasks are created, disabled, or removed by this add-on.
if ($null -ne $startupValue) {
    $current = Read-StartupValue
    if ($current -cne $startupValue) { throw 'Startup value changed while disabling. No changes were made.' }
    Remove-ItemProperty -LiteralPath $runKey -Name $entryName -ErrorAction Stop
}
if ($helpers.Count -eq 1) {
    $stop = Start-Process -FilePath $exe -ArgumentList '--stop' -WindowStyle Hidden -Wait -PassThru
    if ($stop.ExitCode -ne 0) { throw 'The graceful stop request failed. Recovery files were retained.' }
    if (!$helpers[0].WaitForExit(10000)) { throw 'The helper is still stopping. Do not force-kill it; recovery files were retained.' }
}
if (@(Get-Process -Name 'MicBridge-AutoInput' -ErrorAction SilentlyContinue).Count -ne 0) {
    throw 'A helper process appeared before restoration. Recovery files were retained; retry when it is stopped.'
}
$restore = Start-Process -FilePath $exe -ArgumentList '--restore' -WindowStyle Hidden -Wait -PassThru
if ($restore.ExitCode -ne 0) {
    throw 'Original microphone restoration is still pending. Reconnect the original device and run this script again. All recovery files were retained.'
}
if (Test-Path -LiteralPath (Join-Path $actualFolder 'original-input.json')) {
    throw 'A pending recovery journal remains. Files were retained; do not delete the installation folder.'
}
'Automatic microphone switching is disabled and pending originals were restored. Installation files remain available for recovery.'
