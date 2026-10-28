# Selzy Studio — MicBridge Stream / Host

[한국어](README.ko.md) · **English**

An unofficial, modified version of Apollo and Artemis that forwards an Android microphone to a remote Windows PC. This **1.0.0-rc1 test release** is distributed by **Selzy Studio**.

Distribution brand: **[Selzy Studio](https://github.com/SelzyStudio)** · Publishing account: [@selzystudio-01](https://github.com/selzystudio-01).

## Downloads

[Release and all files](https://github.com/SelzyStudio/micbridge-stream/releases/tag/v1.0.0-rc1) · [Android universal APK](https://github.com/SelzyStudio/micbridge-stream/releases/download/v1.0.0-rc1/MicBridge-Stream-1.0.0-rc1-universal.apk) · [Windows x64 installer](https://github.com/SelzyStudio/micbridge-stream/releases/download/v1.0.0-rc1/MicBridge-Host-1.0.0-rc1-windows-x64-setup.exe)

- If you are unsure which Android build to choose, use the **universal APK**. It includes ARM64, ARMv7, x86, and x86_64. Its app ID is `app.micbridge.stream`; it installs and pairs separately from the existing Artemis app.
- On Windows, use the x64 installer or portable ZIP. The Steam audio driver is not included. A new PC requires the official Steam driver, permissions, firewall configuration, pairing, and the Windows default input setting to be configured.
- The declared minimum requirements are Android 5.0 (API 21) and OpenGL ES 3.0. Emulator execution was checked on Android 6 and 15; not every manufacturer or device model has been tested.
- **The Windows installer is not Authenticode-signed.** Final microphone, audio, and network-switching tests on another physical PC and Android phone remain outstanding, so this is distributed as a test release.

## Optional PC microphone switching

Use the [MicBridge Auto Input add-on](https://github.com/SelzyStudio/micbridge-stream/releases/tag/auto-input-v1.0.0-rc1) to select Steam Streaming Microphone on connection and restore previous inputs when the session ends. The APK and host packages remain unchanged. See [setup and validation limits](sources/auto-input/README.en.md).

## Features

- Android microphone forwarding; screen streaming, PC audio, and microphone forwarding pause while the app is in the background and resume when it returns to the foreground
- Automatic reconnection when switching between Wi-Fi and LTE
- Five-finger tap to switch between touch input and relative trackpad mode; default sensitivity of 50%
- Fix for noise during PC audio playback

Installation guides: [Android](docs/ANDROID-SUPPORT.md), [Windows (Korean)](packaging/public/README-Windows.ko.txt). Build instructions: [BUILDING](docs/BUILDING.md).

## Source code and licenses

Upstream projects: [Apollo microphone](https://github.com/logabell/apollo-microphone), [Artemis microphone](https://github.com/logabell/moonlight-android), [Apollo](https://github.com/ClassicOldSong/Apollo), [Sunshine](https://github.com/LizardByte/Sunshine), and [Moonlight Android](https://github.com/moonlight-stream/moonlight-android). This is not an official release from the upstream teams.

Application source is available in `sources/apollo` and `sources/artemis`. The original copyright and component-specific notices are preserved, including GPL/AGPL, Apache, BSD, MIT, and Classpath exception notices. See [LICENSE](LICENSE) and the [source distribution and publication guide](docs/PUBLISHING.md).

Exact corresponding source: [Corresponding-Source.zip](https://github.com/SelzyStudio/micbridge-stream/releases/download/v1.0.0-rc1/MicBridge-Stream-1.0.0-rc1-Corresponding-Source.zip) and [Build-Tool-Sources.zip](https://github.com/SelzyStudio/micbridge-stream/releases/download/v1.0.0-rc1/MicBridge-1.0.0-rc1-Build-Tool-Sources.zip). **Both source archives are provided together.** The smaller, automatically generated GitHub “Source code” ZIP does not replace the original dependency archives.

The corresponding-source ZIPs and binaries preserve the build files that were checked. References inside them to unpublished or planned URLs are records from the build-preparation stage. Source links now point to the actual downloads for this release. The repository's public documentation was updated at publication time; application runtime source was left unchanged.

## Verification scope

The recorded checks cover 422 selected Android regression tests, 22 Windows regression tests, Android 6 and 15 emulators, APK signing and 16 KB alignment, and installation, reinstallation, configuration preservation, and uninstallation in an isolated Windows folder. Apart from APK information text, the new candidate's executable and native contents and the host executable are identical to the previously verified candidate. Defender scanning of those files recorded zero detections. These results do not guarantee operation on every device or the absence of all vulnerabilities.

Use the release's `SHA256SUMS.txt` to verify downloaded files. Personal information, actual certificates, pairing data, and private signing keys are not included in the public materials.
