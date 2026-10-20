# MicBridge Stream / Host

Android 마이크를 원격 Windows PC로 전달하는 비공식 Apollo·Artemis 수정판입니다. 공개 후보 **1.0.0-rc1**이며 아직 업로드하지 않았습니다.

Android 앱 ID는 `app.micbridge.stream`입니다. 기존 Artemis·개인 시험판과 별도로 설치되며 새 페어링이 필요합니다. Windows 기본 설치 위치는 `%LOCALAPPDATA%\Programs\MicBridge-Host`입니다.

유지한 기능: 마이크 전달, 백그라운드 절전과 복귀, 네트워크 전환 시 빠른 복구, 다섯 손가락 입력 모드 전환, 상대 트랙패드 기본 감도 50%, 오디오 재생 잡음 수정. 철회한 Windows 키보드 보정 실험과 Codex 수정은 포함하지 않습니다.

공개판에는 OpenSSL 3.5.9·Opus 1.6.1 소스 빌드, Android 5/6 API 호환성 설정, 별도 앱 서명, 앱 내 소스·라이선스 안내를 추가했습니다. 호스트는 다른 프로젝트의 바이너리를 자동 업데이트 대상으로 안내하지 않습니다.

- 원본: [Apollo microphone](https://github.com/logabell/apollo-microphone), [Artemis microphone](https://github.com/logabell/moonlight-android), [Sunshine](https://github.com/LizardByte/Sunshine), [Moonlight Android](https://github.com/moonlight-stream/moonlight-android).
- 설치와 검증 범위: [배포 상태](docs/DISTRIBUTION-STATUS.md), [Android 지원](docs/ANDROID-SUPPORT.md).
- 빌드: [BUILDING](docs/BUILDING.md). 소스 스냅샷이 기준이며 패치는 보조 자료입니다.
- 공개 절차: [PUBLISHING](docs/PUBLISHING.md). 대응 소스 파일을 바이너리와 함께 제공해야 합니다.

프로젝트를 GPL로 표시했다고 모든 구성 요소의 조건이 대체되지는 않습니다. 원래의 저작권·GPL/AGPL·Apache·BSD·MIT·Classpath 예외 및 개별 고지를 보존했습니다. 자동 수집한 의존성 목록은 법률 검토나 모든 취약점 부재의 보증이 아닙니다.
