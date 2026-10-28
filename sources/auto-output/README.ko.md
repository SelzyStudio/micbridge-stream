# Selzy Studio — MicBridge Auto Output 1.0.0-rc1

원격 Apollo 연결 때 Windows 기본 **출력**을 Steam Streaming Speakers로 바꾸고, 마지막 세션 종료 후 2초간 재연결되지 않으면 연결 전 출력으로 복원하는 선택적 PC 보조 프로그램입니다. Console·Multimedia·Communications의 원래 출력 장치를 각각 저장합니다.

**APK·Apollo 호스트는 업데이트할 필요가 없습니다.** 기본 [Android APK·Windows 호스트](https://github.com/SelzyStudio/micbridge-stream/releases/tag/v1.0.0-rc1)를 먼저 설치하세요. 마이크 입력 자동 전환은 별도의 [Auto Input 추가 기능](https://github.com/SelzyStudio/micbridge-stream/releases/tag/auto-input-v1.0.0-rc1)입니다. 두 프로그램은 서로 다른 시작 항목·복원 기록을 사용합니다.

## 다운로드·조건

[전체 추가 기능 ZIP·소스 ZIP·체크섬](https://github.com/SelzyStudio/micbridge-stream/releases/tag/auto-output-v1.0.0-rc1)

- Windows x64, .NET Framework 4.x, 읽을 수 있는 현재 Apollo sunshine.log와 공식 Steam 오디오 드라이버가 필요합니다. Steam 드라이버는 포함하지 않습니다.
- 활성 출력 장치 이름에 `Steam Streaming Speakers`가 포함된 장치가 정확히 하나 있어야 합니다. **Steam Streaming Microphone이라는 출력 장치는 선택하지 않습니다.** 장치를 다른 이름으로 바꾸면 검색하지 못할 수 있습니다.
- Windows 11 x64의 기존 구현에서 장치 전환을 시험했습니다. 다른 물리 PC·제조사·드라이버는 미검증입니다. 프로그램은 이 저장소의 Apollo 로그 형식, 하나의 sunshine 프로세스와 비공개 Windows IPolicyConfig 인터페이스에 의존합니다.
- **실행 파일과 스크립트는 Authenticode 미서명 시험판입니다.** Windows 경고나 조직의 실행 제한이 있을 수 있습니다. Defender 탐지 없음은 서명이나 안전 보장을 대신하지 않습니다. 보호 기능을 끄거나 예외를 추가하지 마세요.

## 설치

1. 원격 스트리밍을 종료하고 PC의 원래 스피커를 선택한 상태에서 ZIP **전체**를 풉니다. 관리자 권한 없이 현재 사용자 PowerShell을 엽니다.
2. `Get-FileHash .\MicBridge-AutoOutput.exe -Algorithm SHA256`을 실행해 `PACKAGE.json`의 `executableSha256` 및 릴리스 체크섬과 비교합니다.
3. `& .\Install.ps1 -ValidateOnly`로 파일·경로·시작 항목 충돌을 사전 점검합니다. 이 모드에서는 오디오 장치 검사를 실행하지 않습니다.
4. `& .\Install.ps1`을 실행합니다. 다른 로그 경로는 `& .\Install.ps1 -ApolloLog 'D:\Apollo\config\sunshine.log'`처럼 지정합니다.

다운로드 차단·스크립트 정책으로 실행이 거부되면 출처와 내용을 검토한 뒤 해당 파일의 차단 해제나 조직의 허용 절차를 따르세요. 설치 스크립트는 실행 정책을 변경하지 않습니다.

설치 위치는 `%LOCALAPPDATA%\MicBridge\AutoOutput`, 로그인 시작 항목은 현재 사용자의 HKCU `MicBridge-AutoOutput`입니다. 기존 폴더·실행 중 프로그램·같은 이름의 시작 항목이 있으면 덮어쓰지 않고 중단합니다. 기존 정상 설치를 이 릴리스 때문에 바로 교체할 필요는 없습니다.

업데이트는 스트리밍 종료 → **설치 폴더**의 Disable.ps1 실행 → 복원이 끝나 `original-output.json`이 없는지 확인 → 기존 폴더를 별도로 보관 → 새 설치 순서입니다. 복원 대기 중인 폴더는 옮기거나 삭제하지 마세요.

## 복원 방식·한계

Apollo는 연결 알림보다 먼저 출력을 바꿀 수 있으므로, 연결 전 대기 중의 장치를 `output-baseline.json`으로 저장합니다. 이미 Steam으로 바뀐 상태로 원본을 덮어쓰지 않습니다. 기준 정보가 없는 첫 실행이 이미 스트리밍 중이면 원래 출력을 추측하지 않고 오류를 표시합니다. 연결을 종료하고 원래 출력을 선택한 뒤 다시 확인하세요.

장치를 바꾸기 전에 `original-output.json`에 복원 기록을 남깁니다. 원래 장치가 분리되면 기록을 보관하고 재연결 후 복원합니다. 짧은 재연결이나 앱의 미디어 일시정지에는 전환 상태를 유지합니다.

Apollo가 종료 중 출력 역할을 순서대로 Console 장치로 바꾸는 경우도 교정합니다. 역할별 원본이 다르면 최초 복원 이후 최대 35초의 정상 정리 감시가 적용됩니다. 같은 장치인 경우에도 2초간 안정된 것을 확인한 후 기록을 마칩니다. 따라서 이미 소리가 복원됐어도 `restorationPending`이 잠시 남을 수 있습니다.

이 감시 구간에서 사용자가 다른 역할을 **기존 Console 출력과 같은 장치**로 직접 바꾸는 동작은 Apollo의 자동 정리와 구별할 수 없어 되돌려질 수 있습니다. 감시가 끝난 뒤 수동 변경하세요. 다른 임의 장치로 바꾼 선택은 가능한 범위에서 보존합니다. 관측 사이에 끝난 짧은 장치 분리·복귀나 향후 Apollo의 다른 정리 순서는 완전히 판별하지 못할 수 있습니다.

강제 종료 즉시 복원은 보장하지 않습니다. 다음 실행에서 저장 기록을 이용합니다. 자동 실행 해제·복원은 스트리밍을 먼저 종료한 뒤 **설치 폴더의 Disable.ps1**을 실행하세요. 정상 복원이 끝나면 파일은 복구를 위해 보존되며 직접 삭제할 수 있습니다.

## 개인정보·소스·검증

이 프로그램은 기본 출력 역할만 바꾸며 마이크·음소거·볼륨·Apollo 설정·APK는 변경하지 않습니다. 음성 녹음이나 네트워크 전송은 없습니다. 로컬 상태·복원 기록에는 장치 이름·ID·경로·프로세스 정보가 들어갈 수 있으니 외부 공유 전 제거하세요.

`Build.ps1`은 포함된 C# 소스만 사용해 `build`에 컴파일하고 가상 장치 회귀 검사를 실행합니다. 실제 Windows 오디오를 변경하지 않습니다. 재빌드는 PE 식별자 때문에 배포 파일과 해시가 달라질 수 있습니다.

기존 구현의 실제 Windows 출력 3역할 전환·복원, 입력 보존, 짧은 재연결 검사는 통과했고 사용자가 실제 원격 연결 정상 동작을 확인했습니다. 이번 공개 후보는 부분 복원 오인과 복원 기록 저장 순서를 보완하고 가상 회귀 검사를 추가했습니다. **수정 후 후보를 현재 PC에 설치하거나 휴대폰 시험을 반복하지 않았습니다.** 새 배포 스크립트는 구문·사전 점검을 확인하되 전체 설치·업그레이드·제거 실기는 미검증입니다. 정확한 해시·검사 수·범위는 VALIDATION.json을 확인하세요.

[English](README.en.md) · [GPL-3.0](LICENSE) · [출처 고지](NOTICE.md) · [문제 제보](https://github.com/SelzyStudio/micbridge-stream/issues)
