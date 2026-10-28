#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ApolloLog = 'C:\Program Files\Apollo\config\sunshine.log',
    [switch]$ValidateOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$entryName = 'MicBridge-AutoOutput'
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$payload = @('Disable.ps1', 'MicBridge-AutoOutput.exe', 'AssemblyInfo.cs', 'CoreAudio.cs', 'SessionSupport.cs',
    'OutputRouting.cs', 'OutputTests.cs', 'Program.cs', 'Build.ps1', 'LICENSE', 'NOTICE.md',
    'README.ko.md', 'README.en.md', 'VALIDATION.json', 'PACKAGE.json')

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
function Get-Helpers { return ,@(Get-Process -Name 'MicBridge-AutoOutput' -ErrorAction SilentlyContinue) }
function Assert-DisconnectedLog {
    $file = [IO.FileInfo]::new($ApolloLog)
    if (!$file.Exists -or $file.Length -gt 64MB) { throw 'Apollo log is missing or larger than the supported 64 MiB limit.' }
    $stream = [IO.File]::Open($ApolloLog, [IO.FileMode]::Open, [IO.FileAccess]::Read,
        ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    try {
        $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $true)
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $stream.Dispose() }
    $active = $false
    $seen = $false
    foreach ($line in ($text -split "`n")) {
        $marker = $line.IndexOf(']: Info: ', [StringComparison]::Ordinal)
        if ($marker -lt 0) { continue }
        $message = $line.Substring($marker + 9).TrimEnd("`r")
        if ($message.StartsWith('Apollo version:') -or $message.StartsWith('Sunshine version:')) {
            $active = $false; $seen = $true
        } elseif ($message.StartsWith('Session resuming for app [') -or $message -eq 'CLIENT CONNECTED') {
            $active = $true; $seen = $true
        } elseif ($message.StartsWith('Session pausing for app [') -or
            $message.StartsWith('Session already stopped, do not run pause commands.') -or $message -eq 'Process terminated' -or
            ($message.StartsWith('Terminating app [') -and $message.Contains('when all clients are disconnected'))) {
            $active = $false; $seen = $true
        }
    }
    if (!$seen) { throw 'No supported Apollo session lifecycle was found. Start the supported host once before installation.' }
    if ($active) { throw 'End the remote streaming session before installing the output helper. No startup entry was registered.' }
}
function Copy-NewPayloadFile([string]$Name) {
    $inputFile = [IO.File]::OpenRead((Join-Path $PSScriptRoot $Name))
    try {
        $outputFile = [IO.File]::Open((Join-Path $folder $Name), [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $inputFile.CopyTo($outputFile); $outputFile.Flush($true) } finally { $outputFile.Dispose() }
    } finally { $inputFile.Dispose() }
}

if (![Environment]::Is64BitOperatingSystem -or ![Environment]::Is64BitProcess) {
    throw 'Use 64-bit Windows PowerShell on Windows x64.'
}
$localRoot = Assert-SafePath $env:LOCALAPPDATA 'LOCALAPPDATA'
$folder = Assert-SafePath (Join-Path $localRoot 'MicBridge\AutoOutput') 'Installation folder'
$ApolloLog = Assert-SafePath $ApolloLog 'ApolloLog'
foreach ($name in $payload) {
    if (!(Test-Path -LiteralPath (Join-Path $PSScriptRoot $name) -PathType Leaf)) { throw "Missing package file: $name" }
}
$package = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'PACKAGE.json') -Raw | ConvertFrom-Json
$expectedHash = [string]$package.executableSha256
if ($expectedHash -notmatch '\A[0-9A-Fa-f]{64}\z') { throw 'PACKAGE.json must contain a valid executableSha256.' }
$sourceExe = Join-Path $PSScriptRoot 'MicBridge-AutoOutput.exe'
if ((Get-FileHash -LiteralPath $sourceExe -Algorithm SHA256).Hash -ne $expectedHash) {
    throw 'The executable does not match PACKAGE.json. No installation changes were made.'
}
if ($null -ne (Read-StartupValue)) { throw 'The MicBridge-AutoOutput startup value already exists. Existing installations are not replaced.' }
if ((Get-Helpers).Count -ne 0) { throw 'An output helper is already running. Existing installations are not replaced.' }
if (Test-Path -LiteralPath $folder) {
    $item = Get-Item -LiteralPath $folder -Force
    if (!$item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'The installation target is not an ordinary directory. No changes were made.'
    }
    if (@(Get-ChildItem -LiteralPath $folder -Force).Count -ne 0) {
        throw 'The installation folder is not empty. Existing installation and recovery files are not replaced.'
    }
}
Assert-DisconnectedLog
if ($ValidateOnly) {
    [pscustomobject]@{ validated = $true; installFolder = $folder; apolloLog = $ApolloLog
        helperSha256 = $expectedHash; changesMade = $false; audioDeviceCheckDeferredUntilInstallation = $true }
    return
}

$exe = Join-Path $folder 'MicBridge-AutoOutput.exe'
$arguments = '--watch "' + $ApolloLog + '"'
$startupValue = '"' + $exe + '" ' + $arguments
$startupCreated = $false
$started = $null
try {
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    foreach ($name in $payload) { Copy-NewPayloadFile $name }
    if ((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash -ne $expectedHash) { throw 'Installed executable integrity check failed.' }
    if ($null -ne (Read-StartupValue) -or (Get-Helpers).Count -ne 0) { throw 'Another output helper or startup value appeared during setup.' }
    $snapshotPath = Join-Path $folder 'installation-snapshot.json'
    $snapshotProcess = Start-Process -FilePath $exe -ArgumentList @('--snapshot', ('"' + $snapshotPath + '"')) -WorkingDirectory $folder -WindowStyle Hidden -PassThru
    if (!$snapshotProcess.WaitForExit(15000)) {
        # This is our read-only --snapshot child, not a running watcher.
        if ([string]::Equals($snapshotProcess.Path, $exe, [StringComparison]::OrdinalIgnoreCase)) {
            $snapshotProcess.Kill(); [void]$snapshotProcess.WaitForExit(3000)
        }
        throw 'Timed out reading Windows audio devices. No startup entry was registered.'
    }
    if ($snapshotProcess.ExitCode -ne 0) { throw 'Windows output devices could not be inspected. No startup entry was registered.' }
    $snapshot = Get-Content -LiteralPath $snapshotPath -Raw | ConvertFrom-Json
    $render = @($snapshot.defaults | Where-Object flowIndex -eq 0 | Sort-Object roleIndex)
    if ($render.Count -ne 3 -or @($render.roleIndex | Sort-Object -Unique).Count -ne 3 -or
        @($render | Where-Object { $_.roleIndex -lt 0 -or $_.roleIndex -gt 2 -or [string]::IsNullOrEmpty($_.deviceId) }).Count) {
        throw 'Exactly three valid Windows output roles are required.'
    }
    $speakers = @($snapshot.endpoints | Where-Object {
        $_.flowIndex -eq 0 -and $_.state -eq 1 -and $_.name -like '*Steam Streaming Speakers*' -and $_.name -notlike '*Steam Streaming Microphone*'
    })
    if ($speakers.Count -ne 1) { throw 'Exactly one active Steam Streaming Speakers output is required. Install the Steam audio driver separately.' }
    foreach ($row in $render) {
        $endpoint = @($snapshot.endpoints | Where-Object { $_.flowIndex -eq 0 -and $_.state -eq 1 -and $_.deviceId -eq $row.deviceId })
        if ($endpoint.Count -ne 1 -or $row.name -like '*Steam Streaming*' -or $endpoint[0].name -like '*Steam Streaming*') {
            throw 'Select the normal physical output and end streaming before installation. A Steam output cannot be the original baseline.'
        }
    }
    # Save known idle outputs before the first watcher tick can race a new connection.
    [IO.File]::WriteAllText((Join-Path $folder 'output-baseline.json'),
        ([ordered]@{ Version = 1; Defaults = $render } | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    Assert-DisconnectedLog
    if ($null -ne (Read-StartupValue) -or (Get-Helpers).Count -ne 0) { throw 'Another output helper or startup value appeared during setup.' }
    if (!(Test-Path -LiteralPath $runKey)) { New-Item -Path $runKey -Force | Out-Null }
    New-ItemProperty -LiteralPath $runKey -Name $entryName -Value $startupValue -PropertyType String -ErrorAction Stop | Out-Null
    $startupCreated = $true
    $started = Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $folder -WindowStyle Hidden -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    $status = $null
    do {
        $started.Refresh()
        if ($started.HasExited) { throw 'The output helper exited before startup could be verified.' }
        if (![string]::Equals($started.Path, $exe, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected output helper process path.' }
        $statusPath = Join-Path $folder 'status.json'
        if (Test-Path -LiteralPath $statusPath -PathType Leaf) {
            try { $candidate = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json } catch { $candidate = $null }
            if ($null -ne $candidate -and $candidate.watcherPid -eq $started.Id) {
                if ($candidate.error) { throw ('Output helper startup error: ' + $candidate.error) }
                if ($candidate.baselineReady) { $status = $candidate; break }
            }
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($null -eq $status) { throw 'Timed out waiting for this output helper process to report a healthy baseline.' }
    $receipt = [ordered]@{
        installedAtUtc = [DateTime]::UtcNow.ToString('o'); helperSha256 = $expectedHash
        installFolder = $folder; apolloLog = $ApolloLog; startupMode = 'current-user-run-key'
        watcherPid = $started.Id; renderOnly = $true; restoreDelayMs = 2000
        hostFilesChanged = $false; phoneAppChanged = $false; microphoneHelperChanged = $false
    }
    $receipt | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $folder 'installation.json') -Encoding UTF8
    $receipt | ConvertTo-Json
} catch {
    $failure = $_
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
                if ($stop.ExitCode -ne 0) { Write-Warning 'The output helper stop request failed; do not force-kill it.' }
                if (!$started.WaitForExit(10000)) { Write-Warning 'The output helper is still stopping; do not force-kill it.' }
            }
        } catch { Write-Warning ('Automatic graceful stop could not be verified: ' + $_.Exception.Message) }
    }
    Write-Warning 'Setup did not complete. Files and any original-output.json recovery journal were retained. If the installed executable and Disable.ps1 exist, run Disable.ps1 after ending streaming. Do not delete recovery files.'
    throw $failure
}
