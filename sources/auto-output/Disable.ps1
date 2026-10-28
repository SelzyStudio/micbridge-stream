#Requires -Version 5.1
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$entryName = 'MicBridge-AutoOutput'
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$expectedFolder = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'MicBridge\AutoOutput')).TrimEnd('\')
$actualFolder = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\')
if (![string]::Equals($actualFolder, $expectedFolder, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Run Disable.ps1 from the installed LOCALAPPDATA\MicBridge\AutoOutput folder. No changes were made.'
}
$exe = Join-Path $actualFolder 'MicBridge-AutoOutput.exe'
if (!(Test-Path -LiteralPath $exe -PathType Leaf)) { throw 'Installed output helper is missing. Recovery files were retained.' }
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
$helpers = @(Get-Process -Name 'MicBridge-AutoOutput' -ErrorAction SilentlyContinue)
foreach ($helper in $helpers) {
    if (![string]::Equals($helper.Path, $exe, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Another output helper is running outside this installation. No changes were made.'
    }
}
if ($helpers.Count -gt 1) { throw 'Multiple output helpers are present. No changes were made.' }
$statusPath = Join-Path $actualFolder 'status.json'
if ($helpers.Count -eq 1 -and (Test-Path -LiteralPath $statusPath -PathType Leaf)) {
    $status = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json
    if ($status.watcherPid -eq $helpers[0].Id -and $status.active -and
        [DateTime]::UtcNow.Subtract([DateTime]::Parse($status.checkedAtUtc)).TotalSeconds -lt 15) {
        throw 'End the remote streaming session before disabling output switching. No changes were made.'
    }
}
# This add-on never changes microphone startup entries or scheduled tasks.
if ($null -ne $startupValue) {
    if ((Read-StartupValue) -cne $startupValue) { throw 'Startup value changed while disabling. No changes were made.' }
    Remove-ItemProperty -LiteralPath $runKey -Name $entryName -ErrorAction Stop
}
if ($helpers.Count -eq 1) {
    $stop = Start-Process -FilePath $exe -ArgumentList '--stop' -WindowStyle Hidden -Wait -PassThru
    if ($stop.ExitCode -ne 0) { throw 'The graceful output stop request failed. Recovery files were retained.' }
    if (!$helpers[0].WaitForExit(10000)) { throw 'The output helper is still stopping. Do not force-kill it; recovery files were retained.' }
}
if (@(Get-Process -Name 'MicBridge-AutoOutput' -ErrorAction SilentlyContinue).Count -ne 0) {
    throw 'An output helper appeared before restoration. Recovery files were retained; retry when it is stopped.'
}
$restore = Start-Process -FilePath $exe -ArgumentList '--restore' -WindowStyle Hidden -Wait -PassThru
if ($restore.ExitCode -ne 0) {
    throw 'Original output restoration is still pending. Reconnect the original output device and retry. All recovery files were retained.'
}
if (Test-Path -LiteralPath (Join-Path $actualFolder 'original-output.json')) {
    throw 'A pending output recovery journal remains. Do not delete the installation folder.'
}
'Automatic output switching is disabled and pending originals were restored. Files remain available for recovery.'
