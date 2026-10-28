#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ApolloLog = 'C:\Program Files\Apollo\config\sunshine.log',
    [switch]$ValidateOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$expectedHash = 'DD323E9238214D617503F49D0E394CC5A8A9D4B7043BDEE44ED7C61B4BE5AA68'
$entryName = 'MicBridge-AutoInput'
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$payload = @('MicBridge-AutoInput.exe', 'CoreAudio.cs', 'MicrophoneAutoSwitch.cs', 'Tests.cs',
    'Build.ps1', 'LICENSE', 'NOTICE.md', 'README.ko.md', 'README.en.md', 'Disable.ps1')

function Assert-SafePath([string]$Value, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.IndexOfAny([char[]]"`r`n`0`"") -ge 0) {
        throw "$Label must be a nonempty path without quotes or line breaks."
    }
    return [IO.Path]::GetFullPath($Value)
}
function Read-StartupValue {
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Software\Microsoft\Windows\CurrentVersion\Run', $false)
    if ($null -eq $key) { return $null }
    try {
        if ($key.GetValueNames() -contains $entryName) { return [string]$key.GetValue($entryName) }
        return $null
    } finally { $key.Close() }
}
function Get-Helpers { return ,@(Get-Process -Name 'MicBridge-AutoInput' -ErrorAction SilentlyContinue) }

if (![Environment]::Is64BitOperatingSystem -or ![Environment]::Is64BitProcess) {
    throw 'Use 64-bit Windows PowerShell on Windows x64.'
}
$localRoot = Assert-SafePath $env:LOCALAPPDATA 'LOCALAPPDATA'
$folder = Assert-SafePath (Join-Path $localRoot 'MicBridge\AutoInput') 'Installation folder'
$ApolloLog = Assert-SafePath $ApolloLog 'ApolloLog'
if (!(Test-Path -LiteralPath $ApolloLog -PathType Leaf)) {
    throw 'Apollo log not found. Start the supported Apollo host once, or supply -ApolloLog with its current sunshine.log path.'
}
# Open for reading only: never rotate, write or change the host log/configuration.
$probe = [IO.File]::Open($ApolloLog, [IO.FileMode]::Open, [IO.FileAccess]::Read,
    ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
$probe.Dispose()
foreach ($name in $payload) {
    if (!(Test-Path -LiteralPath (Join-Path $PSScriptRoot $name) -PathType Leaf)) { throw "Missing package file: $name" }
}
$sourceExe = Join-Path $PSScriptRoot 'MicBridge-AutoInput.exe'
if ((Get-FileHash -LiteralPath $sourceExe -Algorithm SHA256).Hash -ne $expectedHash) {
    throw 'The helper executable does not match this release. No installation changes were made.'
}
if ($null -ne (Read-StartupValue)) { throw 'The MicBridge-AutoInput startup value already exists. Existing installations are not replaced.' }
if ((Get-Helpers).Count -ne 0) { throw 'A MicBridge-AutoInput process is already running. Existing installations are not replaced.' }
if (Test-Path -LiteralPath $folder) {
    $item = Get-Item -LiteralPath $folder -Force
    if (!$item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'The installation target is not an ordinary directory. No changes were made.'
    }
    if (@(Get-ChildItem -LiteralPath $folder -Force).Count -ne 0) {
        throw 'The installation folder is not empty. Existing installation/recovery files are not replaced.'
    }
}
if ($ValidateOnly) {
    [pscustomobject]@{ validated = $true; installFolder = $folder; apolloLog = $ApolloLog; helperSha256 = $expectedHash; changesMade = $false }
    return
}

$exe = Join-Path $folder 'MicBridge-AutoInput.exe'
$arguments = '--watch "' + $ApolloLog + '"'
$startupValue = '"' + $exe + '" ' + $arguments
$startupCreated = $false
$started = $null
try {
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    foreach ($name in $payload) {
        $inputFile = [IO.File]::OpenRead((Join-Path $PSScriptRoot $name))
        try {
            # CreateNew refuses an existing destination even if another setup raced the preflight.
            $outputFile = [IO.File]::Open((Join-Path $folder $name), [IO.FileMode]::CreateNew,
                [IO.FileAccess]::Write, [IO.FileShare]::None)
            try { $inputFile.CopyTo($outputFile); $outputFile.Flush($true) } finally { $outputFile.Dispose() }
        } finally { $inputFile.Dispose() }
    }
    if ((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash -ne $expectedHash) { throw 'Installed executable integrity check failed.' }
    # Recheck after copying; never overwrite another setup's startup value.
    if ($null -ne (Read-StartupValue) -or (Get-Helpers).Count -ne 0) { throw 'Another helper/startup value appeared during setup.' }
    if (!(Test-Path -LiteralPath $runKey)) { New-Item -Path $runKey -Force | Out-Null }
    New-ItemProperty -LiteralPath $runKey -Name $entryName -Value $startupValue -PropertyType String -ErrorAction Stop | Out-Null
    $startupCreated = $true
    $started = Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $folder -WindowStyle Hidden -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    $status = $null
    do {
        $started.Refresh()
        if ($started.HasExited) { throw 'The helper exited before startup could be verified.' }
        if (![string]::Equals($started.Path, $exe, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected helper process path.' }
        $statusPath = Join-Path $folder 'status.json'
        if (Test-Path -LiteralPath $statusPath -PathType Leaf) {
            try { $candidate = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json } catch { $candidate = $null }
            if ($null -ne $candidate -and $candidate.watcherPid -eq $started.Id) {
                if ($candidate.error) { throw ('Helper startup error: ' + $candidate.error) }
                $status = $candidate
                break
            }
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($null -eq $status) { throw 'Timed out waiting for this helper process to report a healthy status.' }
    $receipt = [ordered]@{
        installedAtUtc = [DateTime]::UtcNow.ToString('o'); helperSha256 = $expectedHash
        installFolder = $folder; apolloLog = $ApolloLog; startupMode = 'current-user-run-key'
        watcherPid = $started.Id; captureOnly = $true; restoreDelayMs = 2000
        hostFilesChanged = $false; phoneAppChanged = $false
    }
    $receipt | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $folder 'installation.json') -Encoding UTF8
    $receipt | ConvertTo-Json
} catch {
    $failure = $_
    # Roll back only the exact startup value that this invocation created.
    if ($startupCreated -and (Read-StartupValue) -ceq $startupValue) {
        Remove-ItemProperty -LiteralPath $runKey -Name $entryName -ErrorAction SilentlyContinue
    }
    if ($null -ne $started) {
        try {
            $started.Refresh()
            $helpers = Get-Helpers
            if (!$started.HasExited -and $helpers.Count -eq 1 -and $helpers[0].Id -eq $started.Id -and
                [string]::Equals($started.Path, $exe, [StringComparison]::OrdinalIgnoreCase)) {
                $stop = Start-Process -FilePath $exe -ArgumentList '--stop' -WindowStyle Hidden -Wait -PassThru
                if ($stop.ExitCode -ne 0) { Write-Warning 'The helper stop request failed; do not force-kill it.' }
                if (!$started.WaitForExit(10000)) { Write-Warning 'The helper is still stopping; do not force-kill it.' }
            }
        } catch { Write-Warning ('Automatic graceful stop could not be verified: ' + $_.Exception.Message) }
    }
    Write-Warning 'Setup did not complete. Files and any original-input.json recovery journal were retained. Run the installed Disable.ps1 to finish recovery before retrying. Do not delete recovery files.'
    throw $failure
}
