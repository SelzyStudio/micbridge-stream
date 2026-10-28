# Selzy Studio — MicBridge Stream / Host

**한국어** · [English](README.en.md)

Android 마이크를 원격 Windows PC에 전달하는 비공식 Apollo·Artemis 수정판입니다. **Selzy Studio**에서 배포하는 **1.0.0-rc1 시험판**입니다.

배포 브랜드: **[Selzy Studio](https://github.com/SelzyStudio)** · 게시 계정: [@selzystudio-01](https://github.com/selzystudio-01).

## 다운로드

[릴리스와 전체 파일](https://github.com/SelzyStudio/micbridge-stream/releases/tag/v1.0.0-rc1) · [Android 범용 APK](https://github.com/SelzyStudio/micbridge-stream/releases/download/v1.0.0-rc1/MicBridge-Stream-1.0.0-rc1-universal.apk) · [Windows x64 설치 파일](https://github.com/SelzyStudio/micbridge-stream/releases/download/v1.0.0-rc1/MicBridge-Host-1.0.0-rc1-windows-x64-setup.exe)

- Android 기종이 정해지지 않았다면 **universal APK**를 사용하세요. ARM64·ARMv7·x86·x86_64를 포함합니다. 앱 ID는 `app.micbridge.stream`이며 기존 Artemis와 별도로 설치·페어링합니다.
- Windows는 x64 설치 파일 또는 포터블 ZIP을 사용합니다. Steam 오디오 드라이버는 포함하지 않았습니다. 새 PC는 공식 Steam 드라이버, 권한·방화벽·페어링과 Windows 기본 입력 설정이 필요합니다.
- 선언된 최소 조건은 Android 5.0(API 21)·OpenGL ES 3.0입니다. 가상 기기 실행 검증은 Android 6과 15에서 수행했으며 모든 제조사·기종을 시험한 것은 아닙니다.
- **Windows 설치 파일은 Authenticode 미서명입니다.** 다른 물리 PC·Android폰의 최종 마이크·소리·네트워크 전환 시험은 남아 있어 시험판으로 배포합니다.

## 선택적 PC 마이크 자동 전환

원격 연결 때 Steam Streaming Microphone으로 바꾸고 종료 후 원래 입력을 복원하려면 [MicBridge Auto Input 추가 기능](https://github.com/SelzyStudio/micbridge-stream/releases/tag/auto-input-v1.0.0-rc1)을 설치하세요. APK·호스트는 그대로 사용합니다. [설치·검증 범위](sources/auto-input/README.ko.md).

## 선택적 PC 출력 자동 전환

원격 연결 때 Steam Streaming Speakers를 선택하고 종료 후 원래 스피커를 복원하려면 [MicBridge Auto Output 추가 기능](https://github.com/SelzyStudio/micbridge-stream/releases/tag/auto-output-v1.0.0-rc1)을 사용하세요. 기존 마이크 추가 기능과 독립적으로 설치합니다. [설치·검증 범위](sources/auto-output/README.ko.md).

## 기능

- Android 마이크 전달, 백그라운드에서 화면·PC 소리·마이크 일시 중지 후 복귀
- Wi-Fi/LTE 전환 시 자동 연결 복구
- 다섯 손가락 탭으로 터치 ↔ 상대 트랙패드 전환, 기본 감도 50%
- PC 소리 재생 잡음 수정

설치 안내: [Android](docs/ANDROID-SUPPORT.md), [Windows](packaging/public/README-Windows.ko.txt). 빌드: [BUILDING](docs/BUILDING.md).

## 소스와 라이선스

원본 프로젝트: [Apollo microphone](https://github.com/logabell/apollo-microphone), [Artemis microphone](https://github.com/logabell/moonlight-android), [Apollo](https://github.com/ClassicOldSong/Apollo), [Sunshine](https://github.com/LizardByte/Sunshine), [Moonlight Android](https://github.com/moonlight-stream/moonlight-android). 원본 개발팀의 공식 릴리스가 아닙니다.

앱 소스는 `sources/apollo`와 `sources/artemis`에 있습니다. 원래의 저작권과 GPL/AGPL·Apache·BSD·MIT·Classpath 예외 등 구성 요소별 고지를 유지합니다. [LICENSE](LICENSE), [소스 제공·배포 안내](docs/PUBLISHING.md)를 확인하세요.

정확한 대응 소스: [Corresponding-Source.zip](https://github.com/SelzyStudio/micbridge-stream/releases/download/v1.0.0-rc1/MicBridge-Stream-1.0.0-rc1-Corresponding-Source.zip) 및 [Build-Tool-Sources.zip](https://github.com/SelzyStudio/micbridge-stream/releases/download/v1.0.0-rc1/MicBridge-1.0.0-rc1-Build-Tool-Sources.zip). **두 소스 파일을 함께 제공**하며 작은 GitHub 자동 Source code ZIP만으로 의존성 원본 아카이브를 대체하지 않습니다.

대응 소스 ZIP과 바이너리는 검사한 빌드 파일을 그대로 보존합니다. 그 안의 '미공개/예정 URL' 표기는 빌드 준비 시점의 기록입니다. 소스 URL은 이 릴리스의 실제 다운로드 주소로 연결됩니다. 저장소의 공개 안내 문서는 게시 시점에 갱신했으며 앱 실행 소스는 그대로입니다.

## 검증 범위

선택한 Android 회귀 422개, Windows 회귀 22개, Android 6/15 가상 기기, APK 서명·16KB 정렬, Windows 격리 폴더의 설치·재설치·설정 보존·제거를 확인했습니다. 새 후보는 APK 안내문 외 실행·네이티브 내용과 호스트 실행 파일이 이전 검증 후보와 동일합니다. 해당 파일의 Defender 검사에서 탐지 0을 기록했습니다. 모든 기기 동작이나 모든 취약점 부재를 보장하는 결과는 아닙니다.

릴리스의 `SHA256SUMS.txt`로 다운로드 파일을 확인할 수 있습니다. 개인정보·실제 인증서·페어링·개인 서명 키는 공개 자료에 포함하지 않았습니다.
