#Requires -Version 5.1
# Read-only preflight; deliberately never installs drivers or changes audio defaults.
$ErrorActionPreference='Stop'
$devices=@(Get-PnpDevice -Class AudioEndpoint -PresentOnly -ErrorAction SilentlyContinue)
$mic=@($devices | Where-Object {$_.FriendlyName -match 'Microphone.*Steam Streaming Microphone'})
$speakers=@($devices | Where-Object {$_.FriendlyName -match 'Steam Streaming Speakers'})
$ports=@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {$_.LocalPort -in 47984,47989,47990,48010} | Select-Object -ExpandProperty LocalPort -Unique)
[pscustomobject]@{
 Windows64Bit=[Environment]::Is64BitOperatingSystem
 SteamMicrophoneEndpointPresent=($mic.Count -gt 0)
 SteamSpeakersEndpointPresent=($speakers.Count -gt 0)
 HostAlreadyRunning=([bool](Get-Process -Name sunshine -ErrorAction SilentlyContinue))
 OccupiedHostTcpPorts=$ports
 ChangesMade='None: read-only preflight'
} | Format-List
if($mic.Count -eq 0){Write-Host 'Install official Steam audio drivers first. See README.ko.txt. Do not download repackaged driver files.'}
Write-Host 'Select Microphone (Steam Streaming Microphone) for Windows voice input. Keep normal speakers/headphones as output.'
