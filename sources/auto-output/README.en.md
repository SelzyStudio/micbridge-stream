# Selzy Studio — MicBridge Auto Output 1.0.0-rc1

An optional Windows x64 helper that selects **Steam Streaming Speakers** when an Apollo session connects, then restores the previous output device for each of the three Windows roles after the final session ends and a two-second reconnect grace period expires.

Use the existing [host and APK](https://github.com/SelzyStudio/micbridge-stream/releases/tag/v1.0.0-rc1). No phone update is needed. Microphone switching is a separate optional [Auto Input helper](https://github.com/SelzyStudio/micbridge-stream/releases/tag/auto-input-v1.0.0-rc1).

Download the full [add-on ZIP](https://github.com/SelzyStudio/micbridge-stream/releases/tag/auto-output-v1.0.0-rc1), extract everything, and compare the executable SHA256 with PACKAGE.json and the release checksums. End streaming and select your normal PC speakers. In normal-user PowerShell, run `Install.ps1 -ValidateOnly`, then `Install.ps1`. A custom log path is accepted with `-ApolloLog`. Preflight does not run an audio device probe. Installation uses `%LOCALAPPDATA%\MicBridge\AutoOutput` and the current user's `MicBridge-AutoOutput` startup Run entry; existing installations or conflicting entries/processes are rejected. Administrator permission is not required.

Requirements: Windows x64, .NET Framework 4.x, the supported Apollo log format and one sunshine process, and exactly one active render device containing `Steam Streaming Speakers` in its name. Steam drivers are not bundled. The render device named Steam Streaming Microphone is deliberately excluded. Renamed devices may not be found. Logs larger than 64MiB or unreadable/unsupported logs prevent normal detection.

**The executable and scripts are not Authenticode-signed.** Windows may display warnings or enforce execution policies; setup does not bypass them. A clean Defender scan does not guarantee safety. Do not disable antivirus or add exclusions.

An idle baseline is saved before Apollo can change defaults ahead of the connection marker. `original-output.json` is written before changing devices and retained if an original device is absent. A first startup while already streaming without a baseline fails safely. Hard-kill recovery happens on a later startup, not immediately. Short reconnects and background media pauses retain the Steam selection.

Different original output roles use a 35-second cleanup guard because Apollo restores roles sequentially to its original Console device. A manual choice of that same Console device on another role during the guard is indistinguishable and may be corrected; wait until restoration finishes before making that change. Other arbitrary choices are preserved where observable. Very brief driver loss/return between observations and future Apollo cleanup behavior have limits.

To disable, first end streaming, then run Disable.ps1 **from the installed folder**. Files are retained for recovery. Reconnect an absent original device and retry. For an upgrade, finish disable/restoration, verify original-output.json is absent, preserve the old folder elsewhere, then install anew. Do not move/delete pending recovery files. Existing working users do not need to replace their installation immediately.

The helper does not record/transmit audio, change microphone defaults/volume/mute, edit Apollo configuration, or update the phone. Local diagnostic and recovery files contain device names/IDs and process/path information; redact them before sharing.

Build.ps1 compiles included C# files into `build` and runs fake-adapter regressions without changing real audio. Rebuilt PE hashes can differ. The earlier implementation passed real Windows output switching/restoration, input preservation and reconnect tests, and the user confirmed real remote operation. This publication candidate fixes partial-role cleanup recognition and recovery-record commit ordering, with additional fake regressions. **The fixed candidate was not reinstalled on the working PC or retested with the phone.** New packaging scripts have syntax/preflight checks; their full install/upgrade/remove lifecycle and other physical PCs remain unverified. See VALIDATION.json for exact scope.

[Korean guide](README.ko.md) · [GPL-3.0](LICENSE) · [Notices](NOTICE.md) · [Issues](https://github.com/SelzyStudio/micbridge-stream/issues)
