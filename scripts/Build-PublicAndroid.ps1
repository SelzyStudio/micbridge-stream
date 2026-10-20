#Requires -Version 7.0
$ErrorActionPreference='Stop'
$workspace=Split-Path -Parent $PSScriptRoot
$candidate=Join-Path $workspace 'distribution-work\2026-10-20-r1'
$env:JAVA_HOME=Join-Path $workspace 'toolchains\android\jdk'
$env:ANDROID_HOME=Join-Path $workspace 'toolchains\android\sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:ANDROID_USER_HOME=Join-Path $candidate 'android-user'
$env:GRADLE_USER_HOME=Join-Path $candidate 'gradle-home'
$env:PATH=$env:JAVA_HOME+'\bin;'+$env:PATH
# Use a separate cache. Read-only copies avoid re-downloading already verified Gradle/Maven inputs.
if(-not(Test-Path -LiteralPath $env:GRADLE_USER_HOME)){
 Copy-Item -LiteralPath (Join-Path $workspace 'toolchains\android\gradle-home') -Destination $env:GRADLE_USER_HOME -Recurse
}
$tests=(Get-Content -LiteralPath (Join-Path $workspace 'audit\audio-noise-2026-10-20\regression-summary.json') -Raw | ConvertFrom-Json).suites.name
$arguments=@('--no-daemon','--max-workers=3','testNonRoot_gameDebugUnitTest')
foreach($test in $tests){$arguments+=@('--tests',$test)}
$arguments+=@('assembleNonRoot_gameRelease')
Push-Location -LiteralPath (Join-Path $candidate 'checkouts\artemis')
try{
 & .\gradlew.bat @arguments *> (Join-Path $candidate 'logs\android-public-build.log')
 $result=$LASTEXITCODE
} finally {Pop-Location}
exit $result
