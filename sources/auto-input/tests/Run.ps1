#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
$base = Split-Path -Parent $PSScriptRoot
$output = Join-Path $base 'build\adversarial'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
& $compiler /nologo /target:exe /main:Adversarial /reference:System.Web.Extensions.dll /reference:System.Management.dll "/out:$output\Adversarial.exe" "$base\CoreAudio.cs" "$base\MicrophoneAutoSwitch.cs" "$base\Tests.cs" "$PSScriptRoot\Adversarial.cs"
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
& "$output\Adversarial.exe"
if ($LASTEXITCODE -ne 0) { throw 'Test runner failed.' }
$cases = Get-Content -LiteralPath (Join-Path $output 'RESULTS.json') -Raw | ConvertFrom-Json
foreach ($case in $cases) {
    if ($case.exception) { throw ("Test threw: " + $case.label) }
    if ($case.result.PSObject.Properties['pass'] -and -not $case.result.pass) { throw ("Test failed: " + $case.label) }
}
'All adversarial assertions passed. The case without a pass field is an informational log-parser observation.'
