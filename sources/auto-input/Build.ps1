#Requires -Version 5.1
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$output = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Windows x64 .NET Framework 4.x compiler is required.' }
$exe = Join-Path $output 'MicBridge-AutoInput.exe'
& $compiler /nologo /target:winexe /platform:x64 /optimize+ /reference:System.Web.Extensions.dll /reference:System.Management.dll "/out:$exe" "$PSScriptRoot\CoreAudio.cs" "$PSScriptRoot\MicrophoneAutoSwitch.cs" "$PSScriptRoot\Tests.cs"
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
$report = Join-Path $output 'test-results.json'
$test = Start-Process -FilePath $exe -ArgumentList @('--self-test', ('"' + $report + '"')) -WindowStyle Hidden -Wait -PassThru
if ($test.ExitCode -ne 0) { throw "Regression tests failed: $report" }
Get-Content -LiteralPath $report
Write-Output "Built $exe (a rebuild can have a different PE hash from the published binary)."
